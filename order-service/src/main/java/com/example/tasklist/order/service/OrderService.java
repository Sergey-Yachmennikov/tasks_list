package com.example.tasklist.order.service;

import com.example.tasklist.order.domain.Order;

public interface OrderService {

    /**
     * Создаёт заказ и синхронно отвечает вызывающей стороне сразу после того, как заказ
     * надёжно сохранён — без синхронного обращения к AnalyticsService. Данные для аналитики
     * (подсчёт популярности товаров) публикуются асинхронно через outbox; см.
     * docs/order-created-flow.md о том, какую проблему это решает.
     */
    Order createOrder(CreateOrderCommand command);
}
