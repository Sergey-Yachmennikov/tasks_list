package com.example.tasklist.order.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Orders", description = "Order creation")
@RequestMapping("/api/v1/orders")
public interface OrderController {

    @Operation(
            operationId = "createOrder",
            summary = "Create an order",
            description = """
                    Persists the order and responds immediately. Analytics tracking (product \
                    popularity counting) is published asynchronously via the transactional \
                    outbox — the response never waits on AnalyticsService, so a slow or \
                    timing-out analytics pipeline can no longer cause an order (and the \
                    associated payment) to be lost."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "Order request is invalid (no items, non-positive quantity, negative price)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request);
}
