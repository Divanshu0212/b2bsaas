package com.salespipe.scoring.recompute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.outbox.OutboxRecorder;
import com.salespipe.scoring.client.ScoreRequest;
import com.salespipe.scoring.client.ScoreResponse;
import com.salespipe.scoring.client.ScoringClient;
import com.salespipe.scoring.domain.LeadFeatures;
import com.salespipe.scoring.domain.LeadScore;
import com.salespipe.scoring.infra.LeadFeaturesRepository;
import com.salespipe.scoring.infra.LeadScoreRepository;
import com.salespipe.scoring.infra.LeadScoreWriter;
import com.salespipe.scoring.infra.ScoringFeatureAssembler;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Async recompute — the source of truth for a lead's score (overview change #9, T3.4).
 * Assembles features, calls the ML service through the resilient {@link ScoringClient},
 * and on success persists a new {@code lead_scores} history row (with {@code
 * model_version}), refreshes the {@code leads.current_score} cache, and records a {@code
 * lead.score.updated} outbox event — all in one transaction so the history row, the
 * cache, and the event are atomic.
 *
 * <p><b>Graceful degradation.</b> If {@link ScoringClient} returns empty (ML down,
 * timeout, circuit open) this method logs and returns the last-known score WITHOUT
 * writing a new row or an event — the app never surfaces an ML error and never records a
 * bogus score. This is the T3.4 accept criterion ("ML service down -> app returns
 * last-known score, no error to caller, circuit opens").
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final LeadFeaturesRepository features;
    private final LeadScoreRepository scores;
    private final ScoringFeatureAssembler assembler;
    private final ScoringClient client;
    private final LeadScoreWriter scoreWriter;
    private final OutboxRecorder outbox;
    private final ObjectMapper objectMapper;

    public ScoringService(
        LeadFeaturesRepository features,
        LeadScoreRepository scores,
        ScoringFeatureAssembler assembler,
        ScoringClient client,
        LeadScoreWriter scoreWriter,
        OutboxRecorder outbox,
        ObjectMapper objectMapper
    ) {
        this.features = features;
        this.scores = scores;
        this.assembler = assembler;
        this.client = client;
        this.scoreWriter = scoreWriter;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * Recompute and persist the score for one lead. Returns the score that is now
     * authoritative — the freshly-computed one, or (if ML was unavailable) the last-known
     * one, or empty if there is neither.
     */
    @Transactional
    public Optional<BigDecimal> recompute(UUID orgId, UUID leadId) {
        LeadFeatures leadFeatures = features.findByOrgAndLead(orgId, leadId).orElse(null);
        ScoreRequest request = assembler.assemble(orgId, leadId, leadFeatures);

        Optional<ScoreResponse> response = client.score(request);
        if (response.isEmpty()) {
            Optional<BigDecimal> lastKnown = scores.findLatestByOrgAndLead(orgId, leadId).map(LeadScore::getScore);
            log.info("Scoring unavailable for lead {}; keeping last-known score {}", leadId, lastKnown.orElse(null));
            return lastKnown;
        }

        ScoreResponse scored = response.get();
        BigDecimal score = BigDecimal.valueOf(scored.score()).setScale(4, RoundingMode.HALF_UP);

        scores.save(new LeadScore(
            UUID.randomUUID(), orgId, leadId, score, scored.modelVersion(), topFactorsJson(scored)
        ));
        scoreWriter.updateCurrentScore(orgId, leadId, score);
        recordScoreUpdatedEvent(orgId, leadId, score, scored.modelVersion());
        return Optional.of(score);
    }

    private void recordScoreUpdatedEvent(UUID orgId, UUID leadId, BigDecimal score, String modelVersion) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("leadId", leadId.toString());
        payload.put("score", score.doubleValue());
        if (modelVersion != null) {
            payload.put("modelVersion", modelVersion);
        } else {
            payload.putNull("modelVersion");
        }
        // Matches lead.score.updated.json; partition key = leadId for per-lead ordering.
        // Explicit orgId overload: recompute runs off the request thread (Kafka consumer /
        // scheduled sweep) where the @RequestScope TenantContext is not populated.
        outbox.record(orgId, "lead", leadId.toString(), Topics.LEAD_SCORE_UPDATED, payload);
    }

    private String topFactorsJson(ScoreResponse scored) {
        try {
            return objectMapper.writeValueAsString(scored.topFactors());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
