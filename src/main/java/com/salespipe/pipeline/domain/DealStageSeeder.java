package com.salespipe.pipeline.domain;

import com.salespipe.pipeline.infra.DealStageRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DealStageSeeder {
    private final DealStageRepository repo;
    public DealStageSeeder(DealStageRepository repo) { this.repo = repo; }

    /** Seed the default pipeline for a freshly-registered org. */
    public void seedDefaults(UUID orgId) {
        repo.save(new DealStage(UUID.randomUUID(), orgId, "NEW", 0, false, false));
        repo.save(new DealStage(UUID.randomUUID(), orgId, "QUALIFICATION", 1, false, false));
        repo.save(new DealStage(UUID.randomUUID(), orgId, "PROPOSAL", 2, false, false));
        repo.save(new DealStage(UUID.randomUUID(), orgId, "WON", 3, true, false));
        repo.save(new DealStage(UUID.randomUUID(), orgId, "LOST", 4, false, true));
    }
}
