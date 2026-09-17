package com.example.tasklist.kyc.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("notification")
public record NotificationProperties(
        @DefaultValue("http://localhost:8083") String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout
) {
}
