package com.example.tasklist.lending.application.web;

import com.example.tasklist.lending.application.service.ApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ApplicationRestController implements ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationRestController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Override
    public ResponseEntity<Void> blockLenderLimit(UUID applicationId) {
        applicationService.blockLenderLimit(applicationId);
        return ResponseEntity.noContent().build();
    }
}
