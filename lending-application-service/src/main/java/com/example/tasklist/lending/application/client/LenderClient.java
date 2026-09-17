package com.example.tasklist.lending.application.client;

import com.example.tasklist.lending.application.domain.LenderBlockResult;

import java.math.BigDecimal;
import java.util.UUID;

public interface LenderClient {

    /**
     * @param requestId идемпотентный ключ, который передаёт вызывающий код. Лендер должен
     *                  вернуть тот же {@link LenderBlockResult} на повторные вызовы с тем же
     *                  requestId, а не блокировать сумму повторно.
     */
    LenderBlockResult blockLimit(
            UUID lenderId,
            UUID applicationId,
            BigDecimal amount,
            String requestId
    );
}
