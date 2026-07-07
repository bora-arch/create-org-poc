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
    public UUID createOrganization(String name) {
        maybeFail("createOrganization");
        UUID id = UUID.randomUUID();
        log.debug("createOrganization name={} -> id={}", name, id);
        return id;
    }

    @Override
    public void setupOrganization(UUID organizationId) {
        maybeFail("setupOrganization");
        log.debug("setupOrganization id={}", organizationId);
    }

    @Override
    public String uploadLogo(UUID organizationId) {
        maybeFail("uploadLogo");
        String url = "https://cdn.example.com/logos/" + organizationId + ".png";
        log.debug("uploadLogo id={} -> url={}", organizationId, url);
        return url;
    }

    @Override
    public void configureVocabulary(UUID organizationId) {
        maybeFail("configureVocabulary");
        log.debug("configureVocabulary id={}", organizationId);
    }

    @Override
    public void configureUsers(UUID organizationId) {
        maybeFail("configureUsers");
        log.debug("configureUsers id={}", organizationId);
    }

    @Override
    public void configurePermissions(UUID organizationId) {
        maybeFail("configurePermissions");
        log.debug("configurePermissions id={}", organizationId);
    }

    @Override
    public void configureBranding(UUID organizationId, String logoUrl) {
        maybeFail("configureBranding");
        log.debug("configureBranding id={} logoUrl={}", organizationId, logoUrl);
    }

    @Override
    public void validate(UUID organizationId) {
        maybeFail("validate");
        log.debug("validate id={}", organizationId);
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
