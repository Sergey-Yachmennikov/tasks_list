package com.example.tasklist.order.kafka.impl;

import com.example.tasklist.order.kafka.KafkaProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Отправляет синхронно (блокирует поток outbox-поллера на время до {@code sendTimeout}),
 * чтобы вызывающий код сразу узнал об успехе/неудаче и мог решить — ретраить или отправлять
 * в dead-letter. Именно ради этого {@link com.example.tasklist.order.outbox.OutboxMessageProducer}
 * вызывает этот метод, а не полагается на fire-and-forget.
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
