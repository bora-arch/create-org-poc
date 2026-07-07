package com.example.provisioning.workflow.spi;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mutable, per-job carrier passed from step to step. Not thread-safe
 * by design: a workflow instance is processed by a single thread.
 *
 * <p>Steps read the request context (jobId, organization name, actor)
 * and read/write typed attributes to hand data downstream — for
 * example, {@code UploadLogoStep} publishes the logo URL that
 * {@code ConfigureBrandingStep} later consumes.
 */
@Getter
@RequiredArgsConstructor
public class ProvisionContext {

    private final UUID jobId;
    private final String organizationName;
    private final String createdBy;
    private final Map<String, Object> attributes = new HashMap<>();

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
