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
     * and their {@link #execute(ProvisionContext)} is never called.
     *
     * <p>The common source of truth is the org type's config profile:
     * a step runs only if its section is enabled, e.g.
     * {@code context.hasSection(ConfigSections.LICENSE)}. Mutually
     * exclusive steps invert the check (enable if the license section
     * is present, disable if it is absent).
     *
     * <p>Defaults to {@code true}: unless a step opts out, it always runs.
     */
    default boolean shouldRun(ProvisionContext context) {
        return true;
    }

    /**
     * Whether a failure of this step must halt the whole workflow.
     *
     * <p>Steps run as a chain: by default a step that fails is recorded
     * {@link com.example.provisioning.domain.model.StepStatus#FAILED} and
     * the orchestrator <em>continues</em> with the remaining steps, ending
     * the job {@link com.example.provisioning.domain.model.WorkflowStatus#COMPLETED_WITH_ERRORS}
     * rather than aborting. This suits best-effort setup steps whose
     * failure degrades but does not invalidate the provision.
     *
     * <p>A step returns {@code true} only when later steps genuinely
     * cannot proceed without it — a hard prerequisite. The canonical
     * example is {@code CREATE_ORG_IN_FSP}, which produces the
     * {@code organizationId} that every downstream call consumes: if it
     * fails there is nothing to configure, so the chain halts and the job
     * is {@link com.example.provisioning.domain.model.WorkflowStatus#FAILED},
     * leaving the remaining steps {@code NOT_STARTED}.
     *
     * <p>Defaults to {@code false}: unless a step declares itself critical,
     * its failure is non-blocking.
     */
    default boolean critical() {
        return false;
    }
}
