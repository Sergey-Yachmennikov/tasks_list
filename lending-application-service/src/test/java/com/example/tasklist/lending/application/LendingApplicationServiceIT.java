package com.example.tasklist.lending.application;

import com.example.tasklist.lending.application.client.LenderClient;
import com.example.tasklist.lending.application.domain.LenderBlockResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real wiring end to end: Flyway migrations against real Postgres, the JDBC
 * repositories, {@link com.example.tasklist.lending.application.outbox.OutboxMessageProducer}
 * publishing to a real Kafka broker, and the atomicity of {@code compareAndSetLimitBlocked}
 * under genuine concurrent load — none of which a mocked unit test can actually prove.
 * The lender itself is mocked; it's a third party we don't own, not what this test verifies.
 * <p>
 * *IT naming (not *Test) is deliberate: this needs Docker and runs via failsafe/{@code mvn verify},
 * kept out of the fast default {@code mvn test} loop.
 */
@SpringBootTest(classes = LendingApplicationServiceApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class LendingApplicationServiceIT {

    private static final String TOPIC = "application-limit-blocked";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @MockBean
    private LenderClient lenderClient;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUpConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterEach
    void tearDownConsumer() {
        consumer.close();
    }

    private List<ConsumerRecord<String, String>> pollForRecords(Duration totalTimeout) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        Instant deadline = Instant.now().plus(totalTimeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
            records.forEach(collected::add);
        }
        return collected;
    }

    private UUID seedApplication(BigDecimal amount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO application (id, client_id, lender_id, status, requested_amount, lender_block_id)
                VALUES (:id, :clientId, :lenderId, 'SCORING_APPROVED', :amount, NULL)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("clientId", UUID.randomUUID())
                .addValue("lenderId", UUID.randomUUID())
                .addValue("amount", amount));
        return id;
    }

    @Test
    void blocksLimit_persistsState_andPublishesEventToKafka() {
        UUID id = seedApplication(new BigDecimal("1000.00"));
        when(lenderClient.blockLimit(any(), eq(id), any(), anyString()))
                .thenReturn(new LenderBlockResult("block-abc"));

        assertDoesNotThrow(() -> mockMvc.perform(post("/api/v1/applications/{id}/block-limit", id))
                .andExpect(status().isNoContent()));

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM application WHERE id = :id",
                new MapSqlParameterSource("id", id), String.class);
        assertThat(status).isEqualTo("LIMIT_BLOCKED");

        List<ConsumerRecord<String, String>> matching = pollForRecords(Duration.ofSeconds(10)).stream()
                .filter(r -> r.key().equals(id.toString()))
                .toList();
        assertThat(matching).hasSize(1);
        assertThat(matching.get(0).value()).contains("block-abc");
    }

    @Test
    void concurrentCalls_blockExactlyOnce() throws Exception {
        UUID id = seedApplication(new BigDecimal("500.00"));
        when(lenderClient.blockLimit(any(), eq(id), any(), anyString()))
                .thenReturn(new LenderBlockResult("block-race"));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            var futures = IntStream.range(0, 4)
                    .<Future<?>>mapToObj(i -> executor.submit(() -> assertDoesNotThrow(() ->
                            mockMvc.perform(post("/api/v1/applications/{id}/block-limit", id)))))
                    .toList();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM application WHERE id = :id",
                new MapSqlParameterSource("id", id), String.class);
        assertThat(status).isEqualTo("LIMIT_BLOCKED");

        long matching = pollForRecords(Duration.ofSeconds(10)).stream()
                .filter(r -> r.key().equals(id.toString()))
                .count();
        assertThat(matching).isEqualTo(1);
    }
}
