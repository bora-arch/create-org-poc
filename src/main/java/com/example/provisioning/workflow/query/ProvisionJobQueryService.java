package com.example.provisioning.workflow.query;

import com.example.provisioning.api.dto.ProvisionJobResponse;
import com.example.provisioning.api.dto.ProvisionStepResponse;
import com.example.provisioning.api.error.JobNotFoundException;
import com.example.provisioning.domain.model.OrganizationProvisionJob;
import com.example.provisioning.domain.model.OrganizationProvisionStep;
import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.domain.model.StepStatus;
import com.example.provisioning.domain.model.WorkflowStatus;
import com.example.provisioning.domain.repository.StepRepository;
import com.example.provisioning.domain.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read model for the GET endpoint. Kept separate from the orchestrator
 * so read logic can evolve (projections, caching, DTO assembly)
 * without touching write flows.
 */
@Service
@RequiredArgsConstructor
public class ProvisionJobQueryService {

    private final WorkflowRepository workflowRepository;
    private final StepRepository stepRepository;

    @Transactional(readOnly = true)
    public ProvisionJobResponse fetch(UUID jobId) {
        OrganizationProvisionJob job = workflowRepository.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));

        List<OrganizationProvisionStep> stepRows = stepRepository.findByJobIdOrderByStepOrder(jobId);
        List<ProvisionStepResponse> stepDtos = stepRows.stream()
            .map(this::toDto)
            .toList();

        StepName currentStep = resolveCurrentStep(job, stepRows);
        int progress = countAdvancedSteps(stepRows);

        return new ProvisionJobResponse(
            job.getId(),
            job.getOrgUid(),
            job.getExternalJobUid(),
            job.getSource(),
            job.getStatus(),
            currentStep,
            progress,
            stepRows.size(),
            stepDtos
        );
    }

    private ProvisionStepResponse toDto(OrganizationProvisionStep row) {
        return new ProvisionStepResponse(
            row.getStepName(),
            row.getStatus(),
            row.getErrorCode(),
            row.getErrorMessage()
        );
    }

    /**
     * Prefer the persisted {@code job.currentStep} pointer (kept live
     * by {@link com.example.provisioning.workflow.engine.JobStateWriter}).
     * If it is null (job just created and not yet started), derive it
     * from step rows so the response is never inconsistent with the
     * status.
     */
    private StepName resolveCurrentStep(OrganizationProvisionJob job,
                                        List<OrganizationProvisionStep> steps) {
        if (job.getCurrentStep() != null) {
            return job.getCurrentStep();
        }
        if (job.getStatus() == WorkflowStatus.PENDING || steps.isEmpty()) {
            return null;
        }
        return steps.get(0).getStepName();
    }

    private int countAdvancedSteps(List<OrganizationProvisionStep> steps) {
        return (int) steps.stream()
            .filter(s -> s.getStatus() != StepStatus.NOT_STARTED)
            .filter(s -> s.getStatus() != StepStatus.IN_PROGRESS)
            .count();
    }
}
