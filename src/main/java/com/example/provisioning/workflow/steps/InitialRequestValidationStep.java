package com.example.provisioning.workflow.steps;

import com.example.provisioning.domain.model.OrgType;
import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.workflow.engine.DefaultConfigProvider;
import com.example.provisioning.workflow.engine.RequestValidationException;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Always the first step. Parses and validates the request's raw
 * {@code org_uid} / {@code org_type} / {@code service_user_account}
 * (structural presence is already enforced by bean validation on
 * {@link com.example.provisioning.api.dto.CreateOrganizationRequest};
 * this step checks the values are well-formed).
 *
 * <p>On success it publishes the resolved organization id, org type,
 * and enabled config sections onto the {@link ProvisionContext} via
 * {@link ProvisionContext#markValidated} — every later step relies on
 * these. On failure it throws {@link RequestValidationException},
 * which {@link com.example.provisioning.workflow.engine.StepFailureTranslator}
 * maps to a {@code VALIDATION_FAILED} step error; the orchestrator's
 * fail-fast halt then prevents any of the other steps from running.
 */
@Component
@RequiredArgsConstructor
public class InitialRequestValidationStep implements ProvisionStep {

    /** Declared order — always the lowest, so this step runs first. */
    public static final int ORDER = 0;

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final DefaultConfigProvider defaultConfigProvider;

    @Override
    public StepName name() {
        return StepName.INITIAL_REQUEST_VALIDATION;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void execute(ProvisionContext context) {
        List<String> violations = new ArrayList<>();

        UUID orgUid = parseOrgUid(context.getRawOrgUid(), violations);
        OrgType orgType = resolveOrgType(context.getRawOrgType(), violations);
        if (!isValidEmail(context.getServiceUserAccount())) {
            violations.add("service_user_account must be a valid email address");
        }

        if (!violations.isEmpty()) {
            throw new RequestValidationException(String.join("; ", violations));
        }

        Set<String> enabledSections = defaultConfigProvider.sectionsFor(orgType);
        context.markValidated(orgUid, orgType, enabledSections);
    }

    private UUID parseOrgUid(String rawOrgUid, List<String> violations) {
        try {
            return UUID.fromString(rawOrgUid);
        } catch (IllegalArgumentException e) {
            violations.add("org_uid must be a valid UUID");
            return null;
        }
    }

    private OrgType resolveOrgType(String rawOrgType, List<String> violations) {
        OrgType resolved = mapOrgType(rawOrgType);
        if (resolved == null) {
            violations.add("org_type must be one of: base, internal, enterprise");
        }
        return resolved;
    }

    private OrgType mapOrgType(String rawOrgType) {
        if (rawOrgType == null) {
            return null;
        }
        String normalized = rawOrgType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "base", "standard" -> OrgType.STANDARD;
            case "internal" -> OrgType.INTERNAL;
            case "enterprise" -> OrgType.ENTERPRISE;
            default -> null;
        };
    }

    private boolean isValidEmail(String value) {
        return value != null && EMAIL_PATTERN.matcher(value).matches();
    }
}
