package com.example.tasklist.lending.application.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI lendingApplicationServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Lending Application Service API")
                .version("v1")
                .description("Endpoints for the lender-limit-blocking step of a credit application."));
    }
}
