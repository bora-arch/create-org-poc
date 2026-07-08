package com.example.provisioning.api.dto;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.domain.model.StepStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Status of a single provisioning step")
public record ProvisionStepResponse(
    @Schema(description = "Step identifier", example = "CREATE_ORG_IN_FSP")
    StepName name,

    @Schema(description = "Step status", example = "SUCCESS")
    StepStatus status,

    @Schema(description = "Error code from the failed downstream call; present only when status is FAILED",
        example = "401")
    String errorCode,

    @Schema(description = "Error message from the failed downstream call; present only when status is FAILED",
        example = "Unauthorized")
    String errorMessage
) {
}
