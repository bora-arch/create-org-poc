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

    /**
     * Critical: produces the {@code organizationId} every later step
     * consumes. If it fails there is nothing to provision, so the chain
     * halts rather than cascading null-org failures.
     */
    @Override
    public boolean critical() {
        return true;
    }

    @Override
    public void execute(ProvisionContext context) {
        UUID organizationId = client.createOrgInFsp(context.getOrganizationName());
        context.setOrganizationId(organizationId);
    }
}
