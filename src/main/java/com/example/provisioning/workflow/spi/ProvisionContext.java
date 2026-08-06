package com.example.provisioning.workflow.spi;

import com.example.provisioning.domain.model.OrgType;
import com.example.provisioning.domain.model.StepName;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable, per-job carrier passed from step to step. Not thread-safe
 * by design: a workflow instance is processed by a single thread.
 *
 * <p>Steps read the request context (jobId, raw request fields, actor)
 * and read/write typed attributes to hand data downstream — an earlier
 * step publishes a value under a key that a later step consumes.
 *
 * <p>{@code rawOrgUid} / {@code rawOrgType} / {@code rawSource} are the
 * unvalidated strings from the request body. {@link com.example.provisioning.workflow.steps.InitialRequestValidationStep}
 * — always the first step — parses and validates them, then calls
 * {@link #markValidated} to publish the resolved {@code organizationId} /
 * {@code orgType} / {@code enabledSteps}. Only {@code enabledSteps} is
 * a general orchestration concern — a step not selected by the
 * request's {@code source} is skipped ({@link #isStepEnabledForSource(StepName)}).
 * {@code orgType} itself is exposed for the rare step whose own
 * {@code shouldRun} needs it (see {@code EnablePrmLicensesStep}) — it
 * reads {@code DefaultConfigProvider} directly rather than this class
 * exposing a generic, centrally-computed "enabled sections" concept
 * that most steps never use.
 */
@Getter
public class ProvisionContext {

    private final UUID jobId;
    private final String rawOrgUid;
    private final String rawOrgType;
    private final String rawSource;
    private final String serviceUserAccount;
    private final String externalJobUid;
    private final Map<String, Object> attributes = new HashMap<>();

    private UUID organizationId;
    private OrgType orgType;
    private Set<StepName> enabledSteps = Set.of();

    public ProvisionContext(UUID jobId, String rawOrgUid, String rawOrgType, String rawSource,
                            String serviceUserAccount, String externalJobUid) {
        this.jobId = jobId;
        this.rawOrgUid = rawOrgUid;
        this.rawOrgType = rawOrgType;
        this.rawSource = rawSource;
        this.serviceUserAccount = serviceUserAccount;
        this.externalJobUid = externalJobUid;
    }

    /** True if the request's {@code source} selects this step to run at all. */
    public boolean isStepEnabledForSource(StepName step) {
        return enabledSteps.contains(step);
    }

    /**
     * Published once by {@link com.example.provisioning.workflow.steps.InitialRequestValidationStep}
     * after it successfully parses {@code rawOrgUid} / {@code rawOrgType} /
     * {@code rawSource}. On a resumed run (validation already succeeded
     * in a prior attempt) the orchestrator calls this directly with the
     * persisted values instead of re-running the step.
     */
    public void markValidated(UUID organizationId, OrgType orgType, Set<StepName> enabledSteps) {
        this.organizationId = organizationId;
        this.orgType = orgType;
        this.enabledSteps = enabledSteps;
    }

    public <T> void put(String key, T value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException(
                "Attribute '" + key + "' is a " + value.getClass().getSimpleName()
                    + ", expected " + type.getSimpleName());
        }
        return Optional.of((T) value);
    }
}
