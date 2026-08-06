package com.example.provisioning.api;

import com.example.provisioning.api.dto.CreateOrganizationRequest;
import com.example.provisioning.api.dto.ProvisionJobResponse;
import com.example.provisioning.api.error.ApiErrorResponse;
import com.example.provisioning.domain.model.OrganizationProvisionJob;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.example.provisioning.workflow.engine.ProvisionWorkflowAsyncRunner;
import com.example.provisioning.workflow.engine.ProvisionWorkflowService;
import com.example.provisioning.workflow.query.ProvisionJobQueryService;
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
            + "never ran) instead of starting over. source identifies the calling system and, "
            + "at job creation, determines every step the job will ever run (source_config.json) "
            + "— org_type is validated and persisted but has no effect on which steps run. Every "
            + "step, including INITIAL_REQUEST_VALIDATION, executes asynchronously: this call "
            + "always seeds/looks up the job and returns 202 Accepted immediately; an invalid "
            + "request only becomes visible as status FAILED on a later poll of "
            + "GET /organization-provision-jobs/{jobId}.")
    @ApiResponses({
        @ApiResponse(
            responseCode = "202",
            description = "Provisioning (re)started asynchronously",
            content = @Content(schema = @Schema(implementation = ProvisionJobResponse.class))),
        @ApiResponse(
            responseCode = "200",
            description = "The job for this org_uid was already SUCCESS/IN_PROGRESS and was not restarted",
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
            request.orgUid(), request.source(), request.serviceUserAccount(), request.externalJobUid());
        UUID jobId = job.getId();

        if (job.getStatus() == WorkflowStatus.SUCCESS || job.getStatus() == WorkflowStatus.IN_PROGRESS) {
            return ResponseEntity.ok(queryService.fetch(jobId));
        }

        int resumeFromOrder = workflowService.firstPendingStepOrder(jobId);
        workflowService.markResuming(jobId);
        asyncRunner.run(jobId, request.orgUid(), request.orgType(), request.source(),
            request.serviceUserAccount(), request.externalJobUid(), resumeFromOrder);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(queryService.fetch(jobId));
    }
}
