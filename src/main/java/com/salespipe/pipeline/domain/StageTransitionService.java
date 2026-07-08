package com.salespipe.pipeline.domain;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.pipeline.infra.DealRepository;
import com.salespipe.pipeline.infra.DealStageHistoryRepository;
import com.salespipe.pipeline.infra.DealStageRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

@Service
public class StageTransitionService {

    private final DealRepository deals;
    private final DealStageRepository stages;
    private final DealStageHistoryRepository history;
    private final TenantContext tenant;

    public StageTransitionService(DealRepository deals, DealStageRepository stages,
                                  DealStageHistoryRepository history, TenantContext tenant) {
        this.deals = deals; this.stages = stages;
        this.history = history; this.tenant = tenant;
    }

    /** Move a deal to a new stage under optimistic lock. Version mismatch => 409. */
    @Transactional
    public Deal move(UUID dealId, UUID toStageId, int expectedVersion, UUID actorId) {
        // findByIdFiltered (not findById): primary-key loads bypass Hibernate's
        // org_id @Filter, so a plain findById would leak cross-tenant deals/stages.
        Deal deal = deals.findByIdFiltered(dealId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "deal not found"));
        if (deal.getVersion() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "stale deal version");
        }
        stages.findByIdFiltered(toStageId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown stage"));

        UUID fromStageId = deal.getStageId();
        deal.moveToStage(toStageId);
        try {
            // triggers @Version check -> OptimisticLockingFailureException on a true race
            // where both requests passed the explicit expectedVersion check above.
            deals.saveAndFlush(deal);
        } catch (OptimisticLockingFailureException e) {
            // Interim mapping until Task 7's global exception handler exists:
            // a real DB-level version race must still surface as 409, not 500.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "stale deal version", e);
        }

        history.save(new DealStageHistory(UUID.randomUUID(), tenant.getOrgId(),
            dealId, fromStageId, toStageId, actorId));
        return deal;
    }
}
