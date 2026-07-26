package com.example.provisioning.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "organization_provision_job",
    uniqueConstraints = @UniqueConstraint(name = "uk_job_org_uid", columnNames = "org_uid")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationProvisionJob {

    @Id
    private UUID id;

    /**
     * Client-supplied organization identifier from the request
     * ({@code org_uid}). Doubles as the idempotency/retry key: a
     * subsequent {@code POST /organizations} with the same {@code org_uid}
     * resumes this job instead of creating a new one. Stored as a raw
     * string (not {@code UUID}) so a malformed value can still be
     * persisted and reported by {@code INITIAL_REQUEST_VALIDATION}
     * rather than blowing up job creation itself.
     */
    @Column(name = "org_uid", nullable = false, length = 255)
    private String orgUid;

    /** Caller-supplied job identifier ({@code external_job_uid}), echoed back in every response. */
    @Column(name = "external_job_uid", length = 255)
    private String externalJobUid;

    @Column(name = "service_user_account", length = 255)
    private String serviceUserAccount;

    /**
     * Resolved org tier. Null until {@code INITIAL_REQUEST_VALIDATION}
     * succeeds — the request's raw {@code org_type} string may be
     * invalid, so this column only ever holds a validated value.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "org_type", length = 32)
    private OrgType orgType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", length = 64)
    private StepName currentStep;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
