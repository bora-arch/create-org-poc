package com.example.provisioning.domain.model;

/**
 * Organization tier. Validated by {@code INITIAL_REQUEST_VALIDATION}
 * and persisted onto the job, but has no effect on which steps run —
 * {@code source} is the only thing that gates step execution.
 */
public enum OrgType {
    STANDARD,
    INTERNAL,
    ENTERPRISE
}
