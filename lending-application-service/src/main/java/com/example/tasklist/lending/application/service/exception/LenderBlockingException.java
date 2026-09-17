package com.example.tasklist.lending.application.service.exception;

import java.util.UUID;

public class LenderBlockingException extends RuntimeException {
    public LenderBlockingException(UUID applicationId, Throwable cause) {
        super("Failed to block lender limit for application " + applicationId, cause);
    }
}
