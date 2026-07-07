package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.OrganizationProvisionStep;
import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.domain.model.StepStatus;
import com.example.provisioning.domain.repository.StepRepository;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Runs a single step end-to-end: mark IN_PROGRESS → invoke the step →
 * mark SUCCESS or FAILED, always recording timing and (on failure)
 * translated error info.
 *
 * <p>Each invocation runs in its own {@code REQUIRES_NEW} transaction
 * so a failure persists the FAILED row (the whole point of the audit
 * trail) and so the GET endpoint can observe IN_PROGRESS state in real
 * time. In production, a long-running external call would be split
 * into two short transactions bracketing the call — the same shape,
 * just with the {@code @Transactional} boundary moved.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StepExecutor {

    private final StepRepository stepRepository;
    private final StepFailureTranslator failureTranslator;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(UUID jobId, ProvisionStep step, ProvisionContext context) {
        OrganizationProvisionStep row = loadStepRow(jobId, step.name());
        Instant started = Instant.now();
        markInProgress(row, started);

        try {
            step.execute(context);
            markSuccess(row, started);
            log.info("Step {} for job {} completed in {} ms",
                step.name(), jobId, row.getDurationMs());
        } catch (RuntimeException failure) {
            markFailed(row, started, failure);
            log.warn("Step {} for job {} failed after {} ms: {} {}",
                step.name(), jobId, row.getDurationMs(),
                row.getErrorCode(), row.getErrorMessage());
            throw new StepExecutionException(step.name(), failure);
        }
    }

    private OrganizationProvisionStep loadStepRow(UUID jobId, StepName name) {
        return stepRepository.findByJobIdOrderByStepOrder(jobId).stream()
            .filter(s -> s.getStepName() == name)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No step row for job " + jobId + " and step " + name
                    + "; job was not pre-seeded correctly."));
    }

    private void markInProgress(OrganizationProvisionStep row, Instant started) {
        row.setStatus(StepStatus.IN_PROGRESS);
        row.setStartedAt(started);
        stepRepository.save(row);
    }

    private void markSuccess(OrganizationProvisionStep row, Instant started) {
        Instant finished = Instant.now();
        row.setStatus(StepStatus.SUCCESS);
        row.setFinishedAt(finished);
        row.setDurationMs(finished.toEpochMilli() - started.toEpochMilli());
        stepRepository.save(row);
    }

    private void markFailed(OrganizationProvisionStep row, Instant started, Throwable cause) {
        Instant finished = Instant.now();
        StepFailure failure = failureTranslator.translate(cause);
        row.setStatus(StepStatus.FAILED);
        row.setFinishedAt(finished);
        row.setDurationMs(finished.toEpochMilli() - started.toEpochMilli());
        row.setErrorCode(failure.errorCode());
        row.setErrorMessage(failure.errorMessage());
        stepRepository.save(row);
    }
}
