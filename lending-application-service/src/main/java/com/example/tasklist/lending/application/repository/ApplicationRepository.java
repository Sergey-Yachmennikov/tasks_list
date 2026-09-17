package com.example.tasklist.lending.application.repository;

import com.example.tasklist.lending.application.domain.Application;
import com.example.tasklist.lending.application.domain.ApplicationStatus;

import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository {

    Optional<Application> findById(UUID id);

    /**
     * Transitions the application to {@code LIMIT_BLOCKED} and records the lender block id,
     * but only if its status still equals {@code expectedStatus} at the time of the update.
     * Implementations should do this as a single conditional statement
     * (e.g. {@code UPDATE application SET status = 'LIMIT_BLOCKED', lender_block_id = ? WHERE id = ? AND status = ?}),
     * so the same call both performs the transition and guards against a concurrent transition
     * of the same application, without needing a separate lock or version field.
     *
     * @return true if exactly one row was updated; false if the application had already moved
     *         past {@code expectedStatus} (e.g. a concurrent call already blocked the limit)
     */
    boolean compareAndSetLimitBlocked(UUID id, ApplicationStatus expectedStatus, String lenderBlockId);
}
