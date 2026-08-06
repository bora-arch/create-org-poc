package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.OrgType;
import com.example.provisioning.workflow.spi.ConfigSections;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultConfigProviderTest {

    private final DefaultConfigProvider provider = new DefaultConfigProvider(new ObjectMapper());

    @Test
    void standardEnablesLicense() {
        assertThat(provider.sectionsFor(OrgType.STANDARD))
            .containsExactly(ConfigSections.LICENSE);
    }

    @Test
    void internalEnablesNoSections() {
        assertThat(provider.sectionsFor(OrgType.INTERNAL)).isEmpty();
    }

    @Test
    void enterpriseEnablesLicense() {
        assertThat(provider.sectionsFor(OrgType.ENTERPRISE))
            .containsExactly(ConfigSections.LICENSE);
    }
}
