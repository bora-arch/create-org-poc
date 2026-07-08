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
@Tag(name = "Provision jobs", description = "Read workflow / step state")
public class ProvisionJobController {

    private final ProvisionJobQueryService queryService;

    @GetMapping("/{jobId}")
    @Operation(
        summary = "Get provisioning job state",
        description = "Returns the full workflow snapshot: overall status, the current step "
            + "pointer, numeric progress, total step count, and every step with its status "
            + "(and error info for a failed step).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Current workflow state"),
        @ApiResponse(responseCode = "404", description = "No job with the given id",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ProvisionJobResponse getJob(
        @Parameter(description = "Job id returned by POST /organizations")
        @PathVariable UUID jobId
    ) {
        return queryService.fetch(jobId);
    }
}
