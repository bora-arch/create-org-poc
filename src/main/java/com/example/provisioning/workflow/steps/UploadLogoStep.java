package com.example.provisioning.workflow.steps;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.external.ExternalOrganizationClient;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UploadLogoStep implements ProvisionStep {

    private final ExternalOrganizationClient client;

    @Override
    public StepName name() {
        return StepName.UPLOAD_LOGO;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public void execute(ProvisionContext context) {
        String logoUrl = client.uploadLogo(context.getOrganizationId());
        context.put(ContextKeys.LOGO_URL, logoUrl);
    }
}
