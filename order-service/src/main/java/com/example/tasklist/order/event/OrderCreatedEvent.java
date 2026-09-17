package com.example.tasklist.order.event;

import com.example.tasklist.order.domain.OrderItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload, который вычитывает {@code AnalyticsService} для подсчёта популярности товаров.
 * Публикуется асинхронно через outbox — см. docs/order-created-flow.md про то, почему это
 * не синхронный вызов внутри запроса на создание заказа.
 */
public record OrderCreatedEvent(
        UUID orderId,
        UUID clientId,
        List<OrderItem> items,
        BigDecimal totalAmount,
        Instant occurredAt
) {
}
