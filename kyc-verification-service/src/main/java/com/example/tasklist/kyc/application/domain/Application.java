package com.example.tasklist.kyc.application.domain;

import java.util.UUID;

public record Application(
        UUID id,
        UUID clientId,
        ApplicationStatus status,
        String kycSessionId
) {
}
