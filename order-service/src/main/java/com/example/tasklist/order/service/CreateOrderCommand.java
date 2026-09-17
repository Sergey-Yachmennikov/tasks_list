package com.example.tasklist.order.service;

import java.util.List;
import java.util.UUID;

public record CreateOrderCommand(UUID clientId, List<CreateOrderItemCommand> items) {
}
