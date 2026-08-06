package com.example.provisioning.workflow.steps;

import com.example.provisioning.domain.model.OrgType;
import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.workflow.engine.RequestValidationException;
import com.example.provisioning.workflow.engine.SourceStepConfigProvider;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Always the first step, and — like every other step — executed
 * asynchronously by the normal orchestrator loop; there is no separate
 * synchronous validation path. Parses and validates the request's raw
 * {@code org_uid} / {@code org_type} / {@code source} / {@code service_user_account}
 * (structural presence is already enforced by bean validation on
 * {@link com.example.provisioning.api.dto.CreateOrganizationRequest};
 * this step checks the values are well-formed).
 *
 * <p>Which steps have a row for this job at all — and therefore run —
 * was already decided at job creation, straight from the raw
 * {@code source} string (see {@code ProvisionWorkflowService#createJob}),
 * so this step does not need to resolve {@code source} into a step set;
 * it only needs to confirm {@code source} is one of the known values.
 *
 * <p>On success it publishes the resolved organization id and org type
 * onto the {@link ProvisionContext} via {@link ProvisionContext#markValidated} —
 * every later step relies on these. On failure it throws
 * {@link RequestValidationException}, which
 * {@link com.example.provisioning.workflow.engine.StepFailureTranslator}
 * maps to a {@code VALIDATION_FAILED} step error; the orchestrator's
 * fail-fast halt then prevents any of the other steps from running,
 * leaving them at their pre-seeded {@code NOT_STARTED}.
 */
@Component
@RequiredArgsConstructor
public class InitialRequestValidationStep implements ProvisionStep {

    /** Declared order — always the lowest, so this step runs first. */
    public static final int ORDER = 0;

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final SourceStepConfigProvider sourceStepConfigProvider;

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
        validateSource(context.getRawSource(), violations);
        if (!isValidEmail(context.getServiceUserAccount())) {
            violations.add("service_user_account must be a valid email address");
        }

        if (!violations.isEmpty()) {
            throw new RequestValidationException(String.join("; ", violations));
        }

        context.markValidated(orgUid, orgType);
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
        try {
            return OrgType.valueOf(rawOrgType);
        } catch (IllegalArgumentException e) {
            violations.add("org_type must be one of: STANDARD, INTERNAL, ENTERPRISE");
            return null;
        }
    }

    private void validateSource(String rawSource, List<String> violations) {
        if (!sourceStepConfigProvider.isKnownSource(rawSource)) {
            violations.add("source must be one of: "
                + String.join(", ", sourceStepConfigProvider.knownSources()));
        }
    }

    private boolean isValidEmail(String value) {
        return value != null && EMAIL_PATTERN.matcher(value).matches();
    }
}
