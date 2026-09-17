CREATE TABLE orders (
    id            UUID PRIMARY KEY,
    client_id     UUID           NOT NULL,
    items         JSONB          NOT NULL,
    total_amount  NUMERIC(19, 2) NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL
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

-- Каждый поллинг фильтрует ровно по этому предикату; держит запрос "найти ожидающую работу"
-- index-only сканом вместо полного скана таблицы по мере накопления истории SENT.
CREATE INDEX idx_outbox_message_pending ON outbox_message (next_attempt_at)
    WHERE status = 'NEW';

-- Используется job'ом ретеншена (удаление строк SENT старше порога).
CREATE INDEX idx_outbox_message_sent_at ON outbox_message (sent_at)
    WHERE status = 'SENT';

-- Используется job'ом возврата зависших claim'ов (поллер упал посреди публикации и не записал результат).
CREATE INDEX idx_outbox_message_publishing ON outbox_message (claimed_at)
    WHERE status = 'PUBLISHING';
