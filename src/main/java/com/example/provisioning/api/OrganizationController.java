package com.example.provisioning.api;

import com.example.provisioning.api.dto.CreateOrganizationRequest;
import com.example.provisioning.api.dto.CreateOrganizationResponse;
import com.example.provisioning.domain.model.OrganizationProvisionJob;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.example.provisioning.api.error.ApiErrorResponse;
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
@Tag(name = "Organizations", description = "Kick off a provisioning workflow")
public class OrganizationController {

    private final ProvisionWorkflowService workflowService;
    private final ProvisionWorkflowAsyncRunner asyncRunner;

    @PostMapping
    @Operation(
        summary = "Create an organization (start a provisioning workflow)",
        description = "Persists a new job with all 12 steps pre-seeded as NOT_STARTED, "
            + "hands execution to an async worker, and returns 202 Accepted with the job id. "
            + "Poll GET /organization-provision-jobs/{jobId} for progress.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Accepted — job created and running asynchronously"),
        @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
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
