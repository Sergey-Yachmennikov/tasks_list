package com.example.tasklist.lending.application.service.exception;

import java.util.UUID;

public class ApplicationNotFoundException extends RuntimeException {
    public ApplicationNotFoundException(UUID applicationId) {
        super("Application not found: " + applicationId);
    }
}
