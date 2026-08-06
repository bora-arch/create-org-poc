package com.example.provisioning.workflow.steps;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.external.ExternalOrganizationClient;
import com.example.provisioning.workflow.engine.DefaultConfigProvider;
import com.example.provisioning.workflow.spi.ConfigSections;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Mutually exclusive with {@link EnablePrmLicensesStep} — see that
 * class for why {@code org_type} gating is read locally here instead
 * of through a centrally-computed context field.
 */
@Component
@RequiredArgsConstructor
public class DisablePrmLicensesStep implements ProvisionStep {

    private final ExternalOrganizationClient client;
    private final DefaultConfigProvider defaultConfigProvider;

    @Override
    public StepName name() {
        return StepName.DISABLE_PRM_LICENSES;
    }

    @Override
    public int order() {
        return 110;
    }

    @Override
    public boolean shouldRun(ProvisionContext context) {
        return !defaultConfigProvider.sectionsFor(context.getOrgType()).contains(ConfigSections.LICENSE);
    }

    @Override
    public void execute(ProvisionContext context) {
        client.disablePrmLicenses(context.getOrganizationId());
    }
}
