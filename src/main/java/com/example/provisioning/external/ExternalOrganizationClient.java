package com.example.provisioning.external;

import java.util.UUID;

/**
 * SPI for the downstream organization platform. Real implementations
 * would talk to one or more remote services; the POC ships a mock that
 * fails a random call to exercise the failure path.
 */
public interface ExternalOrganizationClient {

    UUID createOrganization(String name);

    void setupOrganization(UUID organizationId);

    String uploadLogo(UUID organizationId);

    void configureVocabulary(UUID organizationId);

    void configureUsers(UUID organizationId);

    void configurePermissions(UUID organizationId);

    void configureBranding(UUID organizationId, String logoUrl);

    void validate(UUID organizationId);
}
