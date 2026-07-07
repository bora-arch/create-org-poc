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
}
