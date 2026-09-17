package com.example.tasklist.lending.application.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка в таблице транзакционного outbox. Записывается в той же транзакции БД, что и
 * бизнес-изменение состояния, которое она описывает, — поэтому оба изменения либо коммитятся
 * вместе, либо вместе откатываются. Это и есть вся суть паттерна. Реальная доставка в Kafka
 * происходит позже, отдельно, через {@link OutboxMessageProducer}.
 */
public record OutboxMessage(
        UUID id,
        UUID aggregateId,
        String topic,
        String messageKey,
        String payload,
        OutboxStatus status,
        int retryCount,
        Instant createdAt,
        Instant nextAttemptAt
) {

    public static OutboxMessage forEvent(UUID aggregateId, String topic, String key, Object event, ObjectMapper mapper) {
        try {
            String payload = mapper.writeValueAsString(event);
            Instant now = Instant.now();
            return new OutboxMessage(UUID.randomUUID(), aggregateId, topic, key, payload, OutboxStatus.NEW, 0, now, now);
        } catch (JsonProcessingException e) {
            // Сериализация здесь полностью под нашим контролем (обычный record), поэтому
            // ошибка означает баг в коде, а не временное состояние, которое стоит ретраить.
            throw new IllegalStateException("Failed to serialize outbox event for aggregate " + aggregateId, e);
        }
    }
}
