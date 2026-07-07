package com.example.provisioning.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_provision_job")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationProvisionJob {

    @Id
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

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

    @Column(name = "created_by", length = 128)
    private String createdBy;
}
