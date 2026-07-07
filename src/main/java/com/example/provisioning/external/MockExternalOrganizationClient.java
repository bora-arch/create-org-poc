package com.example.provisioning.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock external client that randomly fails one call per invocation.
 *
 * <p>Per-call randomness (rather than a fixed "unlucky method" chosen
 * at startup) gives a richer demo — successive POSTs may fail at
 * different steps, which is what an operator wants to see when
 * validating a UI that renders "completed / current / remaining".
 */
@Service
@Slf4j
public class MockExternalOrganizationClient implements ExternalOrganizationClient {

    private static final int FAILURE_PERCENT = 15;

    private static final List<Map.Entry<String, String>> FAILURES = List.of(
        Map.entry("401", "Unauthorized"),
        Map.entry("403", "Forbidden"),
        Map.entry("404", "Not Found"),
        Map.entry("409", "Conflict"),
        Map.entry("500", "Internal Server Error"),
        Map.entry("503", "Service Unavailable")
    );

    @Override
    public UUID createOrgInFsp(String name) {
        maybeFail("createOrgInFsp");
        UUID id = UUID.randomUUID();
        log.debug("createOrgInFsp name={} -> id={}", name, id);
        return id;
    }

    @Override
    public void setupOrgInFsp(UUID organizationId) {
        maybeFail("setupOrgInFsp");
        log.debug("setupOrgInFsp id={}", organizationId);
    }

    @Override
    public void assignFspRecommendationModels(UUID organizationId) {
        maybeFail("assignFspRecommendationModels");
        log.debug("assignFspRecommendationModels id={}", organizationId);
    }

    @Override
    public void setupDefaultBrandingPrmPreferences(UUID organizationId) {
        maybeFail("setupDefaultBrandingPrmPreferences");
        log.debug("setupDefaultBrandingPrmPreferences id={}", organizationId);
    }

    @Override
    public void setupDefaultPrmPreferences(UUID organizationId) {
        maybeFail("setupDefaultPrmPreferences");
        log.debug("setupDefaultPrmPreferences id={}", organizationId);
    }

    @Override
    public void setupDefaultRfsUiPrmPreferences(UUID organizationId) {
        maybeFail("setupDefaultRfsUiPrmPreferences");
        log.debug("setupDefaultRfsUiPrmPreferences id={}", organizationId);
    }

    @Override
    public void setupDefaultVocabulariesInCe(UUID organizationId) {
        maybeFail("setupDefaultVocabulariesInCe");
        log.debug("setupDefaultVocabulariesInCe id={}", organizationId);
    }

    @Override
    public void setupDefaultDatasourcesInFsp(UUID organizationId) {
        maybeFail("setupDefaultDatasourcesInFsp");
        log.debug("setupDefaultDatasourcesInFsp id={}", organizationId);
    }

    @Override
    public void setupDefaultCitations(UUID organizationId) {
        maybeFail("setupDefaultCitations");
        log.debug("setupDefaultCitations id={}", organizationId);
    }

    @Override
    public void enablePrmLicenses(UUID organizationId) {
        maybeFail("enablePrmLicenses");
        log.debug("enablePrmLicenses id={}", organizationId);
    }

    @Override
    public void disablePrmLicenses(UUID organizationId) {
        maybeFail("disablePrmLicenses");
        log.debug("disablePrmLicenses id={}", organizationId);
    }

    @Override
    public void setupFspBoosters(UUID organizationId) {
        maybeFail("setupFspBoosters");
        log.debug("setupFspBoosters id={}", organizationId);
    }

    private void maybeFail(String call) {
        if (ThreadLocalRandom.current().nextInt(100) < FAILURE_PERCENT) {
            Map.Entry<String, String> failure = FAILURES.get(
                ThreadLocalRandom.current().nextInt(FAILURES.size())
            );
            log.warn("mock {} failing with {} {}", call, failure.getKey(), failure.getValue());
            throw new ExternalCallException(failure.getKey(), failure.getValue());
        }
    }
}
