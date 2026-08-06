package com.example.provisioning.workflow.engine;

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
import java.util.stream.Collectors;

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
 * <p>Every step, including {@code INITIAL_REQUEST_VALIDATION} (always
 * order {@code 0}), runs asynchronously through the normal
 * {@link #execute} loop — there is no synchronous pre-validation
 * step anymore. {@code POST /organizations} always returns
 * {@code 202 Accepted} immediately (once the job is created/found and
 * its rows are seeded); an invalid request only becomes visible as
 * {@code status: FAILED} on a later {@code GET}. Execution is
 * fail-fast: a step failure halts the loop and leaves remaining steps
 * {@code NOT_STARTED}. A retry (same {@code org_uid}, job status
 * {@code FAILED}) resumes at the first non-terminal step instead of
 * restarting the whole job.
 *
 * <p>All of a job's step rows — {@code INITIAL_REQUEST_VALIDATION}
 * plus every step {@link SourceStepConfigProvider#stepsFor} selects
 * for the request's raw, not-yet-validated {@code source} string —
 * are seeded {@code NOT_STARTED} at job creation (see {@link #createJob}),
 * before any step has run, and topped up (idempotently) on any retry
 * before validation has succeeded (see {@link #ensureStepsSeeded}) —
 * covering a {@code source} the first attempt didn't recognize. A step
 * outside the selected set never gets a row at all — it never appears
 * in {@code GET}/{@code POST} responses, rather than showing up as
 * {@code SKIPPED} — so the step list reported to a caller only ever
 * contains steps actually configured for that {@code source}. If
 * {@code source} itself turns out to be unrecognized, {@code stepsFor}
 * yields an empty set, so only {@code INITIAL_REQUEST_VALIDATION} is
 * seeded — it is the one that will go on to report the "unknown
 * source" validation error.
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
     * and seeds every one of its step rows — see {@link #createJob}.
     */
    @Transactional
    public OrganizationProvisionJob findOrCreateJob(String orgUid, String rawSource,
                                                     String serviceUserAccount,
                                                     String externalJobUid) {
        return workflowRepository.findByOrgUid(orgUid)
            .map(job -> {
                job.setServiceUserAccount(serviceUserAccount);
                job.setExternalJobUid(externalJobUid);
                workflowRepository.save(job);
                return job;
            })
            .orElseGet(() -> createJob(orgUid, rawSource, serviceUserAccount, externalJobUid));
    }

    /**
     * Order of the earliest step that has not reached a terminal state
     * (i.e. is {@code NOT_STARTED} or {@code FAILED}) — the point a
     * fresh run starts at, or a retry resumes from. {@code Integer.MAX_VALUE}
     * if every step is already {@code SUCCESS} (job already finished).
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
     * onward, including {@code INITIAL_REQUEST_VALIDATION} itself when
     * {@code resumeFromOrder == 0} (a fresh job, or a retry of one that
     * failed validation). Steps with a lower order are left untouched —
     * they are already SUCCESS from a prior attempt.
     *
     * <p>If validation already succeeded in a previous attempt
     * ({@code job.getOrgType()} is set), that step is skipped by
     * {@code resumeFromOrder} and its resolved values are reconstructed
     * from the job row instead of being re-parsed from the raw request.
     * Otherwise this run's raw {@code org_type} is carried on the
     * context for {@code INITIAL_REQUEST_VALIDATION} to parse when its
     * turn comes, and {@link #ensureStepsSeeded} tops up this run's step
     * rows from this attempt's raw {@code source} — covering a retry
     * that corrects a {@code source} the very first attempt never
     * recognized (so nothing beyond {@code INITIAL_REQUEST_VALIDATION}
     * was seeded back then).
     *
     * <p>Only steps that actually have a row for this job are executed;
     * the loop silently continues past any catalog step without one.
     * Since seeding already filtered to exactly {@code source}'s
     * selection, every step reached here always runs to completion —
     * there is no further per-step business condition.
     */
    public void execute(UUID jobId, String rawOrgUid, String rawOrgType, String rawSource,
                        String serviceUserAccount, String externalJobUid, int resumeFromOrder) {
        OrganizationProvisionJob job = workflowRepository.findById(jobId)
            .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

        ProvisionContext context = new ProvisionContext(
            jobId, rawOrgUid, rawOrgType, rawSource, serviceUserAccount, externalJobUid);
        if (job.getOrgType() != null) {
            context.markValidated(UUID.fromString(job.getOrgUid()), job.getOrgType());
        } else {
            ensureStepsSeeded(jobId, rawSource);
        }

        Set<StepName> seededSteps = stepRepository.findByJobIdOrderByStepOrder(jobId).stream()
            .map(OrganizationProvisionStep::getStepName)
            .collect(Collectors.toSet());

        jobStateWriter.markStarted(jobId);
        List<ProvisionStep> steps = stepRegistry.ordered();
        try {
            for (ProvisionStep step : steps) {
                if (step.order() < resumeFromOrder || !seededSteps.contains(step.name())) {
                    continue;
                }
                jobStateWriter.markCurrentStep(jobId, step.name());
                stepExecutor.execute(jobId, step, context);
                if (step.name() == StepName.INITIAL_REQUEST_VALIDATION) {
                    jobStateWriter.markValidationResolved(jobId, context.getOrgType(), rawSource);
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

    private OrganizationProvisionJob createJob(String orgUid, String rawSource,
                                               String serviceUserAccount, String externalJobUid) {
        UUID jobId = UUID.randomUUID();
        OrganizationProvisionJob job = OrganizationProvisionJob.builder()
            .id(jobId)
            .orgUid(orgUid)
            .serviceUserAccount(serviceUserAccount)
            .externalJobUid(externalJobUid)
            .status(WorkflowStatus.PENDING)
            .build();
        workflowRepository.save(job);
        ensureStepsSeeded(jobId, rawSource);
        log.info("Created provisioning job {} for org_uid '{}'", jobId, orgUid);
        return job;
    }

    /**
     * Ensures a {@link StepStatus#NOT_STARTED} row exists for every step
     * this job should have, straight from the request's raw (not yet
     * validated) {@code source} string: {@code INITIAL_REQUEST_VALIDATION}
     * unconditionally, plus one row per catalog step
     * {@link SourceStepConfigProvider#stepsFor} selects for {@code rawSource},
     * in catalog order. Steps outside that set never get a row at all:
     * they are absent from every response for this job, not reported
     * as {@code SKIPPED}. An unrecognized {@code rawSource} yields an
     * empty selection, so only {@code INITIAL_REQUEST_VALIDATION} is
     * seeded — it is what will go on to report the "unknown source"
     * validation error once it runs.
     *
     * <p>Idempotent — only inserts rows that don't already exist — so
     * it's safe to call again on a retry whose raw {@code source} has
     * changed since a previous attempt that never got past validation
     * (e.g. correcting a {@code source} the first attempt didn't
     * recognize). It must never run once validation has actually
     * succeeded (see the caller in {@link #execute}), since by then the
     * job's step set is fixed and a later request changing {@code source}
     * must not silently add or remove rows out from under it.
     */
    private void ensureStepsSeeded(UUID jobId, String rawSource) {
        Set<StepName> enabledSteps = sourceStepConfigProvider.stepsFor(rawSource);
        Set<StepName> alreadySeeded = stepRepository.findByJobIdOrderByStepOrder(jobId).stream()
            .map(OrganizationProvisionStep::getStepName)
            .collect(Collectors.toSet());
        List<OrganizationProvisionStep> rows = stepRegistry.ordered().stream()
            .filter(step -> step.name() == StepName.INITIAL_REQUEST_VALIDATION
                || enabledSteps.contains(step.name()))
            .filter(step -> !alreadySeeded.contains(step.name()))
            .map(step -> newStepRow(jobId, step))
            .toList();
        if (!rows.isEmpty()) {
            stepRepository.saveAll(rows);
        }
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
}
