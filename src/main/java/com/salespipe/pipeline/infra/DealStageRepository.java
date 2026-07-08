package com.salespipe.pipeline.infra;

import com.salespipe.pipeline.domain.DealStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DealStageRepository extends JpaRepository<DealStage, UUID> {
    List<DealStage> findAllByOrderByPositionAsc();

    // Hibernate's org_id @Filter is not applied to EntityManager#find() (primary-key
    // loads bypass @Filter by design) so findById() would leak cross-tenant rows.
    // Look up by id through HQL instead, which does honor the filter.
    @Query("select s from DealStage s where s.id = :id")
    Optional<DealStage> findByIdFiltered(@Param("id") UUID id);
}
