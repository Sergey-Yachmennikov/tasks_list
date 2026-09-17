package com.example.tasklist.kyc.application.service.exception;

import java.util.UUID;

public class NotificationFailedException extends RuntimeException {
    public NotificationFailedException(UUID applicationId, String reason) {
        super("Failed to notify client for application %s: %s".formatted(applicationId, reason));
    }
}
