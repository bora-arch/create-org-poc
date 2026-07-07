package com.example.provisioning.api;

import com.example.provisioning.api.dto.CreateOrganizationRequest;
import com.example.provisioning.api.dto.CreateOrganizationResponse;
import com.example.provisioning.domain.model.OrganizationProvisionJob;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.example.provisioning.workflow.engine.ProvisionWorkflowAsyncRunner;
import com.example.provisioning.workflow.engine.ProvisionWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final ProvisionWorkflowService workflowService;
    private final ProvisionWorkflowAsyncRunner asyncRunner;

    @PostMapping
    public ResponseEntity<CreateOrganizationResponse> createOrganization(
        @Valid @RequestBody CreateOrganizationRequest request
    ) {
        OrganizationProvisionJob job = workflowService.createJob(
            request.name(), request.createdBy(), request.orgTypeOrDefault());
        asyncRunner.run(job.getId(), request.name(), request.createdBy(),
            request.orgTypeOrDefault());
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(new CreateOrganizationResponse(job.getId(), WorkflowStatus.IN_PROGRESS));
    }
}
