package com.salespipe.scoring.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.common.tenant.TenantContext;
import com.salespipe.scoring.domain.LeadScore;
import com.salespipe.scoring.infra.LeadScoreRepository;
import com.salespipe.scoring.recompute.ScoringService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Score surfacing API (T3.5). {@code GET /leads/{id}/score} returns the latest score +
 * history (async recompute is the source of truth — this endpoint only reads). {@code
 * POST /leads/{id}/score/refresh} is the sync/manual path the plan reserves for
 * cache-miss / explicit refresh (overview change #9) — it drives one recompute inline.
 *
 * <p>Runs on the request thread, so the Hibernate tenant {@code @Filter} is enabled;
 * both queries are lead-scoped and the filter enforces org isolation.
 */
@RestController
public class ScoreController {

    private final LeadScoreRepository scores;
    private final ScoringService scoringService;
    private final TenantContext tenant;
    private final ObjectMapper objectMapper;

    public ScoreController(
        LeadScoreRepository scores,
        ScoringService scoringService,
        TenantContext tenant,
        ObjectMapper objectMapper
    ) {
        this.scores = scores;
        this.scoringService = scoringService;
        this.tenant = tenant;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/leads/{id}/score")
    public ScoreResponseDto score(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "20") int historySize
    ) {
        List<LeadScore> history = scores.findHistory(id, PageRequest.of(0, historySize));
        ScoreResponseDto.ScorePoint latest = history.isEmpty() ? null : toPoint(history.get(0));
        return new ScoreResponseDto(id, latest, history.stream().map(this::toPoint).toList());
    }

    /** Manual/sync refresh (cache-miss path). Recomputes now and returns the fresh view. */
    @PostMapping("/leads/{id}/score/refresh")
    public ScoreResponseDto refresh(@PathVariable UUID id) {
        scoringService.recompute(tenant.getOrgId(), id);
        return score(id, 20);
    }

    private ScoreResponseDto.ScorePoint toPoint(LeadScore s) {
        return new ScoreResponseDto.ScorePoint(
            s.getScore() != null ? s.getScore() : BigDecimal.ZERO,
            s.getModelVersion(),
            parse(s.getTopFactors()),
            s.getScoredAt()
        );
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }
}
