CREATE TABLE application (
    id                UUID PRIMARY KEY,
    client_id         UUID           NOT NULL,
    lender_id         UUID           NOT NULL,
    status            VARCHAR(32)    NOT NULL,
    requested_amount  NUMERIC(19, 2) NOT NULL,
    lender_block_id   VARCHAR(128)
);

CREATE TABLE outbox_message (
    id               UUID PRIMARY KEY,
    aggregate_id     UUID         NOT NULL,
    topic            VARCHAR(255) NOT NULL,
    message_key      VARCHAR(255) NOT NULL,
    payload          TEXT         NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    retry_count      INT          NOT NULL DEFAULT 0,
    last_error       VARCHAR(2000),
    created_at       TIMESTAMPTZ  NOT NULL,
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    claimed_at       TIMESTAMPTZ,
    sent_at          TIMESTAMPTZ
);

-- Every poll filters on exactly this predicate; keeps the "find pending work" query
-- an index-only scan instead of a table scan as the table fills up with SENT history.
CREATE INDEX idx_outbox_message_pending ON outbox_message (next_attempt_at)
    WHERE status = 'NEW';

-- Backs the retention cleanup job (delete SENT rows older than a cutoff).
CREATE INDEX idx_outbox_message_sent_at ON outbox_message (sent_at)
    WHERE status = 'SENT';

-- Backs the stale-claim reclaim job (a poller crashed mid-publish and never recorded an outcome).
CREATE INDEX idx_outbox_message_publishing ON outbox_message (claimed_at)
    WHERE status = 'PUBLISHING';
