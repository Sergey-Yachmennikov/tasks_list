package com.example.tasklist.kyc.application.service.exception;

import com.example.tasklist.kyc.application.domain.ApplicationStatus;

import java.util.UUID;

public class InvalidApplicationStatusException extends RuntimeException {
    public InvalidApplicationStatusException(UUID applicationId, ApplicationStatus expected, ApplicationStatus actual) {
        super("Application %s expected status %s but was %s".formatted(applicationId, expected, actual));
    }
}
