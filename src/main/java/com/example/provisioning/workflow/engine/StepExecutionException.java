package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.StepName;
import lombok.Getter;

/**
 * Raised by {@link StepExecutor} when a step's persistence has already
 * been marked FAILED. Halts the orchestrator without duplicating the
 * error-capture logic.
 */
@Getter
public class StepExecutionException extends RuntimeException {

    private final StepName stepName;

    public StepExecutionException(StepName stepName, Throwable cause) {
        super("Step " + stepName + " failed: " + cause.getMessage(), cause);
        this.stepName = stepName;
    }
}
