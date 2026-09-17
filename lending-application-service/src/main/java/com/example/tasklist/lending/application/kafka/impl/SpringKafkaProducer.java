package com.example.tasklist.lending.application.kafka.impl;

import com.example.tasklist.lending.application.kafka.KafkaProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends synchronously (blocks the outbox poller thread up to {@code sendTimeout}) so the
 * caller finds out success/failure immediately and can decide retry vs. dead-letter — the
 * whole reason {@link com.example.tasklist.lending.application.outbox.OutboxMessageProducer}
 * calls this instead of fire-and-forget.
 */
@Component
public class SpringKafkaProducer implements KafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration sendTimeout;

    public SpringKafkaProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${kafka.send-timeout:5s}") Duration sendTimeout
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void send(String topic, String key, String payloadJson) {
        try {
            kafkaTemplate.send(topic, key, payloadJson).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new KafkaPublishException(topic, key, e.getCause() != null ? e.getCause() : e);
        } catch (TimeoutException e) {
            throw new KafkaPublishException(topic, key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException(topic, key, e);
        }
    }
}
