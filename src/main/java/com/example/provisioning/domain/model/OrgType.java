package com.example.provisioning.domain.model;

/**
 * Organization tier. Selects which default-configuration profile
 * (see {@code default_config.json}) applies to a provisioning job,
 * which in turn decides which steps run and which are
 * {@link StepStatus#SKIPPED}.
 */
public enum OrgType {
    STANDARD,
    INTERNAL,
    ENTERPRISE
}
