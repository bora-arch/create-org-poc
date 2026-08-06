package com.example.provisioning.workflow.spi;

import com.example.provisioning.domain.model.OrgType;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
 * — always the first step, now run asynchronously like every other step
 * — parses and validates them, then calls {@link #markValidated} to
 * publish the resolved {@code organizationId} / {@code orgType}. Which
 * steps actually get a row (and therefore run) for a job is decided
 * once, at job creation, straight from the raw {@code source} string —
 * see {@code ProvisionWorkflowService#createJob} — so the orchestrator
 * doesn't need anything from this context to know which steps to skip.
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

    public ProvisionContext(UUID jobId, String rawOrgUid, String rawOrgType, String rawSource,
                            String serviceUserAccount, String externalJobUid) {
        this.jobId = jobId;
        this.rawOrgUid = rawOrgUid;
        this.rawOrgType = rawOrgType;
        this.rawSource = rawSource;
        this.serviceUserAccount = serviceUserAccount;
        this.externalJobUid = externalJobUid;
    }

    /**
     * Published once by {@link com.example.provisioning.workflow.steps.InitialRequestValidationStep}
     * after it successfully parses {@code rawOrgUid} / {@code rawOrgType}.
     * On a resumed run (validation already succeeded in a prior attempt)
     * the orchestrator calls this directly with the persisted values
     * instead of re-running the step.
     */
    public void markValidated(UUID organizationId, OrgType orgType) {
        this.organizationId = organizationId;
        this.orgType = orgType;
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
