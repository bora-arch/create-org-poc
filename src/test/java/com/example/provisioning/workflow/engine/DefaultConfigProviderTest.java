package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.OrgType;
import com.example.provisioning.workflow.spi.ConfigSections;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultConfigProviderTest {

    private final DefaultConfigProvider provider = new DefaultConfigProvider(new ObjectMapper());

    @Test
    void standardEnablesBrandingRfsCitationsAndLicense() {
        assertThat(provider.sectionsFor(OrgType.STANDARD))
            .containsExactlyInAnyOrder(
                ConfigSections.BRANDING,
                ConfigSections.RFS_UI_PRM_PREFERENCES,
                ConfigSections.CITATIONS,
                ConfigSections.LICENSE);
    }

    @Test
    void internalEnablesNoOptionalSections() {
        assertThat(provider.sectionsFor(OrgType.INTERNAL)).isEmpty();
    }

    @Test
    void enterpriseEnablesEverythingIncludingRecommendationsAndBoosters() {
        assertThat(provider.sectionsFor(OrgType.ENTERPRISE))
            .contains(ConfigSections.RECOMMENDATION_MODELS, ConfigSections.BOOSTERS,
                ConfigSections.LICENSE)
            .doesNotContain("nonexistent");
    }
}
