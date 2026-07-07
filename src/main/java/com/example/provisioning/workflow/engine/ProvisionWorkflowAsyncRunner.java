package com.example.provisioning.workflow.engine;

import com.example.provisioning.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin async wrapper that hands job execution off to the provisioning
 * thread pool. Kept as a separate bean (rather than an {@code @Async}
 * method on the orchestrator itself) so the Spring proxy is engaged —
 * self-invocation would silently bypass it.
 *
 * <p>Only primitive/immutable arguments cross the async boundary: no
 * JPA entities, which would be detached in the target thread.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProvisionWorkflowAsyncRunner {

    private final ProvisionWorkflowService workflowService;

    @Async(AsyncConfig.PROVISIONING_EXECUTOR)
    public void run(UUID jobId, String organizationName, String createdBy,
                    boolean prmLicensesEnabled) {
        try {
            workflowService.execute(jobId, organizationName, createdBy, prmLicensesEnabled);
        } catch (RuntimeException ex) {
            log.error("Provisioning job {} failed catastrophically", jobId, ex);
        }
    }
}
