package com.example.tasklist.order.repository;

import com.example.tasklist.order.domain.Order;

public interface OrderRepository {

    void save(Order order);
}
