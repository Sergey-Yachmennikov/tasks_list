package com.example.tasklist.order.service;

import com.example.tasklist.order.domain.Order;
import com.example.tasklist.order.outbox.OutboxMessage;
import com.example.tasklist.order.outbox.OutboxRepository;
import com.example.tasklist.order.repository.OrderRepository;
import com.example.tasklist.order.service.exception.InvalidOrderRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderCreationServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OutboxRepository outboxRepository;

    private OrderCreationService orderService;

    private final UUID clientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orderService = new OrderCreationService(orderRepository, outboxRepository, new ObjectMapper().findAndRegisterModules());
    }

    private CreateOrderCommand commandWithItems(CreateOrderItemCommand... items) {
        return new CreateOrderCommand(clientId, List.of(items));
    }

    @Test
    void createsOrder_savesRow_andWritesOutboxEvent() {
        CreateOrderCommand command = commandWithItems(
                new CreateOrderItemCommand(UUID.randomUUID(), 2, new BigDecimal("10.00")),
                new CreateOrderItemCommand(UUID.randomUUID(), 1, new BigDecimal("5.00"))
        );

        Order order = orderService.createOrder(command);

        assertThat(order.clientId()).isEqualTo(clientId);
        assertThat(order.totalAmount()).isEqualByComparingTo("25.00");

        verify(orderRepository).save(order);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        OutboxMessage saved = captor.getValue();
        assertThat(saved.aggregateId()).isEqualTo(order.id());
        assertThat(saved.topic()).isEqualTo("order-created");
        assertThat(saved.messageKey()).isEqualTo(order.id().toString());
        assertThat(saved.payload()).contains("25.00");
    }

    @Test
    void throws_whenItemsAreEmpty() {
        CreateOrderCommand command = new CreateOrderCommand(clientId, List.of());

        assertThatThrownBy(() -> orderService.createOrder(command))
                .isInstanceOf(InvalidOrderRequestException.class);

        verify(orderRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void throws_whenItemsAreNull() {
        CreateOrderCommand command = new CreateOrderCommand(clientId, null);

        assertThatThrownBy(() -> orderService.createOrder(command))
                .isInstanceOf(InvalidOrderRequestException.class);
    }

    @Test
    void throws_whenQuantityIsNotPositive() {
        CreateOrderCommand command = commandWithItems(
                new CreateOrderItemCommand(UUID.randomUUID(), 0, new BigDecimal("10.00")));

        assertThatThrownBy(() -> orderService.createOrder(command))
                .isInstanceOf(InvalidOrderRequestException.class);

        verify(orderRepository, never()).save(any());
    }

    @Test
    void throws_whenUnitPriceIsNegative() {
        CreateOrderCommand command = commandWithItems(
                new CreateOrderItemCommand(UUID.randomUUID(), 1, new BigDecimal("-1.00")));

        assertThatThrownBy(() -> orderService.createOrder(command))
                .isInstanceOf(InvalidOrderRequestException.class);

        verify(orderRepository, never()).save(any());
    }
}
