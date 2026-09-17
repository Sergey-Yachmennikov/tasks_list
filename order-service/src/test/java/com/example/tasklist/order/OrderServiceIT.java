package com.example.tasklist.order;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет реальную сборку целиком: миграции Flyway на настоящем Postgres, JDBC-репозитории и
 * {@link com.example.tasklist.order.outbox.OutboxMessageProducer}, публикующий в настоящий
 * брокер Kafka — то есть весь путь "создать заказ → outbox → order-created топик", который
 * заменил собой синхронный вызов AnalyticsService из ТЗ. AnalyticsService в этом модуле нет —
 * тест проверяет только продюсерскую сторону.
 * <p>
 * Суффикс *IT (а не *Test) выбран намеренно: тесту нужен Docker, он запускается через
 * failsafe/{@code mvn verify} и не входит в быстрый дефолтный цикл {@code mvn test}.
 */
@SpringBootTest(classes = OrderServiceApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class OrderServiceIT {

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

    private static final String TOPIC = "order-created";

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

    private String createOrderRequest(UUID clientId, UUID productId) {
        return """
                {
                  "clientId": "%s",
                  "items": [
                    {"productId": "%s", "quantity": 3, "unitPrice": 15.50}
                  ]
                }
                """.formatted(clientId, productId);
    }

    @Test
    void createsOrder_persistsState_andPublishesEventToKafka() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        assertDoesNotThrow(() -> mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(createOrderRequest(clientId, productId)))
                .andExpect(status().isCreated()));

        BigDecimal totalAmount = jdbcTemplate.queryForObject(
                "SELECT total_amount FROM orders WHERE client_id = :clientId",
                new MapSqlParameterSource("clientId", clientId), BigDecimal.class);
        assertThat(totalAmount).isEqualByComparingTo("46.50");

        List<ConsumerRecord<String, String>> matching = pollForRecords(Duration.ofSeconds(10)).stream()
                .filter(r -> r.value().contains(clientId.toString()))
                .toList();
        assertThat(matching).hasSize(1);
        assertThat(matching.get(0).value()).contains(productId.toString());
    }
}
