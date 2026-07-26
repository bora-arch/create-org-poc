package com.example.provisioning.workflow.steps;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.external.ExternalOrganizationClient;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateOrgInFspStep implements ProvisionStep {

    private final ExternalOrganizationClient client;

    @Override
    public StepName name() {
        return StepName.CREATE_ORG_IN_FSP;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void execute(ProvisionContext context) {
        client.createOrgInFsp(context.getOrganizationId());
    }
}
