package com.example.provisioning.api;

import com.example.provisioning.api.dto.ProvisionJobResponse;
import com.example.provisioning.api.error.ApiErrorResponse;
import com.example.provisioning.workflow.query.ProvisionJobQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/organization-provision-jobs")
@RequiredArgsConstructor
@Tag(name = "Provision Jobs", description = "Poll the state of an organization provisioning workflow")
public class ProvisionJobController {

    private final ProvisionJobQueryService queryService;

    @Operation(
        summary = "Get provisioning job state",
        description = "Returns the full workflow state for a job — overall status, "
            + "the current step, progress, total steps, and the per-step list "
            + "(including SKIPPED steps that do not apply to the org tier).")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Current workflow state",
            content = @Content(schema = @Schema(implementation = ProvisionJobResponse.class))),
        @ApiResponse(
            responseCode = "404",
            description = "No provisioning job exists for the given id",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{jobId}")
    public ProvisionJobResponse getJob(
        @Parameter(description = "Provisioning job id returned by POST /organizations", required = true)
        @PathVariable UUID jobId
    ) {
        return queryService.fetch(jobId);
    }
}
