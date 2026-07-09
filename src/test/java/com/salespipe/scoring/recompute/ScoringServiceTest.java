package com.salespipe.scoring.recompute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.outbox.OutboxRecorder;
import com.salespipe.scoring.client.ScoreRequest;
import com.salespipe.scoring.client.ScoreResponse;
import com.salespipe.scoring.client.ScoringClient;
import com.salespipe.scoring.domain.LeadScore;
import com.salespipe.scoring.infra.LeadFeaturesRepository;
import com.salespipe.scoring.infra.LeadScoreRepository;
import com.salespipe.scoring.infra.LeadScoreWriter;
import com.salespipe.scoring.infra.ScoringFeatureAssembler;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T3.4 unit test for the recompute logic — the two branches the phase-3 accept lines
 * pin down: (1) a successful score persists a {@code lead_scores} history row with the
 * model_version + updates the current_score cache + emits {@code lead.score.updated};
 * (2) an unavailable ML service (client returns empty) writes NO row and NO event and
 * falls back to the last-known score. Pure Mockito — no containers.
 */
@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock LeadFeaturesRepository features;
    @Mock LeadScoreRepository scores;
    @Mock ScoringFeatureAssembler assembler;
    @Mock ScoringClient client;
    @Mock LeadScoreWriter scoreWriter;
    @Mock OutboxRecorder outbox;

    ScoringService service;

    UUID orgId = UUID.randomUUID();
    UUID leadId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ScoringService(features, scores, assembler, client, scoreWriter, outbox, new ObjectMapper());
        when(features.findByOrgAndLead(orgId, leadId)).thenReturn(Optional.empty());
        when(assembler.assemble(eq(orgId), eq(leadId), any()))
            .thenReturn(new ScoreRequest(leadId.toString(), List.of(), java.util.Map.of()));
    }

    @Test
    void successfulScorePersistsHistoryUpdatesCacheAndEmitsEvent() {
        when(client.score(any())).thenReturn(Optional.of(
            new ScoreResponse(0.8123, "leadscore/Production/v7",
                List.of(new ScoreResponse.TopFactor("email_click_count", 0.18)))));

        Optional<BigDecimal> result = service.recompute(orgId, leadId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("0.8123");
        verify(scores).save(any(LeadScore.class));
        verify(scoreWriter).updateCurrentScore(eq(orgId), eq(leadId), any(BigDecimal.class));
        verify(outbox).record(eq(orgId), eq("lead"), eq(leadId.toString()), eq(Topics.LEAD_SCORE_UPDATED), any());
    }

    @Test
    void mlUnavailableFallsBackToLastKnownAndWritesNothing() {
        when(client.score(any())).thenReturn(Optional.empty());
        when(scores.findLatestByOrgAndLead(orgId, leadId)).thenReturn(Optional.of(
            new LeadScore(UUID.randomUUID(), orgId, leadId, new BigDecimal("0.4200"), "leadscore/Production/v6", "[]")));

        Optional<BigDecimal> result = service.recompute(orgId, leadId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("0.4200"); // last-known, not a new score
        verify(scores, never()).save(any());
        verify(scoreWriter, never()).updateCurrentScore(any(), any(), any());
        verify(outbox, never()).record(any(), any(), any(), any(), any());
    }
}
