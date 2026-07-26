package com.example.provisioning.api;

import com.example.provisioning.api.dto.CreateOrganizationRequest;
import com.example.provisioning.api.dto.ProvisionJobResponse;
import com.example.provisioning.api.error.ApiErrorResponse;
import com.example.provisioning.domain.model.OrganizationProvisionJob;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.example.provisioning.workflow.engine.ProvisionWorkflowAsyncRunner;
import com.example.provisioning.workflow.engine.ProvisionWorkflowService;
import com.example.provisioning.workflow.query.ProvisionJobQueryService;
import com.example.provisioning.workflow.steps.InitialRequestValidationStep;
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

import java.util.UUID;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "Kick off organization provisioning workflows")
public class OrganizationController {

    private final ProvisionWorkflowService workflowService;
    private final ProvisionWorkflowAsyncRunner asyncRunner;
    private final ProvisionJobQueryService queryService;

    @Operation(
        summary = "Create (or retry) an organization provisioning job",
        description = "org_uid is the idempotency/retry key: resubmitting the same org_uid "
            + "resumes a previously failed job from its failed step (fail-fast — later steps "
            + "never ran) instead of starting over. INITIAL_REQUEST_VALIDATION always runs "
            + "first and, when it runs, runs synchronously: an invalid request never starts "
            + "the async workflow and this call returns immediately with status FAILED, the "
            + "external_job_uid, and the failed step's error. Otherwise the remaining steps "
            + "run asynchronously and 202 Accepted is returned; poll "
            + "GET /organization-provision-jobs/{jobId} for progress.")
    @ApiResponses({
        @ApiResponse(
            responseCode = "202",
            description = "Provisioning (re)started asynchronously",
            content = @Content(schema = @Schema(implementation = ProvisionJobResponse.class))),
        @ApiResponse(
            responseCode = "200",
            description = "Request rejected by INITIAL_REQUEST_VALIDATION (status FAILED), or "
                + "the job for this org_uid was already SUCCESS/IN_PROGRESS and was not restarted",
            content = @Content(schema = @Schema(implementation = ProvisionJobResponse.class))),
        @ApiResponse(
            responseCode = "400",
            description = "Request validation failed",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ProvisionJobResponse> createOrganization(
        @Valid @RequestBody CreateOrganizationRequest request
    ) {
        OrganizationProvisionJob job = workflowService.findOrCreateJob(
            request.orgUid(), request.serviceUserAccount(), request.externalJobUid());
        UUID jobId = job.getId();

        if (job.getStatus() == WorkflowStatus.SUCCESS || job.getStatus() == WorkflowStatus.IN_PROGRESS) {
            return ResponseEntity.ok(queryService.fetch(jobId));
        }

        int resumeFromOrder = workflowService.firstPendingStepOrder(jobId);
        if (resumeFromOrder == InitialRequestValidationStep.ORDER) {
            boolean valid = workflowService.validateSynchronously(
                jobId, request.orgUid(), request.orgType(),
                request.serviceUserAccount(), request.externalJobUid());
            if (!valid) {
                return ResponseEntity.ok(queryService.fetch(jobId));
            }
            resumeFromOrder = workflowService.firstPendingStepOrder(jobId);
        }

        workflowService.markResuming(jobId);
        asyncRunner.run(jobId, request.orgUid(), request.serviceUserAccount(),
            request.externalJobUid(), resumeFromOrder);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(queryService.fetch(jobId));
    }
}
