package com.example.tasklist.lending.application.service;

import com.example.tasklist.lending.application.client.LenderClient;
import com.example.tasklist.lending.application.domain.Application;
import com.example.tasklist.lending.application.domain.ApplicationStatus;
import com.example.tasklist.lending.application.domain.LenderBlockResult;
import com.example.tasklist.lending.application.event.ApplicationLimitBlockedEvent;
import com.example.tasklist.lending.application.outbox.OutboxMessage;
import com.example.tasklist.lending.application.outbox.OutboxRepository;
import com.example.tasklist.lending.application.repository.ApplicationRepository;
import com.example.tasklist.lending.application.service.exception.ApplicationNotFoundException;
import com.example.tasklist.lending.application.service.exception.InvalidApplicationStatusException;
import com.example.tasklist.lending.application.service.exception.LenderBlockingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class LenderService implements ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(LenderService.class);
    private static final String APPLICATION_LIMIT_BLOCKED_TOPIC = "application-limit-blocked";

    private final ApplicationRepository applicationRepository;
    private final OutboxRepository outboxRepository;
    private final LenderClient lenderClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public LenderService(
            ApplicationRepository applicationRepository,
            OutboxRepository outboxRepository,
            LenderClient lenderClient,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry
    ) {
        this.applicationRepository = applicationRepository;
        this.outboxRepository = outboxRepository;
        this.lenderClient = lenderClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void blockLenderLimit(UUID applicationId) {
        MDC.put("applicationId", applicationId.toString());
        try {
            doBlockLenderLimit(applicationId);
        } finally {
            MDC.remove("applicationId");
        }
    }

    private void doBlockLenderLimit(UUID applicationId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        if (application.status() == ApplicationStatus.LIMIT_BLOCKED) {
            log.info("Application {} limit already blocked, treating as idempotent retry", applicationId);
            return;
        }
        if (application.status() != ApplicationStatus.SCORING_APPROVED) {
            throw new InvalidApplicationStatusException(applicationId, ApplicationStatus.SCORING_APPROVED, application.status());
        }

        // The lender call is a slow, unreliable network operation, so it deliberately happens
        // outside any DB transaction — a local transaction should only ever wrap fast DB work.
        LenderBlockResult blockResult = callLender(application);

        // Applying the result (status transition + outbox write) is one short local transaction,
        // which is what makes "update the application" and "record the event to publish" atomic.
        Boolean applied = transactionTemplate.execute(status -> {
            boolean updated = applicationRepository.compareAndSetLimitBlocked(
                    applicationId, ApplicationStatus.SCORING_APPROVED, blockResult.blockId());

            if (updated) {
                outboxRepository.save(buildOutboxMessage(application, blockResult));
            }
            return updated;
        });

        if (Boolean.FALSE.equals(applied)) {
            // Lost the race to a concurrent call (or a retried request). Since the lender call
            // above is idempotent per application, the limit is safely blocked either way —
            // nothing left to persist here.
            log.info("Application {} was already transitioned by a concurrent call; lender block {} is idempotent, nothing to persist",
                    applicationId, blockResult.blockId());
        }
    }

    private LenderBlockResult callLender(Application application) {
        // Deterministic per-application request id: if this method is retried after a crash
        // or timeout, the lender returns the same block instead of blocking funds twice.
        String requestId = "app-limit-block:" + application.id();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            LenderBlockResult result = lenderClient.blockLimit(
                    application.lenderId(),
                    application.id(),
                    application.requestedAmount(),
                    requestId
            );
            sample.stop(lenderCallTimer("success"));
            return result;
        } catch (RuntimeException ex) {
            sample.stop(lenderCallTimer("failure"));
            throw new LenderBlockingException(application.id(), ex);
        }
    }

    private Timer lenderCallTimer(String outcome) {
        return Timer.builder("lender.block_limit")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private OutboxMessage buildOutboxMessage(Application application, LenderBlockResult blockResult) {
        ApplicationLimitBlockedEvent event = new ApplicationLimitBlockedEvent(
                application.id(),
                application.clientId(),
                application.lenderId(),
                blockResult.blockId(),
                application.requestedAmount(),
                Instant.now()
        );
        return OutboxMessage.forEvent(
                application.id(),
                APPLICATION_LIMIT_BLOCKED_TOPIC,
                application.id().toString(),
                event,
                objectMapper
        );
    }
}
