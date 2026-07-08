package com.salespipe.pipeline.infra;

import com.salespipe.pipeline.domain.DealStageHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DealStageHistoryRepository extends JpaRepository<DealStageHistory, UUID> {}
