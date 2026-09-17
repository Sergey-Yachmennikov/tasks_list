package com.example.tasklist.lending.application.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * A row in the transactional outbox table. Written in the same DB transaction as the
 * business state change it describes, so the two either both commit or both roll back —
 * that atomicity is the entire point of the pattern. Actual Kafka delivery happens later,
 * out of band, via {@link OutboxMessageProducer}.
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
            // Serialization is fully under our control here (a plain record), so a failure
            // means a programming error, not a transient condition worth retrying.
            throw new IllegalStateException("Failed to serialize outbox event for aggregate " + aggregateId, e);
        }
    }
}
