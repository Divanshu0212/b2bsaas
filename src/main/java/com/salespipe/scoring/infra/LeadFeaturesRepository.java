package com.salespipe.scoring.infra;

import com.salespipe.scoring.domain.LeadFeatures;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadFeaturesRepository extends JpaRepository<LeadFeatures, UUID> {

    /**
     * Load a feature row with an EXPLICIT {@code org_id} predicate rather than relying on
     * Hibernate's request-scoped {@code @Filter}. The feature/recompute path runs on Kafka
     * consumer + scheduler threads where {@code TenantFilterAspect} never enabled the
     * filter (it only binds on request threads), so an unfiltered {@code findById} could
     * leak cross-tenant rows. Same explicit-org-scoping discipline as {@code
     * activity.infra.LinkedDealLookup}/{@code scoring.infra.FeatureLookup}.
     */
    @Query("select f from LeadFeatures f where f.orgId = :orgId and f.leadId = :leadId")
    Optional<LeadFeatures> findByOrgAndLead(@Param("orgId") UUID orgId, @Param("leadId") UUID leadId);
}
