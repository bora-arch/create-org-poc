package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.OrganizationProvisionStep;
import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.domain.model.StepStatus;
import com.example.provisioning.domain.repository.StepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Short-lived, independently-committing writes for a single step row.
 *
 * <p>Each transition ({@code IN_PROGRESS}, {@code SUCCESS}, {@code FAILED},
 * {@code SKIPPED}) runs in its own {@code REQUIRES_NEW} transaction and
 * commits before the next begins. Two consequences the workflow relies on:
 *
 * <ul>
 *   <li>A concurrent GET observes {@code IN_PROGRESS} in real time — the
 *       write commits <em>before</em> the (potentially slow) external call,
 *       rather than being held open across it.</li>
 *   <li>A {@code FAILED} row survives. {@link StepExecutor} signals a
 *       failure to the orchestrator by throwing <em>after</em> this write
 *       has committed, so the audit row is not rolled back with the
 *       signal — which is what makes continue-on-failure possible.</li>
 * </ul>
 *
 * <p>Living on a separate bean also guarantees Spring's transactional
 * proxy engages (a self-invoked method on {@link StepExecutor} would
 * bypass it).
 */
@Component
@RequiredArgsConstructor
public class StepStateWriter {

    private final StepRepository stepRepository;
    private final StepFailureTranslator failureTranslator;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInProgress(UUID jobId, StepName name, Instant started) {
        OrganizationProvisionStep row = load(jobId, name);
        row.setStatus(StepStatus.IN_PROGRESS);
        row.setStartedAt(started);
        stepRepository.save(row);
    }

    /** Marks the row SUCCESS and returns the recorded duration in ms. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long markSuccess(UUID jobId, StepName name, Instant started) {
        Instant finished = Instant.now();
        long durationMs = finished.toEpochMilli() - started.toEpochMilli();
        OrganizationProvisionStep row = load(jobId, name);
        row.setStatus(StepStatus.SUCCESS);
        row.setFinishedAt(finished);
        row.setDurationMs(durationMs);
        stepRepository.save(row);
        return durationMs;
    }

    /**
     * Translates the cause, marks the row FAILED with the error info, and
     * returns the translated {@link StepFailure} for logging. Commits on
     * its own so the caller can then throw without rolling this back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StepFailure markFailed(UUID jobId, StepName name, Instant started, Throwable cause) {
        Instant finished = Instant.now();
        StepFailure failure = failureTranslator.translate(cause);
        OrganizationProvisionStep row = load(jobId, name);
        row.setStatus(StepStatus.FAILED);
        row.setFinishedAt(finished);
        row.setDurationMs(finished.toEpochMilli() - started.toEpochMilli());
        row.setErrorCode(failure.errorCode());
        row.setErrorMessage(failure.errorMessage());
        stepRepository.save(row);
        return failure;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSkipped(UUID jobId, StepName name) {
        OrganizationProvisionStep row = load(jobId, name);
        row.setStatus(StepStatus.SKIPPED);
        row.setFinishedAt(Instant.now());
        stepRepository.save(row);
    }

    private OrganizationProvisionStep load(UUID jobId, StepName name) {
        return stepRepository.findByJobIdOrderByStepOrder(jobId).stream()
            .filter(s -> s.getStepName() == name)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No step row for job " + jobId + " and step " + name
                    + "; job was not pre-seeded correctly."));
    }
}
