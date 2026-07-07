package com.example.provisioning.api.dto;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.domain.model.StepStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProvisionStepResponse(
    StepName name,
    StepStatus status,
    String errorCode,
    String errorMessage
) {
}
