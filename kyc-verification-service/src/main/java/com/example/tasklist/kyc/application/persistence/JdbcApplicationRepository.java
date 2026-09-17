package com.example.tasklist.kyc.application.persistence;

import com.example.tasklist.kyc.application.domain.Application;
import com.example.tasklist.kyc.application.domain.ApplicationStatus;
import com.example.tasklist.kyc.application.repository.ApplicationRepository;
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
            ApplicationStatus.valueOf(rs.getString("status")),
            rs.getString("kyc_session_id")
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcApplicationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Application> findById(UUID id) {

        String sql = """
                SELECT id, client_id, status, kyc_session_id
                FROM application
                WHERE id = :id
                """;

        List<Application> results = jdbcTemplate.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);

        return results.stream().findFirst();
    }

    @Override
    public boolean compareAndSetKycCompleted(UUID id, ApplicationStatus expectedStatus) {

        String sql = """
                UPDATE application
                SET status = :newStatus
                WHERE id = :id AND status = :expectedStatus
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("newStatus", ApplicationStatus.KYC_COMPLETED.name())
                .addValue("id", id)
                .addValue("expectedStatus", expectedStatus.name());

        return jdbcTemplate.update(sql, params) == 1;
    }
}
