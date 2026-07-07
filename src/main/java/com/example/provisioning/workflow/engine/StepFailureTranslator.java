package com.example.provisioning.workflow.engine;

import com.example.provisioning.external.ExternalCallException;
import org.springframework.stereotype.Component;

/**
 * Isolates the "raw exception → persisted error model" mapping so
 * {@link StepExecutor} stays focused on persistence. When retry lands
 * this is the single place that will classify failures as
 * retryable/non-retryable.
 */
@Component
public class StepFailureTranslator {

    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public StepFailure translate(Throwable throwable) {
        if (throwable instanceof ExternalCallException ex) {
            return new StepFailure(ex.getErrorCode(), truncate(ex.getMessage()));
        }
        String message = throwable.getMessage();
        return new StepFailure(INTERNAL_ERROR,
            truncate(message != null ? message : throwable.getClass().getSimpleName()));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1024 ? value.substring(0, 1024) : value;
    }
}
