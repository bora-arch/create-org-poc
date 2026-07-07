package com.example.provisioning.workflow.steps;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.external.ExternalOrganizationClient;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EnablePrmLicensesStep implements ProvisionStep {

    private final ExternalOrganizationClient client;

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
        return context.isPrmLicensesEnabled();
    }

    @Override
    public void execute(ProvisionContext context) {
        client.enablePrmLicenses(context.getOrganizationId());
    }
}
