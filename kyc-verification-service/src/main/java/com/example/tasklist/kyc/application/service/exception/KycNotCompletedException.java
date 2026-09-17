package com.example.tasklist.kyc.application.service.exception;

import java.util.UUID;

public class KycNotCompletedException extends RuntimeException {
    public KycNotCompletedException(UUID applicationId, String details) {
        super("KYC not completed yet for application %s: %s".formatted(applicationId, details));
    }
}
