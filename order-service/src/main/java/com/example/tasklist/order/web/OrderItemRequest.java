package com.example.tasklist.order.web;

import java.math.BigDecimal;
import java.util.UUID;

record OrderItemRequest(UUID productId, int quantity, BigDecimal unitPrice) {
}
