package com.example.tasklist.order.web;

import com.example.tasklist.order.domain.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record OrderResponse(UUID id, UUID clientId, List<OrderItemResponse> items, BigDecimal totalAmount, Instant createdAt) {
    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.id(),
                order.clientId(),
                order.items().stream().map(OrderItemResponse::from).toList(),
                order.totalAmount(),
                order.createdAt()
        );
    }
}
