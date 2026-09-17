package com.example.tasklist.lending.application.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    void save(OutboxMessage message);

    /**
     * Atomically claims up to {@code limit} due messages (status {@code NEW}, {@code nextAttemptAt}
     * in the past) by moving them to {@code PUBLISHING} and returns them, ordered by
     * {@code nextAttemptAt}. Implementations should do the claim as a single
     * {@code UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING ...} statement,
     * so multiple instances can poll the same table concurrently without double-claiming, and so
     * the row lock is held only for that one fast statement — never across the network call to
     * Kafka that happens afterwards.
     */
    List<OutboxMessage> lockBatchForPublishing(int limit);

    void markSent(UUID messageId);

    /**
     * Records a failed publish attempt, moving the row back to {@code NEW} (incrementing its
     * retry count and pushing {@code nextAttemptAt} out by the caller-computed backoff) so it's
     * picked up again on a future {@link #lockBatchForPublishing} call once due.
     */
    void recordFailure(UUID messageId, String errorMessage, Instant nextAttemptAt);

    /**
     * Moves the row to a terminal {@link OutboxStatus#DEAD_LETTERED} state once retries are
     * exhausted, taking it out of the publishing loop for good. A separate dead-letter
     * table/topic and alert should consume rows in this state; wiring that up is out of
     * scope for this module.
     */
    void markDeadLettered(UUID messageId, String errorMessage);

    /**
     * Returns rows stuck in {@code PUBLISHING} for longer than {@code staleAfter} to {@code NEW}
     * (bumping retry count). Covers a poller instance crashing or being killed between claiming
     * a batch and recording its outcome — without this, those rows would never be retried.
     * Consumers already have to tolerate duplicate delivery, so a message that was actually
     * published right before the crash is at worst redelivered once.
     *
     * @return number of rows reclaimed
     */
    int reclaimStalePublishing(Instant claimedBefore);

    /** Number of rows still waiting to be published; backs an outbox-backlog gauge. */
    long countPending();

    /** Deletes SENT rows older than {@code cutoff}. Backs the retention cleanup job. */
    int deleteSentOlderThan(Instant cutoff);
}
