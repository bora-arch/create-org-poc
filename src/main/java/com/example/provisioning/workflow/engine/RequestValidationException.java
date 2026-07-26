package com.example.provisioning.workflow.engine;

/**
 * Raised by {@link com.example.provisioning.workflow.steps.InitialRequestValidationStep}
 * when the incoming request's business fields (org_uid, org_type,
 * service_user_account) are structurally present but semantically
 * invalid. Translated by {@link StepFailureTranslator} into a
 * {@code VALIDATION_FAILED} step error, distinct from downstream
 * {@link com.example.provisioning.external.ExternalCallException}s.
 */
public class RequestValidationException extends RuntimeException {

    public RequestValidationException(String message) {
        super(message);
    }
}
