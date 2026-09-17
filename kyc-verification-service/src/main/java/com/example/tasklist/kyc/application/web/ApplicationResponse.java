package com.example.tasklist.kyc.application.web;

import com.example.tasklist.kyc.application.domain.Application;
import com.example.tasklist.kyc.application.domain.ApplicationStatus;

import java.util.UUID;

record ApplicationResponse(UUID id, UUID clientId, ApplicationStatus status, String kycSessionId) {

    static ApplicationResponse from(Application application) {
        return new ApplicationResponse(application.id(), application.clientId(), application.status(), application.kycSessionId());
    }
}
