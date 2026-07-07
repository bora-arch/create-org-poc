package com.example.provisioning.domain.repository;

import com.example.provisioning.domain.model.OrganizationProvisionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WorkflowRepository extends JpaRepository<OrganizationProvisionJob, UUID> {
}
