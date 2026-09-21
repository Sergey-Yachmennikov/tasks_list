package com.example.tasklist.order.service;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderItemCommand(UUID productId, int quantity, BigDecimal unitPrice) {}
