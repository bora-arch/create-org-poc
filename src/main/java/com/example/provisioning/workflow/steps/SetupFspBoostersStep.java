package com.example.provisioning.workflow.steps;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.external.ExternalOrganizationClient;
import com.example.provisioning.workflow.spi.ConfigSections;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SetupFspBoostersStep implements ProvisionStep {

    private final ExternalOrganizationClient client;

    @Override
    public StepName name() {
        return StepName.SETUP_FSP_BOOSTERS;
    }

    @Override
    public int order() {
        return 120;
    }

    @Override
    public boolean shouldRun(ProvisionContext context) {
        return context.hasSection(ConfigSections.BOOSTERS);
    }

    @Override
    public void execute(ProvisionContext context) {
        client.setupFspBoosters(context.getOrganizationId());
    }
}
