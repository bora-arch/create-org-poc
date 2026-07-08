package com.example.provisioning.domain.model;

public enum WorkflowStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,

    /**
     * Terminal. The chain ran to the end but at least one non-critical
     * step failed. Distinguishes a partially-degraded provision (org is
     * usable, some best-effort setup failed) from a hard {@link #FAILED}
     * (a critical step failed and the chain was halted).
     */
    COMPLETED_WITH_ERRORS,

    /**
     * Terminal. A {@code critical} step failed and the chain was halted;
     * steps after it are left {@link StepStatus#NOT_STARTED}.
     */
    FAILED
}
