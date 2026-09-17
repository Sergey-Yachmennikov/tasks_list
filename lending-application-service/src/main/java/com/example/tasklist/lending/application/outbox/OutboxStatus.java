package com.example.tasklist.lending.application.outbox;

public enum OutboxStatus {
    NEW,
    /** Захвачено инстансом поллера и находится в процессе публикации; см. {@link OutboxMessageProducer}. */
    PUBLISHING,
    SENT,
    /** Попытки исчерпаны; исключается из {@link OutboxRepository#lockBatchForPublishing}. */
    DEAD_LETTERED
}
