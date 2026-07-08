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

    @Schema(description = "Overall workflow status", example = "IN_PROGRESS")
    WorkflowStatus status,

    @Schema(description = "Step currently executing; null once the job reaches a terminal state",
        example = "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES")
    StepName currentStep,

    @Schema(description = "Number of steps that have reached a terminal state "
        + "(SUCCESS, SKIPPED, or FAILED)", example = "12")
    int progress,

    @Schema(description = "Total number of steps in the workflow", example = "12")
    int totalSteps,

    @Schema(description = "How many steps ended in FAILED. Zero for a clean run; "
        + "non-zero pairs with status COMPLETED_WITH_ERRORS (chain finished despite "
        + "failures) or FAILED (a critical step halted the chain).", example = "1")
    int failedSteps,

    @Schema(description = "Per-step status, in execution order")
    List<ProvisionStepResponse> steps
) {
}
