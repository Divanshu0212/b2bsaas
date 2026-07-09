package com.salespipe.activity.infra;

import com.salespipe.activity.domain.Activity;
import com.salespipe.activity.domain.ActivityId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, ActivityId> {

    /**
     * Timeline query backing {@code GET /leads/{id}/timeline} (T2.5). Honors Hibernate's
     * {@code org_id} {@code @Filter} (this is a non-PK query, so unlike a {@code
     * findById}-style lookup the filter applies automatically — see {@code
     * TenantEntity}/{@code common/tenant} for why PK loads need the explicit
     * {@code findByIdFiltered} workaround elsewhere and this query does not).
     *
     * <p>Filters on {@code entityId in :entityIds} only (no {@code entityType}
     * predicate) so the controller can pass the lead's own id plus any linked deals'
     * ids in one call and get a single, {@code created_at}-ordered merged feed back —
     * see {@code TimelineController} for the "merged feed" interpretation this
     * supports. {@code entity_id} is a UUID generated per-aggregate (lead ids and deal
     * ids are both random UUIDs from disjoint tables), so collision across entity types
     * is not a practical concern.
     */
    @Query("select a from Activity a where a.entityId in :entityIds order by a.id.createdAt desc")
    Page<Activity> findTimeline(@Param("entityIds") List<UUID> entityIds, Pageable pageable);
}
