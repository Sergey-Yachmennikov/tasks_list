package com.example.tasklist.lending.application.service.exception;

import com.example.tasklist.lending.application.domain.ApplicationStatus;

import java.util.UUID;

public class InvalidApplicationStatusException extends RuntimeException {
    public InvalidApplicationStatusException(UUID applicationId, ApplicationStatus expected, ApplicationStatus actual) {
        super("Application %s expected status %s but was %s".formatted(applicationId, expected, actual));
    }
}
