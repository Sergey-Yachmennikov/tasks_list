package com.example.tasklist.lending.application;

import com.example.tasklist.lending.application.config.LenderProperties;
import com.example.tasklist.lending.application.config.OutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({OutboxProperties.class, LenderProperties.class})
public class LendingApplicationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LendingApplicationServiceApplication.class, args);
    }
}
