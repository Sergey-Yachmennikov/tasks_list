package com.example.tasklist.kyc.application.service;

import com.example.tasklist.kyc.application.client.KycClient;
import com.example.tasklist.kyc.application.client.NotificationClient;
import com.example.tasklist.kyc.application.domain.Application;
import com.example.tasklist.kyc.application.domain.ApplicationStatus;
import com.example.tasklist.kyc.application.domain.KycResult;
import com.example.tasklist.kyc.application.domain.NotificationResult;
import com.example.tasklist.kyc.application.domain.SmsRequest;
import com.example.tasklist.kyc.application.repository.ApplicationRepository;
import com.example.tasklist.kyc.application.service.exception.ApplicationClientMismatchException;
import com.example.tasklist.kyc.application.service.exception.ApplicationNotFoundException;
import com.example.tasklist.kyc.application.service.exception.InvalidApplicationStatusException;
import com.example.tasklist.kyc.application.service.exception.KycNotCompletedException;
import com.example.tasklist.kyc.application.service.exception.NotificationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class KycService implements ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final ApplicationRepository applicationRepository;
    private final KycClient kycClient;
    private final NotificationClient notificationClient;

    public KycService(
            ApplicationRepository applicationRepository,
            KycClient kycClient,
            NotificationClient notificationClient
    ) {
        this.applicationRepository = applicationRepository;
        this.kycClient = kycClient;
        this.notificationClient = notificationClient;
    }

    @Override
    public Application completeKyc(UUID applicationId, UUID clientId) {
        MDC.put("applicationId", applicationId.toString());
        try {
            return doCompleteKyc(applicationId, clientId);
        } finally {
            MDC.remove("applicationId");
        }
    }

    private Application doCompleteKyc(UUID applicationId, UUID clientId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        if (application.status() == ApplicationStatus.KYC_COMPLETED) {
            log.info("Application {} KYC already completed, treating as idempotent retry", applicationId);
            return application;
        }
        if (application.status() != ApplicationStatus.KYC_PENDING) {
            throw new InvalidApplicationStatusException(applicationId, ApplicationStatus.KYC_PENDING, application.status());
        }
        if (!application.clientId().equals(clientId)) {
            throw new ApplicationClientMismatchException(applicationId, clientId);
        }

        KycResult kycResult = kycClient.fetchStatus(application.kycSessionId());
        if (!kycResult.completed()) {
            throw new KycNotCompletedException(applicationId, kycResult.details());
        }

        // Уведомление отправляется до фиксации статуса, а не после (как предполагает
        // наивный порядок "обновить, потом уведомить"): уведомление обязательно, и если
        // отправить его после перевода в KYC_COMPLETED, а оно упадёт, повторный вызов
        // попадёт в идемпотентную ветку выше и никогда больше не попытается его отправить.
        // Отправляя раньше, при провале SMS статус остаётся KYC_PENDING, и ретрай клиента
        // корректно переигрывает оба шага.
        NotificationResult notificationResult = notificationClient.sendSms(
                new SmsRequest(application.clientId().toString(), "Your identity verification is complete."));
        if (notificationResult instanceof NotificationResult.ValidationError error) {
            throw new NotificationFailedException(applicationId, error.reason());
        }

        boolean updated = applicationRepository.compareAndSetKycCompleted(applicationId, ApplicationStatus.KYC_PENDING);
        if (!updated) {
            // Проиграли гонку конкурентному вызову: тот уже перевёл заявку в KYC_COMPLETED.
            // SMS для этого запроса уже ушла (у NotificationClient, в отличие от LenderClient,
            // нет idempotency-key) — редкий, но принятый компромисс: возможен дубль уведомления,
            // но не дубль денежной операции.
            log.info("Application {} was already transitioned by a concurrent call; SMS was still sent for this request",
                    applicationId);
        }

        return new Application(application.id(), application.clientId(), ApplicationStatus.KYC_COMPLETED, application.kycSessionId());
    }
}
