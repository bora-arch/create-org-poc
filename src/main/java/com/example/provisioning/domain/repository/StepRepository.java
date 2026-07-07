package com.example.provisioning.domain.repository;

import com.example.provisioning.domain.model.OrganizationProvisionStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StepRepository extends JpaRepository<OrganizationProvisionStep, UUID> {

    List<OrganizationProvisionStep> findByJobIdOrderByStepOrder(UUID jobId);
}
