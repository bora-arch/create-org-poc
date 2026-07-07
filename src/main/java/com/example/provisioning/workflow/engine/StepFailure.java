package com.example.provisioning.workflow.engine;

/**
 * Translator output: how a raised {@link Throwable} maps onto the
 * persisted step row's {@code errorCode} / {@code errorMessage}.
 */
public record StepFailure(String errorCode, String errorMessage) {
}
