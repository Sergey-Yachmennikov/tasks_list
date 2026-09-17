package com.example.tasklist.lending.application.service;

import java.util.UUID;

public interface ApplicationService {

    /**
     * Блокирует запрошенную сумму кредита у лендера и переводит заявку
     * из {@code SCORING_APPROVED} в {@code LIMIT_BLOCKED}.
     * Идемпотентен: повторный вызов после успешной блокировки — no-op.
     */
    void blockLenderLimit(UUID applicationId);
}
