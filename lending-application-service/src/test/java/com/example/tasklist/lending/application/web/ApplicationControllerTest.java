package com.example.tasklist.lending.application.web;

import com.example.tasklist.lending.application.LendingApplicationServiceApplication;
import com.example.tasklist.lending.application.domain.Application;
import com.example.tasklist.lending.application.domain.ApplicationStatus;
import com.example.tasklist.lending.application.domain.LenderBlockResult;
import com.example.tasklist.lending.application.client.LenderClient;
import com.example.tasklist.lending.application.kafka.KafkaProducer;
import com.example.tasklist.lending.application.outbox.OutboxRepository;
import com.example.tasklist.lending.application.repository.ApplicationRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Excludes DataSource/Flyway via the standard `spring.autoconfigure.exclude` property rather
// than a second @SpringBootApplication test class: a second bootstrap class in the same base
// package gets swept up by this one's component scan, and Spring Boot *merges* every
// @EnableAutoConfiguration's exclude list it finds into one global set — so a test-only
// bootstrap's exclusions would silently disable DataSource/Flyway for the real app too.
@SpringBootTest(
        classes = LendingApplicationServiceApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
)
@AutoConfigureMockMvc
// Explicit `classes` above disables the automatic pickup of nested @TestConfiguration
// classes, so it has to be imported explicitly instead.
@Import(ApplicationControllerTest.TransactionManagerConfig.class)
class ApplicationControllerTest {

    // Gives LenderService a real, working PlatformTransactionManager without a live database,
    // since DataSource/Flyway autoconfiguration is excluded for this test (see the property above).
    @TestConfiguration
    static class TransactionManagerConfig {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApplicationRepository applicationRepository;
    @MockBean
    private OutboxRepository outboxRepository;
    @MockBean
    private LenderClient lenderClient;
    @MockBean
    private KafkaProducer kafkaProducer;

    private Application applicationWithStatus(UUID id, ApplicationStatus status) {
        return new Application(id, UUID.randomUUID(), UUID.randomUUID(), status, new BigDecimal("500.00"), Optional.empty());
    }

    @Test
    void blockLenderLimit_returns204_onSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(applicationRepository.findById(id))
                .thenReturn(Optional.of(applicationWithStatus(id, ApplicationStatus.SCORING_APPROVED)));
        when(lenderClient.blockLimit(any(), eq(id), any(), anyString()))
                .thenReturn(new LenderBlockResult("block-1"));
        when(applicationRepository.compareAndSetLimitBlocked(eq(id), eq(ApplicationStatus.SCORING_APPROVED), eq("block-1")))
                .thenReturn(true);

        mockMvc.perform(post("/api/v1/applications/{id}/block-limit", id))
                .andExpect(status().isNoContent());
    }

    @Test
    void blockLenderLimit_returns404_whenApplicationNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(applicationRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/applications/{id}/block-limit", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Application not found: " + id));
    }

    @Test
    void blockLenderLimit_returns409_whenStatusIsNotScoringApproved() throws Exception {
        UUID id = UUID.randomUUID();
        when(applicationRepository.findById(id))
                .thenReturn(Optional.of(applicationWithStatus(id, ApplicationStatus.INITIAL)));

        mockMvc.perform(post("/api/v1/applications/{id}/block-limit", id))
                .andExpect(status().isConflict());
    }

    @Test
    void blockLenderLimit_returns502_whenLenderCallFails() throws Exception {
        UUID id = UUID.randomUUID();
        when(applicationRepository.findById(id))
                .thenReturn(Optional.of(applicationWithStatus(id, ApplicationStatus.SCORING_APPROVED)));
        when(lenderClient.blockLimit(any(), eq(id), any(), anyString()))
                .thenThrow(new RuntimeException("lender timeout"));

        mockMvc.perform(post("/api/v1/applications/{id}/block-limit", id))
                .andExpect(status().isBadGateway());
    }

    @Test
    void openApiSpec_documentsTheBlockLimitEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/block-limit'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/block-limit'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/block-limit'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/block-limit'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/block-limit'].post.responses['502']").exists());
    }

    /**
     * Snapshots the live spec to docs/openapi.yaml for consumers who need a static file
     * (client codegen, contract review) without running the service. Excluded from the
     * default build (see the module pom's surefire excludedGroups) since writing to the
     * source tree isn't something a normal test run should do; re-run it by hand after
     * changing the API:
     * <pre>
     *   mvn -pl lending-application-service test \
     *       -Dtest=ApplicationControllerTest#writeOpenApiSpecSnapshot \
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
