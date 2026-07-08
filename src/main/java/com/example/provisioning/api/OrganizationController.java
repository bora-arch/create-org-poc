package com.example.provisioning.api;

import com.example.provisioning.api.dto.CreateOrganizationRequest;
import com.example.provisioning.api.dto.CreateOrganizationResponse;
import com.example.provisioning.api.error.ApiErrorResponse;
import com.example.provisioning.domain.model.OrganizationProvisionJob;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.example.provisioning.workflow.engine.ProvisionWorkflowAsyncRunner;
import com.example.provisioning.workflow.engine.ProvisionWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "Kick off organization provisioning workflows")
public class OrganizationController {

    private final ProvisionWorkflowService workflowService;
    private final ProvisionWorkflowAsyncRunner asyncRunner;

    @Operation(
        summary = "Create an organization",
        description = "Registers a provisioning job and immediately returns "
            + "202 Accepted with its job id. The 12-step provisioning workflow "
            + "then runs asynchronously; poll GET /organization-provision-jobs/{jobId} "
            + "for progress.")
    @ApiResponses({
        @ApiResponse(
            responseCode = "202",
            description = "Provisioning job accepted and started asynchronously",
            content = @Content(schema = @Schema(implementation = CreateOrganizationResponse.class))),
        @ApiResponse(
            responseCode = "400",
            description = "Request validation failed",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<CreateOrganizationResponse> createOrganization(
        @Valid @RequestBody CreateOrganizationRequest request
    ) {
        OrganizationProvisionJob job = workflowService.createJob(
            request.name(), request.createdBy(), request.orgTypeOrDefault());
        asyncRunner.run(job.getId(), request.name(), request.createdBy(),
            request.orgTypeOrDefault());
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(new CreateOrganizationResponse(job.getId(), WorkflowStatus.IN_PROGRESS));
    }
}
