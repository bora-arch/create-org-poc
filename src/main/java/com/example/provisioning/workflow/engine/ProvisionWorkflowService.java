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
 *
 * <p>Only {@code INITIAL_REQUEST_VALIDATION} is pre-seeded at job
 * creation — {@code source} (and therefore which other steps even
 * apply) isn't known yet. Once validation resolves {@code source},
 * {@link #validateSynchronously} seeds exactly the steps
 * {@link SourceStepConfigProvider#stepsFor} selects, in catalog order.
 * A step outside that set never gets a row at all — it never appears
 * in {@code GET}/{@code POST} responses, rather than showing up as
 * {@code SKIPPED} — so the step list reported to a caller only ever
 * contains steps actually configured for that {@code source}.
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
    private final SourceStepConfigProvider sourceStepConfigProvider;

    /**
     * Looks up the job for {@code orgUid}. If found, refreshes the
     * request fields that are safe to correct on a retry —
     * {@code serviceUserAccount} / {@code externalJobUid} — and returns
     * it. {@code orgType} and {@code source} are deliberately NOT
     * refreshed here: they are only persisted once
     * {@code INITIAL_REQUEST_VALIDATION} resolves them (see
     * {@link JobStateWriter#markValidationResolved}), so a retry that
     * changes them takes effect only while the job is still stuck at
     * validation; once later steps have run under a given org type /
     * source, changing either mid-flight would desync which steps were
     * actually gated by which rules. If not found, persists a new job
     * with only {@code INITIAL_REQUEST_VALIDATION} pre-seeded as
     * {@link StepStatus#NOT_STARTED} — the rest are seeded once
     * {@code source} is resolved, see {@link #validateSynchronously}.
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
     * (org type, source, and their derived enabled sections/steps are
     * now resolved and persisted onto the job), {@code false} if it
     * failed validation — in which case the job is already marked
     * FAILED and the caller should respond with the current job state
     * rather than starting async execution.
     *
     * <p>On success, seeds the step rows for exactly the steps
     * {@code source} selects (see {@link #seedStepsForSource}) — this
     * runs exactly once per job, since a job never re-enters this
     * method once {@code INITIAL_REQUEST_VALIDATION} has succeeded.
     */
    public boolean validateSynchronously(UUID jobId, String rawOrgUid, String rawOrgType,
                                         String rawSource, String serviceUserAccount,
                                         String externalJobUid) {
        ProvisionContext context = new ProvisionContext(
            jobId, rawOrgUid, rawOrgType, rawSource, serviceUserAccount, externalJobUid);
        ProvisionStep validationStep = validationStep();

        jobStateWriter.markStarted(jobId);
        jobStateWriter.markCurrentStep(jobId, validationStep.name());
        try {
            stepExecutor.execute(jobId, validationStep, context);
            jobStateWriter.markValidationResolved(jobId, context.getOrgType(), rawSource);
            seedStepsForSource(jobId, context.getEnabledSteps());
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
     * or a previous one), so the job's {@code orgType} / {@code source}
     * are guaranteed resolved.
     *
     * <p>A step outside {@code source}'s selection has no row at all
     * (see {@link #seedStepsForSource}) — it's silently skipped over in
     * this loop, not touched via {@link StepExecutor}. A step that
     * {@code source} did select still only runs if it is also
     * applicable per its own {@link ProvisionStep#shouldRun} (org-type
     * gating); failing that records it {@code SKIPPED}. Either way,
     * relative execution order among the steps that do run is
     * unaffected by source restricting the set.
     */
    public void execute(UUID jobId, String rawOrgUid, String serviceUserAccount,
                        String externalJobUid, int resumeFromOrder) {
        OrganizationProvisionJob job = workflowRepository.findById(jobId)
            .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        OrgType orgType = job.getOrgType();
        String source = job.getSource();
        if (orgType == null || source == null) {
            throw new IllegalStateException(
                "Job " + jobId + " has no resolved orgType/source; validation must succeed before execute()");
        }
        Set<String> enabledSections = defaultConfigProvider.sectionsFor(orgType);
        Set<StepName> enabledSteps = sourceStepConfigProvider.stepsFor(source);
        ProvisionContext context = new ProvisionContext(
            jobId, rawOrgUid, orgType.name(), source, serviceUserAccount, externalJobUid);
        context.markValidated(UUID.fromString(job.getOrgUid()), orgType, enabledSections, enabledSteps);

        jobStateWriter.markStarted(jobId);
        List<ProvisionStep> steps = stepRegistry.ordered();
        try {
            for (ProvisionStep step : steps) {
                if (step.order() < resumeFromOrder || !context.isStepEnabledForSource(step.name())) {
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
        seedStepRow(jobId, validationStep());
        log.info("Created provisioning job {} for org_uid '{}'", jobId, orgUid);
        return job;
    }

    /**
     * Seeds one {@link StepStatus#NOT_STARTED} row per catalog step
     * that {@code enabledSteps} selects (in catalog order), skipping
     * {@code INITIAL_REQUEST_VALIDATION} itself — already seeded by
     * {@link #createJob}. Steps outside {@code enabledSteps} never get
     * a row at all: they are absent from every response for this job,
     * not reported as {@code SKIPPED}.
     */
    private void seedStepsForSource(UUID jobId, Set<StepName> enabledSteps) {
        List<OrganizationProvisionStep> rows = stepRegistry.ordered().stream()
            .filter(step -> step.name() != StepName.INITIAL_REQUEST_VALIDATION)
            .filter(step -> enabledSteps.contains(step.name()))
            .map(step -> newStepRow(jobId, step))
            .toList();
        stepRepository.saveAll(rows);
    }

    private void seedStepRow(UUID jobId, ProvisionStep step) {
        stepRepository.save(newStepRow(jobId, step));
    }

    private OrganizationProvisionStep newStepRow(UUID jobId, ProvisionStep step) {
        return OrganizationProvisionStep.builder()
            .id(UUID.randomUUID())
            .jobId(jobId)
            .stepOrder(step.order())
            .stepName(step.name())
            .status(StepStatus.NOT_STARTED)
            .build();
    }

    private ProvisionStep validationStep() {
        return stepRegistry.ordered().stream()
            .filter(step -> step.name() == StepName.INITIAL_REQUEST_VALIDATION)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "INITIAL_REQUEST_VALIDATION step not registered"));
    }
}
