package com.example.tasklist.lending.application.outbox;

import com.example.tasklist.lending.application.config.OutboxProperties;
import com.example.tasklist.lending.application.kafka.KafkaProducer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Relays outbox rows to Kafka. Runs independently of the transaction that wrote them,
 * which is what decouples "commit the state change" from "publish the event" and gives
 * us at-least-once delivery instead of the dual-write problem (DB commit succeeds, Kafka
 * publish fails, or vice versa).
 * <p>
 * Consumers of {@code application-limit-blocked} must therefore be idempotent (dedupe on
 * applicationId), since a crash between {@link KafkaProducer#send} and {@link #markSent}
 * can cause the same message to be delivered twice.
 */
@Component
public class OutboxMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(OutboxMessageProducer.class);

    private final OutboxRepository outboxRepository;
    private final KafkaProducer kafkaProducer;
    private final OutboxProperties properties;
    private final MeterRegistry meterRegistry;

    public OutboxMessageProducer(
            OutboxRepository outboxRepository,
            KafkaProducer kafkaProducer,
            OutboxProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaProducer = kafkaProducer;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        Gauge.builder("outbox.backlog", outboxRepository, OutboxRepository::countPending)
                .description("Outbox rows still waiting to be published")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval:PT0.5S}")
    public void publishPending() {
        List<OutboxMessage> batch = outboxRepository.lockBatchForPublishing(properties.batchSize());
        for (OutboxMessage message : batch) {
            publish(message);
        }
    }

    /** Deletes delivered rows older than the configured retention window, keeping the table bounded. */
    @Scheduled(fixedDelayString = "${outbox.cleanup-interval:PT1H}")
    public void cleanupSent() {
        Instant cutoff = Instant.now().minus(properties.sentRetention());
        int deleted = outboxRepository.deleteSentOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Purged {} sent outbox rows older than {}", deleted, cutoff);
        }
    }

    /**
     * Returns rows abandoned mid-publish (this or another instance claimed them, then crashed
     * or was killed before recording an outcome) back to the queue. Without this, a crash at
     * exactly the wrong moment would strand a row in PUBLISHING forever.
     */
    @Scheduled(fixedDelayString = "${outbox.reclaim-interval:PT1M}")
    public void reclaimStaleClaims() {
        Instant claimedBefore = Instant.now().minus(properties.staleClaimTimeout());
        int reclaimed = outboxRepository.reclaimStalePublishing(claimedBefore);
        if (reclaimed > 0) {
            log.warn("Reclaimed {} outbox rows stuck in PUBLISHING since before {} (worker crash?)",
                    reclaimed, claimedBefore);
            meterRegistry.counter("outbox.reclaimed").increment(reclaimed);
        }
    }

    private void publish(OutboxMessage message) {
        MDC.put("outboxMessageId", message.id().toString());
        MDC.put("applicationId", message.aggregateId().toString());
        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                kafkaProducer.send(message.topic(), message.messageKey(), message.payload());
                outboxRepository.markSent(message.id());
                sample.stop(publishTimer(message.topic(), "success"));
                counter(message.topic(), "sent").increment();
            } catch (Exception ex) {
                sample.stop(publishTimer(message.topic(), "failure"));
                handleFailure(message, ex);
            }
        } finally {
            MDC.remove("outboxMessageId");
            MDC.remove("applicationId");
        }
    }

    private void handleFailure(OutboxMessage message, Exception ex) {
        int attempt = message.retryCount() + 1;
        log.warn("Failed to publish outbox message {} (attempt {}): {}", message.id(), attempt, ex.getMessage());

        if (attempt >= properties.maxRetries()) {
            // Out of retries: move it out of the publishing loop for good rather than
            // spinning on it forever. A dead-letter table/topic + alert should pick rows
            // in this state up; wiring that up is out of scope for this module.
            outboxRepository.markDeadLettered(message.id(), ex.getMessage());
            counter(message.topic(), "dead_lettered").increment();
            log.error("Outbox message {} exceeded max retries ({}); dead-lettered, needs manual attention",
                    message.id(), properties.maxRetries());
        } else {
            Instant nextAttempt = computeNextAttempt(attempt);
            outboxRepository.recordFailure(message.id(), ex.getMessage(), nextAttempt);
            counter(message.topic(), "retry_scheduled").increment();
        }
    }

    /** Exponential backoff (initialBackoff * 2^(attempt-1)), capped at maxBackoff. */
    private Instant computeNextAttempt(int attempt) {
        long shift = Math.min(attempt - 1, 20); // guards against overflow on pathological configs
        long backoffMillis = properties.initialBackoff().toMillis() * (1L << shift);
        long cappedMillis = Math.min(backoffMillis, properties.maxBackoff().toMillis());
        return Instant.now().plusMillis(cappedMillis);
    }

    private Counter counter(String topic, String result) {
        return Counter.builder("outbox.publish")
                .tag("topic", topic)
                .tag("result", result)
                .register(meterRegistry);
    }

    private Timer publishTimer(String topic, String outcome) {
        return Timer.builder("outbox.publish.latency")
                .tag("topic", topic)
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
