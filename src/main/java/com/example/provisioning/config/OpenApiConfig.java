package com.example.provisioning.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Central OpenAPI (Swagger) document definition. Describes the
 * Organization Provisioning API at the document level — title, version,
 * description, contact, and the local server. Per-operation and per-schema
 * detail lives on the controllers and DTOs via {@code @Operation} /
 * {@code @Schema} annotations, which springdoc merges into this document.
 *
 * <p>The generated spec is served at {@code /v3/api-docs} (JSON) and
 * {@code /v3/api-docs.yaml}; Swagger UI is at {@code /swagger-ui.html}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI provisioningOpenAPI(
        @Value("${server.port:8080}") int serverPort,
        @Value("${spring.application.name:create-org-poc}") String applicationName
    ) {
        return new OpenAPI()
            .info(new Info()
                .title("Organization Provisioning API")
                .version("0.0.1")
                .description("""
                    Asynchronous, orchestrator-driven organization provisioning.

                    `POST /organizations` accepts a create/retry request, returns \
                    `202 Accepted` with a job id, and drives every step — including \
                    `INITIAL_REQUEST_VALIDATION` itself — asynchronously in the \
                    background; an invalid request only becomes visible as \
                    `status: FAILED` on a later poll, never in the POST response. \
                    `source` (e.g. `DEFAULT`, `ETL_JOB`, `ADMIN_APP`) is resolved \
                    from the raw request at job creation and determines every step \
                    the job will ever run (`source_config.json`) — a step outside \
                    that set never gets a row and never appears in any response for \
                    the job. `org_type` is validated and persisted but has no effect \
                    on which steps run. `org_uid` is the idempotency/retry key: \
                    resubmitting it resumes a previously failed job at its failed \
                    step (fail-fast — later steps never ran) instead of starting over. \
                    `GET /organization-provision-jobs/{jobId}` returns the full \
                    workflow state — per-step status, the current step, and \
                    progress — for a UI to poll and render.""")
                .contact(new Contact()
                    .name("create-org-poc")
                    .url("https://github.com/bora-arch/create-org-poc"))
                .license(new License()
                    .name("MIT")
                    .url("https://opensource.org/licenses/MIT")))
            .servers(List.of(
                new Server()
                    .url("http://localhost:" + serverPort)
                    .description("Local development server")));
    }
}
