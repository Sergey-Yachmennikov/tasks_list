package com.example.tasklist.lending.application.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public record Application(
        UUID id,
        UUID clientId,
        UUID lenderId,
        ApplicationStatus status,
        BigDecimal requestedAmount,
        Optional<String> lenderBlockId
        // ещё поля...
) {
}
