package com.example.tasklist.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Order(
        UUID id,
        UUID clientId,
        List<OrderItem> items,
        BigDecimal totalAmount,
        Instant createdAt
) {
}
