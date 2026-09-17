package com.example.tasklist.kyc.application.client;

import com.example.tasklist.kyc.application.domain.NotificationResult;
import com.example.tasklist.kyc.application.domain.SmsRequest;

public interface NotificationClient {
    NotificationResult sendSms(SmsRequest request);
}
