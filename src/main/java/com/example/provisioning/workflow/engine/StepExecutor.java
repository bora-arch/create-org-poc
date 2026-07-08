package com.example.provisioning.workflow.engine;

import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Runs a single step end-to-end: mark IN_PROGRESS → invoke the step →
 * mark SUCCESS or FAILED, always recording timing and (on failure)
 * translated error info.
 *
 * <p>Deliberately <em>not</em> transactional itself. Each status write is
 * delegated to {@link StepStateWriter}, which commits it in its own
 * {@code REQUIRES_NEW} transaction bracketing the external call. That
 * split is what lets a GET observe {@code IN_PROGRESS} in real time and,
 * crucially, lets the {@code FAILED} row survive the
 * {@link StepExecutionException} this method throws to signal failure —
 * if the write and the throw shared one transaction, the signal would
 * roll back the audit row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StepExecutor {

    private final StepStateWriter stepStateWriter;

    public void execute(UUID jobId, ProvisionStep step, ProvisionContext context) {
        Instant started = Instant.now();
        stepStateWriter.markInProgress(jobId, step.name(), started);

        try {
            step.execute(context);
        } catch (RuntimeException failure) {
            StepFailure translated = stepStateWriter.markFailed(jobId, step.name(), started, failure);
            log.warn("Step {} for job {} failed: {} {}",
                step.name(), jobId, translated.errorCode(), translated.errorMessage());
            throw new StepExecutionException(step.name(), failure);
        }

        long durationMs = stepStateWriter.markSuccess(jobId, step.name(), started);
        log.info("Step {} for job {} completed in {} ms", step.name(), jobId, durationMs);
    }

    /**
     * Records a step as {@link com.example.provisioning.domain.model.StepStatus#SKIPPED}
     * without invoking it. Called by the orchestrator when
     * {@link ProvisionStep#shouldRun} returns false — the step does not
     * apply to this job by business rule.
     */
    public void skip(UUID jobId, ProvisionStep step) {
        stepStateWriter.markSkipped(jobId, step.name());
        log.info("Step {} for job {} skipped (not applicable)", step.name(), jobId);
    }
}
