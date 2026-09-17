package com.example.tasklist.lending.application.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApplicationLimitBlockedEvent(
        UUID applicationId,
        UUID clientId,
        UUID lenderId,
        String lenderBlockId,
        BigDecimal amount,
        Instant occurredAt
) {
}
