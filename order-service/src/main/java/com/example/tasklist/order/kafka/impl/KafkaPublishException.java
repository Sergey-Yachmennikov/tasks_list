package com.example.tasklist.order.kafka.impl;

public class KafkaPublishException extends RuntimeException {
    public KafkaPublishException(String topic, String key, Throwable cause) {
        super("Failed to publish to topic %s (key=%s)".formatted(topic, key), cause);
    }
}
