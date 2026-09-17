package com.example.tasklist.order.web;

import com.example.tasklist.order.domain.Order;
import com.example.tasklist.order.service.CreateOrderCommand;
import com.example.tasklist.order.service.CreateOrderItemCommand;
import com.example.tasklist.order.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderRestController implements OrderController {

    private final OrderService orderService;

    public OrderRestController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public ResponseEntity<OrderResponse> createOrder(CreateOrderRequest request) {
        CreateOrderCommand command = new CreateOrderCommand(
                request.clientId(),
                request.items() == null
                        ? null
                        : request.items().stream()
                                .map(item -> new CreateOrderItemCommand(item.productId(), item.quantity(), item.unitPrice()))
                                .toList()
        );
        Order order = orderService.createOrder(command);
        return ResponseEntity.status(201).body(OrderResponse.from(order));
    }
}
