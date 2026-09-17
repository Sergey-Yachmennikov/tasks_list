package com.example.tasklist.lending.application.persistence;

import com.example.tasklist.lending.application.domain.Application;
import com.example.tasklist.lending.application.domain.ApplicationStatus;
import com.example.tasklist.lending.application.repository.ApplicationRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcApplicationRepository implements ApplicationRepository {

    private static final RowMapper<Application> ROW_MAPPER = (rs, rowNum) -> new Application(
            rs.getObject("id", UUID.class),
            rs.getObject("client_id", UUID.class),
            rs.getObject("lender_id", UUID.class),
            ApplicationStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("requested_amount"),
            Optional.ofNullable(rs.getString("lender_block_id"))
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcApplicationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Application> findById(UUID id) {
        String sql = """
                SELECT id, client_id, lender_id, status, requested_amount, lender_block_id
                FROM application
                WHERE id = :id
                """;
        List<Application> results = jdbcTemplate.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return results.stream().findFirst();
    }

    @Override
    public boolean compareAndSetLimitBlocked(UUID id, ApplicationStatus expectedStatus, String lenderBlockId) {
        String sql = """
                UPDATE application
                SET status = :newStatus, lender_block_id = :lenderBlockId
                WHERE id = :id AND status = :expectedStatus
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("newStatus", ApplicationStatus.LIMIT_BLOCKED.name())
                .addValue("lenderBlockId", lenderBlockId)
                .addValue("id", id)
                .addValue("expectedStatus", expectedStatus.name());
        return jdbcTemplate.update(sql, params) == 1;
    }
}
