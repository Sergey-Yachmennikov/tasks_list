package com.example.tasklist.lending.application.service;

import java.util.UUID;

public interface ApplicationService {

    /**
     * Blocks the requested credit amount with the lender and moves the application
     * from {@code SCORING_APPROVED} to {@code LIMIT_BLOCKED}.
     * Idempotent: calling it again after a successful block is a no-op.
     */
    void blockLenderLimit(UUID applicationId);
}
