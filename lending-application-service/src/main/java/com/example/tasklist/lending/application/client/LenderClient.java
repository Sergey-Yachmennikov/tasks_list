package com.example.tasklist.lending.application.client;

import com.example.tasklist.lending.application.domain.LenderBlockResult;

import java.math.BigDecimal;
import java.util.UUID;

public interface LenderClient {

    /**
     * @param requestId caller-supplied idempotency key. The lender must return the same
     *                  {@link LenderBlockResult} for repeated calls with the same requestId
     *                  instead of blocking the amount again.
     */
    LenderBlockResult blockLimit(
            UUID lenderId,
            UUID applicationId,
            BigDecimal amount,
            String requestId
    );
}
