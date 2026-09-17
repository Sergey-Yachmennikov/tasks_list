package com.example.tasklist.lending.application.kafka;

public interface KafkaProducer {
    void send(String topic, String key, String payloadJson);
}
