package com.example.tasklist.lending.application.client.http;

import java.math.BigDecimal;
import java.util.UUID;

record BlockLimitRequest(UUID applicationId, BigDecimal amount) {
}
