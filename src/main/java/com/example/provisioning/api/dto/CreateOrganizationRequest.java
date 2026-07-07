package com.example.provisioning.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 128) String createdBy,

    /**
     * Whether PRM licenses should be enabled for the new organization.
     * Optional — defaults to {@code true} when omitted. Drives the
     * mutually exclusive {@code ENABLE_PRM_LICENSES} /
     * {@code DISABLE_PRM_LICENSES} steps: one runs, the other is skipped.
     */
    Boolean prmLicensesEnabled
) {

    /** Resolves the optional flag, defaulting to enabled. */
    public boolean prmLicensesEnabledOrDefault() {
        return prmLicensesEnabled == null || prmLicensesEnabled;
    }
}
