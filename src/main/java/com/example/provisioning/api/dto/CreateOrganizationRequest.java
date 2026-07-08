package com.example.provisioning.api.dto;

import com.example.provisioning.domain.model.OrgType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to provision a new organization")
public record CreateOrganizationRequest(
    @Schema(description = "Organization display name", example = "Acme Corporation",
        maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 255) String name,

    @Schema(description = "Identifier of the user creating the organization",
        example = "sav20006@gmail.com", maxLength = 128)
    @Size(max = 128) String createdBy,

    /**
     * Organization tier. Optional — defaults to {@link OrgType#STANDARD}
     * when omitted. Selects the {@code default_config.json} profile that
     * decides which steps run and which are skipped (e.g. the
     * {@code license} section gates the PRM-license steps, {@code boosters}
     * gates {@code SETUP_FSP_BOOSTERS}).
     */
    @Schema(description = "Organization tier; selects the config profile that decides "
        + "which steps run vs. are SKIPPED. Defaults to STANDARD when omitted.",
        example = "STANDARD", defaultValue = "STANDARD")
    OrgType orgType
) {

    /** Resolves the optional org type, defaulting to STANDARD. */
    public OrgType orgTypeOrDefault() {
        return orgType == null ? OrgType.STANDARD : orgType;
    }
}
