package com.example.provisioning.workflow.spi;

import com.example.provisioning.domain.model.OrgType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable, per-job carrier passed from step to step. Not thread-safe
 * by design: a workflow instance is processed by a single thread.
 *
 * <p>Steps read the request context (jobId, organization name, actor,
 * org type + its resolved config sections) and read/write typed
 * attributes to hand data downstream — an earlier step publishes a
 * value under a key that a later step consumes.
 *
 * <p>{@code orgType} and {@code enabledSections} come from the request
 * and {@code default_config.json}: steps consult
 * {@link #hasSection(String)} via {@link ProvisionStep#shouldRun} to
 * decide whether they apply. A step whose section is absent for the
 * org type is skipped.
 */
@Getter
@RequiredArgsConstructor
public class ProvisionContext {

    private final UUID jobId;
    private final String organizationName;
    private final String createdBy;
    private final OrgType orgType;
    private final Set<String> enabledSections;
    private final Map<String, Object> attributes = new HashMap<>();

    /** True if {@code section} is enabled for this job's org type. */
    public boolean hasSection(String section) {
        return enabledSections.contains(section);
    }

    private UUID organizationId;

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
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
