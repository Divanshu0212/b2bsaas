package com.salespipe.pipeline.api;

import com.salespipe.identity.domain.User;
import com.salespipe.identity.infra.UserRepository;
import com.salespipe.pipeline.domain.DealStage;
import com.salespipe.pipeline.infra.DealRepository;
import com.salespipe.pipeline.infra.DealStageRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T6.8: reporting. {@code GET /reports/funnel} returns per-stage deal counts/amounts in
 * pipeline order (the funnel) plus a rep leaderboard. Aggregates run through HQL so the
 * Hibernate tenant @Filter scopes every figure to the caller's org.
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final DealRepository deals;
    private final DealStageRepository stages;
    private final UserRepository users;

    public ReportController(DealRepository deals, DealStageRepository stages,
                            UserRepository users) {
        this.deals = deals; this.stages = stages; this.users = users;
    }

    @GetMapping("/funnel")
    public FunnelReport funnel() {
        // stageId -> aggregate; stages with zero deals still appear (count 0).
        Map<UUID, DealRepository.StageAggregate> byStage = deals.funnelByStage().stream()
            .collect(Collectors.toMap(DealRepository.StageAggregate::getStageId,
                Function.identity()));

        List<FunnelStage> funnel = stages.findAllByOrderByPositionAsc().stream()
            .map(s -> {
                var agg = byStage.get(s.getId());
                long count = agg != null ? agg.getDealCount() : 0L;
                BigDecimal amount = agg != null ? agg.getTotalAmount() : BigDecimal.ZERO;
                return new FunnelStage(s.getId(), s.getName(), s.getPosition(), count, amount);
            })
            .toList();

        List<DealRepository.OwnerAggregate> owners = deals.leaderboard();
        Map<UUID, String> emails = users.findAllById(
                owners.stream().map(DealRepository.OwnerAggregate::getOwnerId).toList())
            .stream().collect(Collectors.toMap(User::getId, User::getEmail));

        List<LeaderboardRow> leaderboard = owners.stream()
            .map(o -> new LeaderboardRow(o.getOwnerId(),
                emails.getOrDefault(o.getOwnerId(), "unknown"),
                o.getDealCount(), o.getTotalAmount()))
            .toList();

        return new FunnelReport(funnel, leaderboard);
    }

    public record FunnelStage(UUID stageId, String stageName, int position,
                              long dealCount, BigDecimal totalAmount) {}

    public record LeaderboardRow(UUID ownerId, String ownerEmail,
                                 long dealCount, BigDecimal totalAmount) {}

    public record FunnelReport(List<FunnelStage> funnel, List<LeaderboardRow> leaderboard) {}
}
