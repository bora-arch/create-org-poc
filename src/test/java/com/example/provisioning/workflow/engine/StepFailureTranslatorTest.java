package com.example.provisioning.workflow.engine;

import com.example.provisioning.external.ExternalCallException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepFailureTranslatorTest {

    private final StepFailureTranslator translator = new StepFailureTranslator();

    @Test
    void externalCallExceptionSurfacesCodeAndMessage() {
        StepFailure failure = translator.translate(new ExternalCallException("401", "Unauthorized"));

        assertThat(failure.errorCode()).isEqualTo("401");
        assertThat(failure.errorMessage()).isEqualTo("Unauthorized");
    }

    @Test
    void unknownExceptionCollapsedToInternalError() {
        StepFailure failure = translator.translate(new IllegalArgumentException("boom"));

        assertThat(failure.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(failure.errorMessage()).isEqualTo("boom");
    }

    @Test
    void nullMessageFallsBackToExceptionClassName() {
        StepFailure failure = translator.translate(new NullPointerException());

        assertThat(failure.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(failure.errorMessage()).isEqualTo("NullPointerException");
    }
}
