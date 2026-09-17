package com.example.tasklist.order.kafka;

public interface KafkaProducer {
    void send(String topic, String key, String payloadJson);
}
