package com.salespipe.crmcore.infra;

import com.salespipe.crmcore.domain.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    // Hibernate's org_id @Filter is not applied to EntityManager#find() (primary-key
    // loads bypass @Filter by design) so findById() would leak cross-tenant rows.
    // Look up by id through HQL instead, which does honor the filter.
    @Query("select c from Contact c where c.id = :id")
    Optional<Contact> findByIdFiltered(@Param("id") UUID id);
}
