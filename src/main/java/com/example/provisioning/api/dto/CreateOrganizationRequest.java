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
    @Schema(description = "Organization tier; validated and persisted onto the job, but has "
        + "no effect on which steps run or how they behave — no step reads it. Must exactly "
        + "match one of the OrgType enum constants: STANDARD, INTERNAL, ENTERPRISE.",
        example = "STANDARD", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 64) String orgType,

    @JsonProperty("source")
    @Schema(description = "Identifies the calling system; selects the fixed set of steps that "
        + "system is allowed to trigger (source_config.json) — resolved from this raw value at "
        + "job creation, before INITIAL_REQUEST_VALIDATION itself has run, independent of "
        + "org_type. Must exactly match a configured source, e.g. DEFAULT, ETL_JOB, ADMIN_APP "
        + "— an unrecognized value seeds no steps beyond INITIAL_REQUEST_VALIDATION, which will "
        + "go on to fail asynchronously with a validation error.",
        example = "DEFAULT", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 64) String source,

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
