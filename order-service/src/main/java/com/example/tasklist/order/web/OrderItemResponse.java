package com.example.tasklist.order.web;

import com.example.tasklist.order.domain.OrderItem;

import java.math.BigDecimal;
import java.util.UUID;

record OrderItemResponse(UUID productId, int quantity, BigDecimal unitPrice) {
    static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.productId(), item.quantity(), item.unitPrice());
    }
}
