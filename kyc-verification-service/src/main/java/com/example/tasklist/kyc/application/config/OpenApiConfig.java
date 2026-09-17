package com.example.tasklist.kyc.application.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kycVerificationServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("KYC Verification Service API")
                .version("v1")
                .description("Endpoints for the KYC-completion step of a credit application."));
    }
}
