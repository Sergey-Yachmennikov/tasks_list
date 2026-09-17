package com.example.tasklist.kyc.application.web;

import com.example.tasklist.kyc.application.KycVerificationServiceApplication;
import com.example.tasklist.kyc.application.client.KycClient;
import com.example.tasklist.kyc.application.client.NotificationClient;
import com.example.tasklist.kyc.application.domain.Application;
import com.example.tasklist.kyc.application.domain.ApplicationStatus;
import com.example.tasklist.kyc.application.domain.KycResult;
import com.example.tasklist.kyc.application.domain.NotificationResult;
import com.example.tasklist.kyc.application.repository.ApplicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Исключаем DataSource/Flyway через стандартное свойство `spring.autoconfigure.exclude`,
// а не через второй тестовый класс @SpringBootApplication: второй bootstrap-класс в том же
// базовом пакете попадёт в component scan этого приложения, а Spring Boot *объединяет*
// exclude-списки всех найденных @EnableAutoConfiguration в один общий набор — поэтому
// exclude из тестового bootstrap-класса тихо отключил бы DataSource/Flyway и в настоящем приложении.
@SpringBootTest(
        classes = KycVerificationServiceApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
)
@AutoConfigureMockMvc
class ApplicationControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ApplicationRepository applicationRepository;
    @MockBean
    private KycClient kycClient;
    @MockBean
    private NotificationClient notificationClient;

    private Application applicationWithStatus(UUID id, UUID clientId, ApplicationStatus status) {
        return new Application(id, clientId, status, "session-1");
    }

    private String requestBody(UUID clientId) throws Exception {
        return objectMapper.writeValueAsString(new CompleteKycRequest(clientId));
    }

    @Test
    void completeKyc_returns200_onSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        when(applicationRepository.findById(id))
                .thenReturn(Optional.of(applicationWithStatus(id, clientId, ApplicationStatus.KYC_PENDING)));
        when(kycClient.fetchStatus(anyString())).thenReturn(new KycResult(true, "verified"));
        when(notificationClient.sendSms(any())).thenReturn(new NotificationResult.Success("msg-1"));
        when(applicationRepository.compareAndSetKycCompleted(id, ApplicationStatus.KYC_PENDING)).thenReturn(true);

        mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(clientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("KYC_COMPLETED"));
    }

    @Test
    void completeKyc_returns404_whenApplicationNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(applicationRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Application not found: " + id));
    }

    @Test
    void completeKyc_returns409_whenStatusIsNotKycPending() throws Exception {
        UUID id = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        when(applicationRepository.findById(id))
                .thenReturn(Optional.of(applicationWithStatus(id, clientId, ApplicationStatus.INITIAL)));

        mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(clientId)))
                .andExpect(status().isConflict());
    }

    @Test
    void completeKyc_returns403_whenClientIdDoesNotMatch() throws Exception {
        UUID id = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        when(applicationRepository.findById(id))
                .thenReturn(Optional.of(applicationWithStatus(id, clientId, ApplicationStatus.KYC_PENDING)));

        mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void completeKyc_returns409_whenKycNotCompletedAtProvider() throws Exception {
        UUID id = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        when(applicationRepository.findById(id))
                .thenReturn(Optional.of(applicationWithStatus(id, clientId, ApplicationStatus.KYC_PENDING)));
        when(kycClient.fetchStatus(anyString())).thenReturn(new KycResult(false, "still processing"));

        mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(clientId)))
                .andExpect(status().isConflict());
    }

    @Test
    void completeKyc_returns502_whenNotificationFails() throws Exception {
        UUID id = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        when(applicationRepository.findById(id))
                .thenReturn(Optional.of(applicationWithStatus(id, clientId, ApplicationStatus.KYC_PENDING)));
        when(kycClient.fetchStatus(anyString())).thenReturn(new KycResult(true, "verified"));
        when(notificationClient.sendSms(any())).thenReturn(new NotificationResult.ValidationError("bad phone number"));

        mockMvc.perform(post("/api/v1/applications/{id}/complete-kyc", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(clientId)))
                .andExpect(status().isBadGateway());
    }

    @Test
    void openApiSpec_documentsTheCompleteKycEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/complete-kyc'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/complete-kyc'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/complete-kyc'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/complete-kyc'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/complete-kyc'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/complete-kyc'].post.responses['502']").exists());
    }

    /**
     * Снимает снапшот живой спеки в docs/openapi.yaml для потребителей, которым нужен
     * статический файл (кодогенерация клиента, ревью контракта) без запуска сервиса.
     * Исключён из обычной сборки (см. surefire excludedGroups в pom модуля), поскольку запись
     * в исходное дерево — не то, что должен делать обычный прогон тестов; перезапускать вручную
     * после изменения API:
     * <pre>
     *   mvn -pl kyc-verification-service test \
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
