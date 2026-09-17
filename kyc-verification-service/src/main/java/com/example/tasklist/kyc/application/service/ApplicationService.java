package com.example.tasklist.kyc.application.service;

import com.example.tasklist.kyc.application.domain.Application;

import java.util.UUID;

public interface ApplicationService {

    /**
     * Подтверждает KYC у внешнего провайдера, уведомляет клиента по SMS и переводит заявку
     * из {@code KYC_PENDING} в {@code KYC_COMPLETED}.
     * Идемпотентен: повторный вызов после успешного завершения — no-op.
     */
    Application completeKyc(UUID applicationId, UUID clientId);
}
