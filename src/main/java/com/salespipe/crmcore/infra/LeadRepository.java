package com.salespipe.crmcore.infra;

import com.salespipe.crmcore.domain.Lead;
import com.salespipe.crmcore.domain.LeadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    @Query("select l from Lead l where " +
           "(:status is null or l.status = :status) and " +
           "(:ownerId is null or l.ownerId = :ownerId)")
    Page<Lead> search(@Param("status") LeadStatus status,
                      @Param("ownerId") UUID ownerId, Pageable pageable);

    // Hibernate's org_id @Filter is not applied to EntityManager#find() (primary-key
    // loads bypass @Filter by design) so findById() would leak cross-tenant rows.
    // Look up by id through HQL instead, which does honor the filter.
    @Query("select l from Lead l where l.id = :id")
    Optional<Lead> findByIdFiltered(@Param("id") UUID id);
}
