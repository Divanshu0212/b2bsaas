package com.salespipe.identity.infra;

import com.salespipe.identity.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {}
