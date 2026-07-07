package com.example.provisioning.api.dto;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProvisionJobResponse(
    UUID jobId,
    WorkflowStatus status,
    StepName currentStep,
    int progress,
    int totalSteps,
    List<ProvisionStepResponse> steps
) {
}
