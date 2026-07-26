package com.example.provisioning.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to provision a new organization.
 *
 * <p>Fields are kept as raw strings rather than typed
 * ({@code UUID}/enum) — structural presence is enforced here via bean
 * validation, but format/semantic validity (is {@code org_uid} really a
 * UUID, is {@code org_type} a known tier) is deliberately deferred to
 * the {@code INITIAL_REQUEST_VALIDATION} workflow step, so a malformed
 * value produces a normal step-failure response instead of a raw JSON
 * deserialization error.
 */
@Schema(description = "Request to provision a new organization")
public record CreateOrganizationRequest(
    @JsonProperty("org_uid")
    @Schema(description = "Client-supplied organization identifier (UUID). Also the "
        + "idempotency/retry key — resubmitting the same org_uid resumes a failed job "
        + "from its failed step instead of starting over.",
        example = "3f2a9c14-7b41-4e2a-9c31-8a2f6d1eb7d2", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 255) String orgUid,

    @JsonProperty("org_type")
    @Schema(description = "Organization tier; selects the config profile that decides "
        + "which steps run vs. are SKIPPED. One of: base, internal, enterprise.",
        example = "base", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 64) String orgType,

    @JsonProperty("service_user_account")
    @Schema(description = "Email of the service account provisioning is performed on behalf of",
        example = "sav20006@gmail.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 255) String serviceUserAccount,

    @JsonProperty("external_job_uid")
    @Schema(description = "Caller-supplied job identifier, echoed back in every response",
        example = "external_job_uid", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 255) String externalJobUid
) {
}
