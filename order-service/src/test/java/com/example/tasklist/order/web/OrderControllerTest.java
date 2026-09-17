package com.example.tasklist.order.web;

import com.example.tasklist.order.OrderServiceApplication;
import com.example.tasklist.order.outbox.OutboxRepository;
import com.example.tasklist.order.repository.OrderRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Исключаем DataSource/Flyway через стандартное свойство `spring.autoconfigure.exclude`,
// а не через второй тестовый класс @SpringBootApplication: второй bootstrap-класс в том же
// базовом пакете попадёт в component scan этого приложения, а Spring Boot *объединяет*
// exclude-списки всех найденных @EnableAutoConfiguration в один общий набор — поэтому
// exclude из тестового bootstrap-класса тихо отключил бы DataSource/Flyway и в настоящем приложении.
//
// В отличие от ApplicationControllerTest в lending-application-service, здесь не нужен
// TestConfiguration с ручным PlatformTransactionManager: OrderCreationService использует
// декларативный @Transactional, а не конструкторски инжектируемый TransactionTemplate —
// без бина PlatformTransactionManager транзакционная advice для этого теста просто не
// применяется (моки не чувствительны к границам транзакции), контекст поднимается нормально.
@SpringBootTest(
        classes = OrderServiceApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
)
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderRepository orderRepository;
    @MockBean
    private OutboxRepository outboxRepository;

    private String validOrderRequest(UUID clientId) {
        return """
                {
                  "clientId": "%s",
                  "items": [
                    {"productId": "%s", "quantity": 2, "unitPrice": 10.00}
                  ]
                }
                """.formatted(clientId, UUID.randomUUID());
    }

    @Test
    void createOrder_returns201_onSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOrderRequest(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(20.00));
    }

    @Test
    void createOrder_returns400_whenItemsAreEmpty() throws Exception {
        String body = """
                {
                  "clientId": "%s",
                  "items": []
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void openApiSpec_documentsTheCreateOrderEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['400']").exists());
    }

    /**
     * Снимает снапшот живой спеки в docs/openapi.yaml для потребителей, которым нужен
     * статический файл (кодогенерация клиента, ревью контракта) без запуска сервиса.
     * Исключён из обычной сборки (см. surefire excludedGroups в pom модуля), поскольку запись
     * в исходное дерево — не то, что должен делать обычный прогон тестов; перезапускать вручную
     * после изменения API:
     * <pre>
     *   mvn -pl order-service test \
     *       -Dtest=OrderControllerTest#writeOpenApiSpecSnapshot \
     *       -DfailIfNoTests=false -Dsurefire.excludedGroups=
     * </pre>
     */
    @Tag("spec-generation")
    @Test
    void writeOpenApiSpecSnapshot() throws Exception {
        String yaml = mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Path target = Path.of("docs/openapi.yaml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, yaml);
    }
}
