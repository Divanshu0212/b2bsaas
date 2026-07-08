package com.salespipe.pipeline.api;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.common.tenant.TenantFilter.AuthPrincipal;
import com.salespipe.pipeline.api.dto.*;
import com.salespipe.pipeline.domain.Deal;
import com.salespipe.pipeline.domain.DealStage;
import com.salespipe.pipeline.domain.StageTransitionService;
import com.salespipe.pipeline.infra.DealRepository;
import com.salespipe.pipeline.infra.DealStageRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/deals")
public class DealController {

    private final DealRepository deals;
    private final DealStageRepository stages;
    private final StageTransitionService transitions;
    private final TenantContext tenant;

    public DealController(DealRepository deals, DealStageRepository stages,
                          StageTransitionService transitions, TenantContext tenant) {
        this.deals = deals; this.stages = stages;
        this.transitions = transitions; this.tenant = tenant;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DealResponse create(@Valid @RequestBody DealRequest req) {
        Deal deal = new Deal(UUID.randomUUID(), tenant.getOrgId(), req.stageId());
        deal.setLeadId(req.leadId()); deal.setAccountId(req.accountId());
        deal.setOwnerId(req.ownerId()); deal.setAmount(req.amount());
        deal.setCurrency(req.currency()); deal.setExpectedCloseDate(req.expectedCloseDate());
        return toResponse(deals.save(deal));
    }

    @GetMapping("/{id}")
    public DealResponse get(@PathVariable UUID id) {
        // findByIdFiltered (not findById): primary-key loads bypass Hibernate's
        // org_id @Filter, so a plain findById would leak cross-tenant deals.
        return toResponse(deals.findByIdFiltered(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "deal not found")));
    }

    @PatchMapping("/{id}/stage")
    public DealResponse move(@PathVariable UUID id,
                             @Valid @RequestBody StageChangeRequest req,
                             @AuthenticationPrincipal AuthPrincipal principal) {
        Deal moved = transitions.move(id, req.toStageId(), req.version(),
            principal != null ? principal.userId() : null);
        return toResponse(moved);
    }

    @GetMapping("/pipeline")
    public List<StageColumn> pipeline() {
        return stages.findAllByOrderByPositionAsc().stream().map(this::column).toList();
    }

    private StageColumn column(DealStage stage) {
        List<DealResponse> ds = deals.findByStageId(stage.getId())
            .stream().map(this::toResponse).toList();
        return new StageColumn(stage.getId(), stage.getName(), stage.getPosition(), ds);
    }

    private DealResponse toResponse(Deal d) {
        return new DealResponse(d.getId(), d.getStageId(), d.getLeadId(), d.getAccountId(),
            d.getOwnerId(), d.getAmount(), d.getCurrency(), d.getExpectedCloseDate(), d.getVersion());
    }
}
