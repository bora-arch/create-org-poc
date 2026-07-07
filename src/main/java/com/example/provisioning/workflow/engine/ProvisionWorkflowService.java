package com.example.provisioning.workflow.engine;

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

    /**
     * Persists a new job with all steps pre-seeded as {@link StepStatus#NOT_STARTED}.
     * Runs in its own short transaction so callers can commit and hand
     * off to async execution.
     */
    @Transactional
    public OrganizationProvisionJob createJob(String organizationName, String createdBy) {
        UUID jobId = UUID.randomUUID();
        OrganizationProvisionJob job = OrganizationProvisionJob.builder()
            .id(jobId)
            .status(WorkflowStatus.PENDING)
            .createdBy(createdBy)
            .build();
        workflowRepository.save(job);
        seedSteps(jobId);
        log.info("Created provisioning job {} for organization '{}'", jobId, organizationName);
        return job;
    }

    /**
     * Executes the pre-seeded job. Marks the job IN_PROGRESS, walks
     * the ordered step list, and marks the terminal outcome. Any step
     * failure halts execution and leaves remaining steps NOT_STARTED
     * (guaranteed by pre-seeding).
     */
    public void execute(UUID jobId, String organizationName, String createdBy,
                        boolean prmLicensesEnabled) {
        ProvisionContext context =
            new ProvisionContext(jobId, organizationName, createdBy, prmLicensesEnabled);
        jobStateWriter.markStarted(jobId);

        List<ProvisionStep> steps = stepRegistry.ordered();
        boolean orgIdPropagated = false;
        try {
            for (ProvisionStep step : steps) {
                if (!step.shouldRun(context)) {
                    stepExecutor.skip(jobId, step);
                    continue;
                }
                jobStateWriter.markCurrentStep(jobId, step.name());
                stepExecutor.execute(jobId, step, context);
                if (!orgIdPropagated && context.getOrganizationId() != null) {
                    jobStateWriter.markOrganizationId(jobId, context.getOrganizationId());
                    orgIdPropagated = true;
                }
            }
            jobStateWriter.markFinished(jobId, WorkflowStatus.SUCCESS);
            log.info("Job {} succeeded ({} steps)", jobId, steps.size());
        } catch (StepExecutionException halt) {
            jobStateWriter.markFinished(jobId, WorkflowStatus.FAILED);
            log.warn("Job {} failed at step {}", jobId, halt.getStepName());
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
