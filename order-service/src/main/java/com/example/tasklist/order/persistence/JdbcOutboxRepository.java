package com.example.tasklist.order.persistence;

import com.example.tasklist.order.outbox.OutboxMessage;
import com.example.tasklist.order.outbox.OutboxRepository;
import com.example.tasklist.order.outbox.OutboxStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private static final int MAX_ERROR_LENGTH = 2000;

    private static final RowMapper<OutboxMessage> ROW_MAPPER = (rs, rowNum) -> new OutboxMessage(
            rs.getObject("id", UUID.class),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("topic"),
            rs.getString("message_key"),
            rs.getString("payload"),
            OutboxStatus.valueOf(rs.getString("status")),
            rs.getInt("retry_count"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("next_attempt_at").toInstant()
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcOutboxRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(OutboxMessage message) {
        String sql = """
                INSERT INTO outbox_message
                    (id, aggregate_id, topic, message_key, payload, status, retry_count, created_at, next_attempt_at)
                VALUES
                    (:id, :aggregateId, :topic, :messageKey, :payload, :status, :retryCount, :createdAt, :nextAttemptAt)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", message.id())
                .addValue("aggregateId", message.aggregateId())
                .addValue("topic", message.topic())
                .addValue("messageKey", message.messageKey())
                .addValue("payload", message.payload())
                .addValue("status", message.status().name())
                .addValue("retryCount", message.retryCount())
                .addValue("createdAt", Timestamp.from(message.createdAt()))
                .addValue("nextAttemptAt", Timestamp.from(message.nextAttemptAt()));
        jdbcTemplate.update(sql, params);
    }

    @Override
    public List<OutboxMessage> lockBatchForPublishing(int limit) {
        // Захват через UPDATE ... RETURNING: блокировка FOR UPDATE SKIP LOCKED удерживается
        // только на это одно быстрое выражение, а не на весь последующий вызов Kafka.
        String sql = """
                UPDATE outbox_message
                SET status = 'PUBLISHING', claimed_at = :now
                WHERE id IN (
                    SELECT id FROM outbox_message
                    WHERE status = 'NEW' AND next_attempt_at <= :now
                    ORDER BY next_attempt_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, aggregate_id, topic, message_key, payload, status, retry_count, created_at, next_attempt_at
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("limit", limit);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    @Override
    public void markSent(UUID messageId) {
        String sql = "UPDATE outbox_message SET status = 'SENT', sent_at = :now WHERE id = :id";
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("id", messageId));
    }

    @Override
    public void recordFailure(UUID messageId, String errorMessage, Instant nextAttemptAt) {
        String sql = """
                UPDATE outbox_message
                SET status = 'NEW', retry_count = retry_count + 1, last_error = :error, next_attempt_at = :nextAttemptAt
                WHERE id = :id
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("error", truncate(errorMessage))
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .addValue("id", messageId));
    }

    @Override
    public void markDeadLettered(UUID messageId, String errorMessage) {
        String sql = "UPDATE outbox_message SET status = 'DEAD_LETTERED', last_error = :error WHERE id = :id";
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("error", truncate(errorMessage))
                .addValue("id", messageId));
    }

    @Override
    public int reclaimStalePublishing(Instant claimedBefore) {
        String sql = """
                UPDATE outbox_message
                SET status = 'NEW', retry_count = retry_count + 1,
                    last_error = 'stale claim reclaimed (worker likely crashed mid-publish)',
                    next_attempt_at = :now
                WHERE status = 'PUBLISHING' AND claimed_at < :claimedBefore
                """;
        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("claimedBefore", Timestamp.from(claimedBefore)));
    }

    @Override
    public long countPending() {
        String sql = "SELECT COUNT(*) FROM outbox_message WHERE status = 'NEW'";
        Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public int deleteSentOlderThan(Instant cutoff) {
        String sql = "DELETE FROM outbox_message WHERE status = 'SENT' AND sent_at < :cutoff";
        return jdbcTemplate.update(sql, new MapSqlParameterSource().addValue("cutoff", Timestamp.from(cutoff)));
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
