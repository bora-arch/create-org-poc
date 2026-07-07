package com.example.provisioning.api.dto;

import com.example.provisioning.domain.model.WorkflowStatus;

import java.util.UUID;

public record CreateOrganizationResponse(UUID jobId, WorkflowStatus status) {
}
