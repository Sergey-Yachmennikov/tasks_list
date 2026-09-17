package com.example.tasklist.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("outbox")
public record OutboxProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("5") int maxRetries,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("7d") Duration sentRetention,
        @DefaultValue("1h") Duration cleanupInterval,
        @DefaultValue("2m") Duration staleClaimTimeout,
        @DefaultValue("1m") Duration reclaimInterval
) {

    public OutboxProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("outbox.batch-size must be positive");
        }
        if (maxRetries <= 0) {
            throw new IllegalArgumentException("outbox.max-retries must be positive");
        }
    }
}
