package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.OrgType;
import com.example.provisioning.domain.model.OrganizationProvisionJob;
import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.example.provisioning.domain.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Short-lived, {@code REQUIRES_NEW}-transactional writes for the job
 * row. Extracted from the orchestrator so each write commits
 * independently and remains observable to concurrent GETs.
 * A separate bean also ensures Spring's transactional proxy actually
 * engages (self-invocation from within the orchestrator would bypass it).
 */
@Component
@RequiredArgsConstructor
public class JobStateWriter {

    private final WorkflowRepository workflowRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStarted(UUID jobId) {
        OrganizationProvisionJob job = load(jobId);
        job.setStatus(WorkflowStatus.IN_PROGRESS);
        job.setStartedAt(Instant.now());
        workflowRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCurrentStep(UUID jobId, StepName current) {
        OrganizationProvisionJob job = load(jobId);
        job.setCurrentStep(current);
        workflowRepository.save(job);
    }

    /**
     * Persists the org type resolved by {@code INITIAL_REQUEST_VALIDATION}
     * so a later resumed run can reconstruct it without re-validating
     * the request.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markOrgTypeResolved(UUID jobId, OrgType orgType) {
        OrganizationProvisionJob job = load(jobId);
        job.setOrgType(orgType);
        workflowRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFinished(UUID jobId, WorkflowStatus finalStatus) {
        OrganizationProvisionJob job = load(jobId);
        job.setStatus(finalStatus);
        job.setFinishedAt(Instant.now());
        workflowRepository.save(job);
    }

    private OrganizationProvisionJob load(UUID jobId) {
        return workflowRepository.findById(jobId)
            .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
    }
}
