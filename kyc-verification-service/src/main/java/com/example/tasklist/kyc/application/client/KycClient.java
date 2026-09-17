package com.example.tasklist.kyc.application.client;

import com.example.tasklist.kyc.application.domain.KycResult;

public interface KycClient {
    KycResult fetchStatus(String kycSessionId);
}
