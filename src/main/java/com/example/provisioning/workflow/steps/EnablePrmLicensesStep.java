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
 * One of the only two steps that still care about {@code org_type}
 * (the other being {@link DisablePrmLicensesStep}, mutually
 * exclusive with this one). Every other step is unconditional —
 * {@code org_type}-based gating was deliberately not made a
 * general-purpose, centrally-computed concern; a step that actually
 * needs it reads {@link DefaultConfigProvider} directly, right here.
 */
@Component
@RequiredArgsConstructor
public class EnablePrmLicensesStep implements ProvisionStep {

    private final ExternalOrganizationClient client;
    private final DefaultConfigProvider defaultConfigProvider;

    @Override
    public StepName name() {
        return StepName.ENABLE_PRM_LICENSES;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public boolean shouldRun(ProvisionContext context) {
        return defaultConfigProvider.sectionsFor(context.getOrgType()).contains(ConfigSections.LICENSE);
    }

    @Override
    public void execute(ProvisionContext context) {
        client.enablePrmLicenses(context.getOrganizationId());
    }
}
