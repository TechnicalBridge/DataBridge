package com.tbridge.debt.repository;

import com.tbridge.debt.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Optional<Organization> findByRut(String rut);

    long countByStatus(Organization.Status status);
}
