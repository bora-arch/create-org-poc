package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.OrgType;
import com.example.provisioning.domain.model.OrganizationProvisionJob;
import com.example.provisioning.domain.model.OrganizationProvisionStep;
import com.example.provisioning.domain.model.StepName;
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
 * Orchestrator. Finds-or-creates jobs (keyed by the client-supplied
 * {@code org_uid}, which makes retries idempotent), then walks the
 * ordered step catalog delegating each step to {@link StepExecutor}.
 *
 * <p>Contains zero knowledge of concrete step classes — the registry
 * hands back an ordered list, and this class treats them as an
 * opaque sequence. Job-row writes are delegated to
 * {@link JobStateWriter} so each transition commits independently
 * and remains visible to concurrent GETs.
 *
 * <p>{@code INITIAL_REQUEST_VALIDATION} (always order {@code 0}) is
 * special: the controller runs it synchronously via
 * {@link #validateSynchronously} before handing off to async
 * execution, so an invalid request never starts the background
 * workflow and the caller gets an immediate FAILED response. Every
 * other step runs inside {@link #execute}, fail-fast: a step failure
 * halts the loop and leaves remaining steps {@code NOT_STARTED}. A
 * retry (same {@code org_uid}, job status {@code FAILED}) resumes at
 * the first non-terminal step instead of restarting the whole job.
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
     * Looks up the job for {@code orgUid}. If found, refreshes the
     * mutable request fields (a retry may correct them) and returns it
     * unchanged otherwise. If not found, persists a new job with all
     * steps pre-seeded as {@link StepStatus#NOT_STARTED}.
     */
    @Transactional
    public OrganizationProvisionJob findOrCreateJob(String orgUid, String serviceUserAccount,
                                                     String externalJobUid) {
        return workflowRepository.findByOrgUid(orgUid)
            .map(job -> {
                job.setServiceUserAccount(serviceUserAccount);
                job.setExternalJobUid(externalJobUid);
                workflowRepository.save(job);
                return job;
            })
            .orElseGet(() -> createJob(orgUid, serviceUserAccount, externalJobUid));
    }

    /**
     * Order of the earliest step that has not reached a terminal state
     * (i.e. is {@code NOT_STARTED} or {@code FAILED}) — the point a
     * fresh run starts at, or a retry resumes from. {@code Integer.MAX_VALUE}
     * if every step is already SUCCESS/SKIPPED (job already finished).
     */
    @Transactional(readOnly = true)
    public int firstPendingStepOrder(UUID jobId) {
        return stepRepository.findByJobIdOrderByStepOrder(jobId).stream()
            .filter(s -> s.getStatus() == StepStatus.NOT_STARTED || s.getStatus() == StepStatus.FAILED)
            .findFirst()
            .map(OrganizationProvisionStep::getStepOrder)
            .orElse(Integer.MAX_VALUE);
    }

    /**
     * Runs only {@code INITIAL_REQUEST_VALIDATION}, synchronously, on
     * the calling thread. Returns {@code true} if the request is valid
     * (org type + config sections are now resolved and persisted onto
     * the job), {@code false} if it failed validation — in which case
     * the job is already marked FAILED and the caller should respond
     * with the current job state rather than starting async execution.
     */
    public boolean validateSynchronously(UUID jobId, String rawOrgUid, String rawOrgType,
                                         String serviceUserAccount, String externalJobUid) {
        ProvisionContext context =
            new ProvisionContext(jobId, rawOrgUid, rawOrgType, serviceUserAccount, externalJobUid);
        ProvisionStep validationStep = validationStep();

        jobStateWriter.markStarted(jobId);
        jobStateWriter.markCurrentStep(jobId, validationStep.name());
        try {
            stepExecutor.execute(jobId, validationStep, context);
            jobStateWriter.markOrgTypeResolved(jobId, context.getOrgType());
            return true;
        } catch (StepExecutionException halt) {
            jobStateWriter.markFinished(jobId, WorkflowStatus.FAILED);
            log.info("Job {} rejected: request validation failed", jobId);
            return false;
        }
    }

    /**
     * Marks the job IN_PROGRESS synchronously, on the calling thread,
     * right before handing off to {@link ProvisionWorkflowAsyncRunner}.
     * Without this, a response built immediately after triggering async
     * execution could race the background thread and read the job's
     * previous (possibly FAILED) state instead.
     */
    public void markResuming(UUID jobId) {
        jobStateWriter.markStarted(jobId);
    }

    /**
     * Executes (or resumes) the workflow from {@code resumeFromOrder}
     * onward. Steps with a lower order are left untouched — they are
     * already SUCCESS/SKIPPED from a prior attempt. Always called after
     * {@link #validateSynchronously} has already succeeded (in this run
     * or a previous one), so the job's {@code orgType} is guaranteed
     * resolved.
     */
    public void execute(UUID jobId, String rawOrgUid, String serviceUserAccount,
                        String externalJobUid, int resumeFromOrder) {
        OrganizationProvisionJob job = workflowRepository.findById(jobId)
            .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        OrgType orgType = job.getOrgType();
        if (orgType == null) {
            throw new IllegalStateException(
                "Job " + jobId + " has no resolved orgType; validation must succeed before execute()");
        }
        Set<String> enabledSections = defaultConfigProvider.sectionsFor(orgType);
        ProvisionContext context =
            new ProvisionContext(jobId, rawOrgUid, orgType.name(), serviceUserAccount, externalJobUid);
        context.markValidated(UUID.fromString(job.getOrgUid()), orgType, enabledSections);

        jobStateWriter.markStarted(jobId);
        List<ProvisionStep> steps = stepRegistry.ordered();
        try {
            for (ProvisionStep step : steps) {
                if (step.order() < resumeFromOrder) {
                    continue;
                }
                if (!step.shouldRun(context)) {
                    stepExecutor.skip(jobId, step);
                    continue;
                }
                jobStateWriter.markCurrentStep(jobId, step.name());
                stepExecutor.execute(jobId, step, context);
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

    private OrganizationProvisionJob createJob(String orgUid, String serviceUserAccount,
                                               String externalJobUid) {
        UUID jobId = UUID.randomUUID();
        OrganizationProvisionJob job = OrganizationProvisionJob.builder()
            .id(jobId)
            .orgUid(orgUid)
            .serviceUserAccount(serviceUserAccount)
            .externalJobUid(externalJobUid)
            .status(WorkflowStatus.PENDING)
            .build();
        workflowRepository.save(job);
        seedSteps(jobId);
        log.info("Created provisioning job {} for org_uid '{}'", jobId, orgUid);
        return job;
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

    private ProvisionStep validationStep() {
        return stepRegistry.ordered().stream()
            .filter(step -> step.name() == StepName.INITIAL_REQUEST_VALIDATION)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "INITIAL_REQUEST_VALIDATION step not registered"));
    }
}
