package com.example.provisioning.api;

import com.example.provisioning.api.dto.ProvisionJobResponse;
import com.example.provisioning.workflow.query.ProvisionJobQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/organization-provision-jobs")
@RequiredArgsConstructor
public class ProvisionJobController {

    private final ProvisionJobQueryService queryService;

    @GetMapping("/{jobId}")
    public ProvisionJobResponse getJob(@PathVariable UUID jobId) {
        return queryService.fetch(jobId);
    }
}
