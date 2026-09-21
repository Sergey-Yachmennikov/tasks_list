package com.example.tasklist.order.service;

import com.example.tasklist.order.domain.Order;
import com.example.tasklist.order.domain.OrderItem;
import com.example.tasklist.order.event.OrderCreatedEvent;
import com.example.tasklist.order.outbox.OutboxMessage;
import com.example.tasklist.order.outbox.OutboxRepository;
import com.example.tasklist.order.repository.OrderRepository;
import com.example.tasklist.order.service.exception.InvalidOrderRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderCreationService implements OrderService {

    private static final String ORDER_CREATED_TOPIC = "order-created";

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderCreationService(
            OrderRepository orderRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Order createOrder(CreateOrderCommand command) {
        List<OrderItem> items = validateAndBuildItems(command);
        BigDecimal totalAmount = items.stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = new Order(UUID.randomUUID(), command.clientId(), items, totalAmount, Instant.now());

        // Единственные две операции здесь — обе локальные DB-записи, без сетевого вызова между
        // ними (в отличие от LenderService/KycService, где внешний вызов намеренно стоит вне
        // транзакции) — поэтому обычного @Transactional достаточно, ручной TransactionTemplate
        // не нужен. Ответ вызывающей стороне уходит сразу после коммита этой транзакции —
        // AnalyticsService в этом пути не участвует вообще.
        orderRepository.save(order);
        outboxRepository.save(OutboxMessage.forEvent(
                order.id(),
                ORDER_CREATED_TOPIC,
                order.id().toString(),
                new OrderCreatedEvent(order.id(), order.clientId(), order.items(), order.totalAmount(), order.createdAt()),
                objectMapper
        ));

        return order;
    }

    private List<OrderItem> validateAndBuildItems(CreateOrderCommand command) {

        if (command.items() == null || command.items().isEmpty()) {
            throw new InvalidOrderRequestException("Order must contain at least one item");
        }

        return command.items().stream()
                .map(item -> {

                    if (item.quantity() <= 0) {
                        throw new InvalidOrderRequestException(
                                "Item quantity must be positive: product %s, quantity %d".formatted(item.productId(), item.quantity()));
                    }

                    if (item.unitPrice() == null || item.unitPrice().signum() < 0) {
                        throw new InvalidOrderRequestException(
                                "Item unit price must not be negative: product %s".formatted(item.productId()));
                    }

                    return new OrderItem(item.productId(), item.quantity(), item.unitPrice());
                })
                .toList();
    }
}
