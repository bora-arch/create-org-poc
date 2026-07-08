package com.example.provisioning.api.dto;

import com.example.provisioning.domain.model.WorkflowStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Acknowledgement returned when a provisioning job is accepted")
public record CreateOrganizationResponse(
    @Schema(description = "Id of the created provisioning job; poll it for progress",
        example = "8b1b2f2c-4a11-4e7a-9c6b-7a1c3b2a0e11")
    UUID jobId,

    @Schema(description = "Workflow status at acceptance time", example = "IN_PROGRESS")
    WorkflowStatus status
) {
}
