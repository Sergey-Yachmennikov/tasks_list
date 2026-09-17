package com.example.tasklist.kyc.application.repository;

import com.example.tasklist.kyc.application.domain.Application;
import com.example.tasklist.kyc.application.domain.ApplicationStatus;

import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository {

    Optional<Application> findById(UUID id);

    /**
     * Переводит заявку в {@code KYC_COMPLETED}, но только если её статус на момент
     * обновления всё ещё равен {@code expectedStatus}. Реализация должна делать это одним
     * условным выражением (например,
     * {@code UPDATE application SET status = 'KYC_COMPLETED' WHERE id = ? AND status = ?}),
     * чтобы один и тот же вызов одновременно выполнял переход и защищал от конкурентного
     * перехода этой же заявки — без отдельной блокировки или поля версии.
     *
     * @return true, если обновилась ровно одна строка; false, если заявка уже перешла
     *         дальше {@code expectedStatus} (например, конкурентный вызов уже завершил KYC)
     */
    boolean compareAndSetKycCompleted(UUID id, ApplicationStatus expectedStatus);
}
