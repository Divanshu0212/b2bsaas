package com.salespipe.emailtracking.infra;

import com.salespipe.emailtracking.domain.Email;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailRepository extends JpaRepository<Email, UUID> {

    // No Hibernate tenantFilter involvement here (see Email's javadoc) so a plain
    // derived-query lookup by tracking_id is safe -- unlike AccountRepository's
    // findByIdFiltered() workaround, there is no filter to bypass.
    Optional<Email> findByTrackingId(UUID trackingId);
}
