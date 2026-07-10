package com.salespipe.pipeline.infra;

import com.salespipe.pipeline.domain.Deal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DealRepository extends JpaRepository<Deal, UUID> {
    List<Deal> findByStageId(UUID stageId);

    // Hibernate's org_id @Filter is not applied to EntityManager#find() (primary-key
    // loads bypass @Filter by design) so findById() would leak cross-tenant rows.
    // Look up by id through HQL instead, which does honor the filter.
    @Query("select d from Deal d where d.id = :id")
    Optional<Deal> findByIdFiltered(@Param("id") UUID id);

    /**
     * T6.8: deal count + total amount grouped by stage — the funnel report. HQL so the
     * tenant @Filter scopes it to the current org. Ordering by stage position is applied
     * in the controller (the join to DealStage is done there to keep this a plain group-by).
     */
    @Query("select d.stageId as stageId, count(d) as dealCount, "
        + "coalesce(sum(d.amount), 0) as totalAmount "
        + "from Deal d group by d.stageId")
    List<StageAggregate> funnelByStage();

    /** T6.8: rep leaderboard — deal count + total amount per owner, current org only. */
    @Query("select d.ownerId as ownerId, count(d) as dealCount, "
        + "coalesce(sum(d.amount), 0) as totalAmount "
        + "from Deal d where d.ownerId is not null group by d.ownerId "
        + "order by sum(d.amount) desc")
    List<OwnerAggregate> leaderboard();

    interface StageAggregate {
        UUID getStageId();
        long getDealCount();
        java.math.BigDecimal getTotalAmount();
    }

    interface OwnerAggregate {
        UUID getOwnerId();
        long getDealCount();
        java.math.BigDecimal getTotalAmount();
    }
}
