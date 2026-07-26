package com.example.provisioning.workflow.spi;

import com.example.provisioning.domain.model.OrgType;
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
 * <p>{@code rawOrgUid} / {@code rawOrgType} are the unvalidated strings
 * from the request body. {@link com.example.provisioning.workflow.steps.InitialRequestValidationStep}
 * — always the first step — parses and validates them, then calls
 * {@link #markValidated} to publish the resolved {@code organizationId} /
 * {@code orgType} / {@code enabledSections} that every later step relies
 * on via {@link #hasSection(String)}. A step whose section is absent for
 * the org type is skipped.
 */
@Getter
public class ProvisionContext {

    private final UUID jobId;
    private final String rawOrgUid;
    private final String rawOrgType;
    private final String serviceUserAccount;
    private final String externalJobUid;
    private final Map<String, Object> attributes = new HashMap<>();

    private UUID organizationId;
    private OrgType orgType;
    private Set<String> enabledSections = Set.of();

    public ProvisionContext(UUID jobId, String rawOrgUid, String rawOrgType,
                            String serviceUserAccount, String externalJobUid) {
        this.jobId = jobId;
        this.rawOrgUid = rawOrgUid;
        this.rawOrgType = rawOrgType;
        this.serviceUserAccount = serviceUserAccount;
        this.externalJobUid = externalJobUid;
    }

    /** True if {@code section} is enabled for this job's org type. */
    public boolean hasSection(String section) {
        return enabledSections.contains(section);
    }

    /**
     * Published once by {@link com.example.provisioning.workflow.steps.InitialRequestValidationStep}
     * after it successfully parses {@code rawOrgUid} / {@code rawOrgType}.
     * On a resumed run (validation already succeeded in a prior attempt)
     * the orchestrator calls this directly with the persisted values
     * instead of re-running the step.
     */
    public void markValidated(UUID organizationId, OrgType orgType, Set<String> enabledSections) {
        this.organizationId = organizationId;
        this.orgType = orgType;
        this.enabledSections = enabledSections;
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
