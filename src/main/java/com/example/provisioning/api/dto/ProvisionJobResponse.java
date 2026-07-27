package com.example.provisioning.api.dto;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Full provisioning workflow state for a job")
public record ProvisionJobResponse(
    @Schema(description = "Provisioning job id", example = "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11")
    UUID jobId,

    @Schema(description = "Client-supplied organization identifier from the request; "
        + "also the retry key", example = "3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2")
    String orgUid,

    @Schema(description = "Caller-supplied job identifier, echoed back from the request",
        example = "external_job_uid")
    String externalJobUid,

    @Schema(description = "Calling system identified by the request; selects the fixed set of "
        + "steps that were allowed to run at all, independent of orgType. Null until "
        + "INITIAL_REQUEST_VALIDATION resolves it.", example = "DEFAULT")
    String source,

    @Schema(description = "Overall workflow status", example = "IN_PROGRESS")
    WorkflowStatus status,

    @Schema(description = "Step currently executing; null once the job reaches a terminal state",
        example = "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES")
    StepName currentStep,

    @Schema(description = "Number of steps that have reached a terminal state (SUCCESS or SKIPPED)",
        example = "3")
    int progress,

    @Schema(description = "Total number of steps in the workflow", example = "12")
    int totalSteps,

    @Schema(description = "Per-step status, in execution order")
    List<ProvisionStepResponse> steps
) {
}
