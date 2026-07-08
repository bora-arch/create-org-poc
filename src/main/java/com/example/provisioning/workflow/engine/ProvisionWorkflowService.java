package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.OrgType;
import com.example.provisioning.domain.model.OrganizationProvisionJob;
import com.example.provisioning.domain.model.OrganizationProvisionStep;
import com.example.provisioning.domain.model.StepStatus;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.example.provisioning.domain.repository.StepRepository;
import com.example.provisioning.domain.repository.WorkflowRepository;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator. Creates and pre-seeds jobs, then walks the ordered
 * step catalog delegating each step to {@link StepExecutor}.
 *
 * <p>Contains zero knowledge of concrete step classes — the registry
 * hands back an ordered list, and this class treats them as an
 * opaque sequence. Job-row writes are delegated to
 * {@link JobStateWriter} so each transition commits independently
 * and remains visible to concurrent GETs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProvisionWorkflowService {

    private final WorkflowRepository workflowRepository;
    private final StepRepository stepRepository;
    private final StepRegistry stepRegistry;
    private final StepExecutor stepExecutor;
    private final JobStateWriter jobStateWriter;
    private final DefaultConfigProvider defaultConfigProvider;

    /**
     * Persists a new job with all steps pre-seeded as {@link StepStatus#NOT_STARTED}.
     * Runs in its own short transaction so callers can commit and hand
     * off to async execution.
     */
    @Transactional
    public OrganizationProvisionJob createJob(String organizationName, String createdBy,
                                              OrgType orgType) {
        UUID jobId = UUID.randomUUID();
        OrganizationProvisionJob job = OrganizationProvisionJob.builder()
            .id(jobId)
            .status(WorkflowStatus.PENDING)
            .orgType(orgType)
            .createdBy(createdBy)
            .build();
        workflowRepository.save(job);
        seedSteps(jobId);
        log.info("Created provisioning job {} for organization '{}' (orgType={})",
            jobId, organizationName, orgType);
        return job;
    }

    /**
     * Executes the pre-seeded job. Marks the job IN_PROGRESS, walks the
     * ordered step list as a chain, and marks the terminal outcome. Steps
     * whose config section is not enabled for the org type are recorded
     * SKIPPED.
     *
     * <p>Failure handling is per-step, not all-or-nothing:
     * <ul>
     *   <li>A non-{@link ProvisionStep#critical() critical} step that
     *       fails is recorded FAILED and execution <em>continues</em>
     *       with the remaining steps. If any such failure occurred, the
     *       job ends {@link WorkflowStatus#COMPLETED_WITH_ERRORS}.</li>
     *   <li>A critical step that fails halts the chain: the job is
     *       {@link WorkflowStatus#FAILED} and the remaining steps stay
     *       NOT_STARTED (guaranteed by pre-seeding).</li>
     *   <li>No failures → {@link WorkflowStatus#SUCCESS}.</li>
     * </ul>
     */
    public void execute(UUID jobId, String organizationName, String createdBy,
                        OrgType orgType) {
        Set<String> enabledSections = defaultConfigProvider.sectionsFor(orgType);
        ProvisionContext context =
            new ProvisionContext(jobId, organizationName, createdBy, orgType, enabledSections);
        jobStateWriter.markStarted(jobId);

        List<ProvisionStep> steps = stepRegistry.ordered();
        boolean orgIdPropagated = false;
        int failedSteps = 0;

        try {
            for (ProvisionStep step : steps) {
                if (!step.shouldRun(context)) {
                    stepExecutor.skip(jobId, step);
                    continue;
                }
                jobStateWriter.markCurrentStep(jobId, step.name());
                try {
                    stepExecutor.execute(jobId, step, context);
                } catch (StepExecutionException failure) {
                    if (step.critical()) {
                        jobStateWriter.markFinished(jobId, WorkflowStatus.FAILED);
                        log.warn("Job {} halted: critical step {} failed", jobId, step.name());
                        return;
                    }
                    failedSteps++;
                    log.warn("Job {} continuing past non-critical failure of step {} ({} so far)",
                        jobId, step.name(), failedSteps);
                    continue;
                }
                if (!orgIdPropagated && context.getOrganizationId() != null) {
                    jobStateWriter.markOrganizationId(jobId, context.getOrganizationId());
                    orgIdPropagated = true;
                }
            }
            WorkflowStatus outcome = failedSteps == 0
                ? WorkflowStatus.SUCCESS
                : WorkflowStatus.COMPLETED_WITH_ERRORS;
            jobStateWriter.markFinished(jobId, outcome);
            log.info("Job {} finished {} ({} steps, {} failed)",
                jobId, outcome, steps.size(), failedSteps);
        } catch (RuntimeException unexpected) {
            jobStateWriter.markFinished(jobId, WorkflowStatus.FAILED);
            log.error("Job {} aborted by unexpected exception", jobId, unexpected);
        }
    }

    private void seedSteps(UUID jobId) {
        List<ProvisionStep> steps = stepRegistry.ordered();
        List<OrganizationProvisionStep> rows = steps.stream()
            .map(step -> OrganizationProvisionStep.builder()
                .id(UUID.randomUUID())
                .jobId(jobId)
                .stepOrder(step.order())
                .stepName(step.name())
                .status(StepStatus.NOT_STARTED)
                .build())
            .toList();
        stepRepository.saveAll(rows);
    }
}
