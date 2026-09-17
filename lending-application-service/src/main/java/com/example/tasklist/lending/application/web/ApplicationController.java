package com.example.tasklist.lending.application.web;

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
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

@Tag(name = "Applications", description = "Operations on credit applications")
@RequestMapping("/api/v1/applications")
public interface ApplicationController {

    @Operation(
            operationId = "blockLenderLimit",
            summary = "Block the requested credit amount with the lender",
            description = """
                    Moves the application from SCORING_APPROVED to LIMIT_BLOCKED by blocking the \
                    requested amount in the external lender system, then schedules an \
                    ApplicationLimitBlocked event for publication.
                    Idempotent: calling this again after a successful block returns 204 without \
                    contacting the lender a second time."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Limit blocked (or was already blocked)"),
            @ApiResponse(responseCode = "404", description = "Application not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Application is not in SCORING_APPROVED status",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "Lender system rejected or failed to process the block request",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{applicationId}/block-limit")
    ResponseEntity<Void> blockLenderLimit(
            @Parameter(description = "Application id", required = true)
            @PathVariable UUID applicationId
    );
}
