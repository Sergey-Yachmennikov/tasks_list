package com.example.tasklist.kyc.application;

import com.example.tasklist.kyc.application.config.KycProperties;
import com.example.tasklist.kyc.application.config.NotificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({KycProperties.class, NotificationProperties.class})
public class KycVerificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(KycVerificationServiceApplication.class, args);
    }
}
