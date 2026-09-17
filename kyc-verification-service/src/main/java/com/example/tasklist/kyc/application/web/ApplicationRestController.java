package com.example.tasklist.kyc.application.web;

import com.example.tasklist.kyc.application.domain.Application;
import com.example.tasklist.kyc.application.service.ApplicationService;
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
    public ResponseEntity<ApplicationResponse> completeKyc(UUID applicationId, CompleteKycRequest request) {
        Application application = applicationService.completeKyc(applicationId, request.clientId());
        return ResponseEntity.ok(ApplicationResponse.from(application));
    }
}
