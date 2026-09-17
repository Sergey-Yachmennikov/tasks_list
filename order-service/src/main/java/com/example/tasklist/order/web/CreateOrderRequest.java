package com.example.tasklist.order.web;

import java.util.List;
import java.util.UUID;

record CreateOrderRequest(UUID clientId, List<OrderItemRequest> items) {
}
