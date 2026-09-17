package com.example.tasklist.lending.application.outbox;

public enum OutboxStatus {
    NEW,
    /** Claimed by a poller instance and being published; see {@link OutboxMessageProducer}. */
    PUBLISHING,
    SENT,
    /** Retries exhausted; excluded from {@link OutboxRepository#lockBatchForPublishing}. */
    DEAD_LETTERED
}
