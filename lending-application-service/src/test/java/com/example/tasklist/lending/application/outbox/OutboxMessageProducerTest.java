package com.example.tasklist.lending.application.outbox;

import com.example.tasklist.lending.application.config.OutboxProperties;
import com.example.tasklist.lending.application.kafka.KafkaProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxMessageProducerTest {

    private static final OutboxProperties PROPERTIES = new OutboxProperties(
            100, Duration.ofMillis(500), 5,
            Duration.ofSeconds(1), Duration.ofMinutes(5),
            Duration.ofDays(7), Duration.ofHours(1),
            Duration.ofMinutes(2), Duration.ofMinutes(1)
    );

    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private KafkaProducer kafkaProducer;

    private OutboxMessageProducer producer;

    @BeforeEach
    void setUp() {
        producer = new OutboxMessageProducer(outboxRepository, kafkaProducer, PROPERTIES, new SimpleMeterRegistry());
    }

    private OutboxMessage messageWithRetryCount(int retryCount) {
        Instant now = Instant.now();
        return new OutboxMessage(UUID.randomUUID(), UUID.randomUUID(), "application-limit-blocked",
                "key", "{}", OutboxStatus.PUBLISHING, retryCount, now, now);
    }

    @Test
    void marksSent_whenPublishSucceeds() {
        OutboxMessage message = messageWithRetryCount(0);
        when(outboxRepository.lockBatchForPublishing(anyInt())).thenReturn(List.of(message));

        producer.publishPending();

        verify(outboxRepository).markSent(message.id());
        verify(outboxRepository, never()).recordFailure(any(), anyString(), any());
        verify(outboxRepository, never()).markDeadLettered(any(), anyString());
    }

    @Test
    void recordsFailureWithBackoff_andStaysEligibleForRetry_whenBelowMaxRetries() {
        OutboxMessage message = messageWithRetryCount(0);
        when(outboxRepository.lockBatchForPublishing(anyInt())).thenReturn(List.of(message));
        doThrow(new RuntimeException("broker unavailable"))
                .when(kafkaProducer).send(any(), any(), any());

        Instant before = Instant.now();
        producer.publishPending();

        var nextAttemptCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).recordFailure(eq(message.id()), anyString(), nextAttemptCaptor.capture());
        verify(outboxRepository, never()).markDeadLettered(any(), anyString());
        verify(outboxRepository, never()).markSent(any());

        // попытка 1 -> initialBackoff (1с)
        assertThat(nextAttemptCaptor.getValue()).isAfterOrEqualTo(before.plusSeconds(1));
        assertThat(nextAttemptCaptor.getValue()).isBefore(before.plusSeconds(2));
    }

    @Test
    void deadLetters_whenRetriesAreExhausted() {
        OutboxMessage message = messageWithRetryCount(4); // попытка 5 == maxRetries
        when(outboxRepository.lockBatchForPublishing(anyInt())).thenReturn(List.of(message));
        doThrow(new RuntimeException("broker unavailable"))
                .when(kafkaProducer).send(any(), any(), any());

        producer.publishPending();

        verify(outboxRepository).markDeadLettered(eq(message.id()), anyString());
        verify(outboxRepository, never()).recordFailure(any(), anyString(), any());
    }

    @Test
    void cleanupSent_deletesRowsOlderThanRetention() {
        when(outboxRepository.deleteSentOlderThan(any())).thenReturn(3);

        producer.cleanupSent();

        verify(outboxRepository).deleteSentOlderThan(any());
    }

    @Test
    void reclaimStaleClaims_reclaimsRowsStuckPastTimeout() {
        when(outboxRepository.reclaimStalePublishing(any())).thenReturn(2);

        producer.reclaimStaleClaims();

        verify(outboxRepository).reclaimStalePublishing(any());
    }
}
