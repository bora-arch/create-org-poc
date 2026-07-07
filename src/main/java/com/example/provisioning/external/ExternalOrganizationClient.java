package com.example.provisioning.external;

import java.util.UUID;

/**
 * SPI for the downstream organization platform (FSP / CE / PRM / RFS
 * subsystems). Real implementations would talk to one or more remote
 * services; the POC ships a mock that fails a random call to exercise
 * the failure path.
 */
public interface ExternalOrganizationClient {

    UUID createOrgInFsp(String name);

    void setupOrgInFsp(UUID organizationId);

    void assignRecommendationModels(UUID organizationId);

    void setupDefaultBrandingPreferences(UUID organizationId);

    void setupDefaultPrmPreferences(UUID organizationId);

    void setupRfsUiPreferences(UUID organizationId);

    void setupDefaultVocabulariesInCe(UUID organizationId);

    void setupDefaultDatasources(UUID organizationId);

    void setupDefaultCitationStyles(UUID organizationId);

    void enablePrmLicenses(UUID organizationId);

    void disablePrmLicenses(UUID organizationId);

    void setupFspBoosters(UUID organizationId);
}
