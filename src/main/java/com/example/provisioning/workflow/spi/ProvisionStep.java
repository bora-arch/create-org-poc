package com.example.provisioning.workflow.spi;

import com.example.provisioning.domain.model.StepName;

/**
 * Contract every provisioning step implements. Steps are discovered
 * automatically as Spring beans; the orchestrator never references
 * concrete classes.
 *
 * <p>Ordering is declared explicitly via {@link #order()} — steps
 * return values like 10, 20, 30 (gapped so insertions do not force a
 * rewrite). Uniqueness is enforced at startup by the step registry.
 * When retry support lands, a {@code RetryPolicy retryPolicy()} default
 * method fits naturally on this interface without touching the engine.
 */
public interface ProvisionStep {

    StepName name();

    int order();

    void execute(ProvisionContext context);

    /**
     * Business gate deciding whether this step applies to the current
     * job. Steps that return {@code false} are recorded as
     * {@link com.example.provisioning.domain.model.StepStatus#SKIPPED}
     * and their {@link #execute(ProvisionContext)} is never called —
     * e.g. mutually exclusive steps like enabling vs. disabling PRM
     * licenses, or features that only apply to certain org tiers.
     *
     * <p>Defaults to {@code true}: unless a step opts out, it always runs.
     */
    default boolean shouldRun(ProvisionContext context) {
        return true;
    }
}
