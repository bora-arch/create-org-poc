package com.example.provisioning.workflow.steps;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.external.ExternalOrganizationClient;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CreateOrganizationStep implements ProvisionStep {

    private final ExternalOrganizationClient client;

    @Override
    public StepName name() {
        return StepName.CREATE_ORG;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void execute(ProvisionContext context) {
        UUID organizationId = client.createOrganization(context.getOrganizationName());
        context.setOrganizationId(organizationId);
    }
}
