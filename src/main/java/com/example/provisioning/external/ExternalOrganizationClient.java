package com.example.provisioning.external;

import java.util.UUID;

/**
 * SPI for the downstream organization platform. Real implementations
 * would talk to one or more remote services (FSP, PRM, CE, …); the POC
 * ships a mock that fails a random call to exercise the failure path.
 */
public interface ExternalOrganizationClient {

    void createOrgInFsp(UUID orgUid);

    void setupOrgInFsp(UUID organizationId);

    void assignFspRecommendationModels(UUID organizationId);

    void setupDefaultBrandingPrmPreferences(UUID organizationId);

    void setupDefaultPrmPreferences(UUID organizationId);

    void setupDefaultRfsUiPrmPreferences(UUID organizationId);

    void setupDefaultVocabulariesInCe(UUID organizationId);

    void setupDefaultDatasourcesInFsp(UUID organizationId);

    void setupDefaultCitations(UUID organizationId);

    void enablePrmLicenses(UUID organizationId);

    void disablePrmLicenses(UUID organizationId);

    void setupFspBoosters(UUID organizationId);
}
