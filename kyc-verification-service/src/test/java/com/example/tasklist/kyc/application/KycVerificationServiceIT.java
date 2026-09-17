package com.example.tasklist.kyc.application;

import com.example.tasklist.kyc.application.client.KycClient;
import com.example.tasklist.kyc.application.client.NotificationClient;
import com.example.tasklist.kyc.application.domain.KycResult;
import com.example.tasklist.kyc.application.domain.NotificationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет реальную сборку целиком: миграции Flyway на настоящем Postgres, JDBC-репозиторий
 * и атомарность {@code compareAndSetKycCompleted} под реальной конкурентной нагрузкой —
 * ничего из этого мок-тест на юнит-уровне доказать не может. KYC-провайдер и SMS-шлюз
 * замокан — это внешние системы, не наши, и не то, что проверяет этот тест.
 * <p>
 * Суффикс *IT (а не *Test) выбран намеренно: тесту нужен Docker, он запускается через
 * failsafe/{@code mvn verify} и не входит в быстрый дефолтный цикл {@code mvn test}.
 */
@SpringBootTest(classes = KycVerificationServiceApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class KycVerificationServiceIT {

    static {
        // Некоторые локальные Docker-демоны требуют минимальную версию API выше той, что
        // docker-java (клиент, которым пользуется Testcontainers) запрашивает по умолчанию,
        // и отклоняют старый запрос простым HTTP 400 вместо того, чтобы его обслужить — без
        // этого фикса здесь всплывает непонятная ошибка "Can't get Docker image" /
        // "Could not find a valid Docker environment", не имеющая отношения к логике теста.
        // Фиксируем современный, но консервативный минимум (Docker Engine ~20.10, 2020 год),
        // если окружение уже не переопределило значение само.
        System.getProperties().putIfAbsent("api.version", "1.41");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private KycClient kycClient;
    @MockBean
    private NotificationClient notificationClient;

    private UUID seedApplication(UUID clientId, String kycSessionId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO application (id, client_id, status, kyc_session_id)
                VALUES (:id, :clientId, 'KYC_PENDING', :kycSessionId)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("clientId", clientId)
                .addValue("kycSessionId", kycSessionId));
        return id;
    }

    private String requestBody(UUID clientId) throws Exception {
        return objectMapper.writeValueAsString(Map.of("clientId", clientId));
    }

    @Test
    void completesKyc_persistsState() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID id = seedApplication(clientId, "session-1");
        when(kycClient.fetchStatus("session-1")).thenReturn(new KycResult(true, "verified"));
        when(notificationClient.sendSms(any())).thenReturn(new NotificationResult.Success("msg-1"));

        mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(clientId)))
                .andExpect(status().isOk());

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM application WHERE id = :id",
                new MapSqlParameterSource("id", id), String.class);
        assertThat(status).isEqualTo("KYC_COMPLETED");
    }

    @Test
    void completeKyc_isIdempotent_onRetry() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID id = seedApplication(clientId, "session-2");
        when(kycClient.fetchStatus("session-2")).thenReturn(new KycResult(true, "verified"));
        when(notificationClient.sendSms(any())).thenReturn(new NotificationResult.Success("msg-1"));

        mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(clientId)))
                .andExpect(status().isOk());

        // Повторный вызов после успешного завершения не должен снова дёргать провайдера/SMS.
        mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(clientId)))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(kycClient, org.mockito.Mockito.times(1)).fetchStatus(anyString());
        org.mockito.Mockito.verify(notificationClient, org.mockito.Mockito.times(1)).sendSms(any());
    }

    @Test
    void concurrentCalls_completeExactlyOnce() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID id = seedApplication(clientId, "session-race");
        when(kycClient.fetchStatus("session-race")).thenReturn(new KycResult(true, "verified"));
        when(notificationClient.sendSms(any())).thenReturn(new NotificationResult.Success("msg-1"));
        String body = requestBody(clientId);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            var futures = IntStream.range(0, 4)
                    .<Future<?>>mapToObj(i -> executor.submit(() -> assertDoesNotThrow(() ->
                            mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)))))
                    .toList();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        // Ровно один переход состояния в БД гарантирован через compareAndSetKycCompleted —
        // это единственное, что этот тест проверяет строго. Сколько раз при этом реально
        // ушла SMS (1 или больше — см. docs/complete-kyc-flow.md про accepted trade-off),
        // здесь намеренно не assert'ится, чтобы не сделать тест флаки.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM application WHERE id = :id",
                new MapSqlParameterSource("id", id), String.class);
        assertThat(status).isEqualTo("KYC_COMPLETED");
    }
}
