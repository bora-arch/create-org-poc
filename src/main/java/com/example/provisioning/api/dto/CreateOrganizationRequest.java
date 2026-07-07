package com.example.provisioning.api.dto;

import com.example.provisioning.domain.model.OrgType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 128) String createdBy,

    /**
     * Organization tier. Optional — defaults to {@link OrgType#STANDARD}
     * when omitted. Selects the {@code default_config.json} profile that
     * decides which steps run and which are skipped (e.g. the
     * {@code license} section gates the PRM-license steps, {@code boosters}
     * gates {@code SETUP_FSP_BOOSTERS}).
     */
    OrgType orgType
) {

    /** Resolves the optional org type, defaulting to STANDARD. */
    public OrgType orgTypeOrDefault() {
        return orgType == null ? OrgType.STANDARD : orgType;
    }
}
