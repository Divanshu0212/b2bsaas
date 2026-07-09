package com.salespipe.scoring.infra;

import com.salespipe.scoring.domain.LeadScore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadScoreRepository extends JpaRepository<LeadScore, UUID> {

    /** History (newest first) for a lead — backs {@code GET /leads/{id}/score} on the request thread. */
    @Query("select s from LeadScore s where s.leadId = :leadId order by s.scoredAt desc")
    List<LeadScore> findHistory(@Param("leadId") UUID leadId, Pageable pageable);

    /**
     * Latest score row for a lead, EXPLICITLY org-scoped — the recompute path calls this
     * from a consumer/scheduler thread where the Hibernate tenant {@code @Filter} is not
     * enabled (see {@code LeadFeaturesRepository#findByOrgAndLead}).
     */
    @Query("select s from LeadScore s where s.orgId = :orgId and s.leadId = :leadId order by s.scoredAt desc limit 1")
    Optional<LeadScore> findLatestByOrgAndLead(@Param("orgId") UUID orgId, @Param("leadId") UUID leadId);
}
