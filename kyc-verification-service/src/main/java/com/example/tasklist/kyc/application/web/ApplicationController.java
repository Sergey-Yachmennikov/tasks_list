package com.example.tasklist.kyc.application.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

@Tag(name = "Applications", description = "Operations on credit applications")
@RequestMapping("/api/v1/applications")
public interface ApplicationController {

    @Operation(
            operationId = "completeKyc",
            summary = "Complete KYC verification for an application",
            description = """
                    Confirms KYC status with the external provider, notifies the client by SMS, \
                    and moves the application from KYC_PENDING to KYC_COMPLETED.
                    Idempotent: calling this again after a successful completion returns 200 without \
                    contacting the KYC provider or sending another SMS."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KYC completed (or was already completed)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApplicationResponse.class))),
            @ApiResponse(responseCode = "403", description = "Application does not belong to the given client",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Application not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Application is not in KYC_PENDING status, or KYC is not yet completed at the provider",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "KYC provider or notification gateway failed to process the request",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{applicationId}/complete-kyc")
    ResponseEntity<ApplicationResponse> completeKyc(
            @Parameter(description = "Application id", required = true)
            @PathVariable UUID applicationId,
            @RequestBody CompleteKycRequest request
    );
}
