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
 *
 * <p>Whether a step runs at all for a given job is decided entirely
 * by the request's {@code source} — see
 * {@code SourceStepConfigProvider} / {@code source_config.json}. A
 * step selected by {@code source} always executes; there is no
 * further per-step business gate.
 */
public interface ProvisionStep {

    StepName name();

    int order();

    void execute(ProvisionContext context);
}
