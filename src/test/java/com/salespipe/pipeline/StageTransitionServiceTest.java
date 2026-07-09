package com.salespipe.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.common.tenant.TenantContext;
import com.salespipe.eventing.outbox.OutboxRecorder;
import com.salespipe.pipeline.domain.Deal;
import com.salespipe.pipeline.domain.DealStage;
import com.salespipe.pipeline.domain.StageTransitionService;
import com.salespipe.pipeline.infra.DealRepository;
import com.salespipe.pipeline.infra.DealStageHistoryRepository;
import com.salespipe.pipeline.infra.DealStageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StageTransitionServiceTest {

    @Test
    void staleVersionThrows409() {
        DealRepository deals = mock(DealRepository.class);
        DealStageRepository stages = mock(DealStageRepository.class);
        DealStageHistoryRepository history = mock(DealStageHistoryRepository.class);
        TenantContext tenant = new TenantContext();
        tenant.setOrgId(UUID.randomUUID());

        UUID dealId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        Deal deal = new Deal(dealId, tenant.getOrgId(), UUID.randomUUID());
        when(deals.findByIdFiltered(dealId)).thenReturn(Optional.of(deal));

        OutboxRecorder outbox = mock(OutboxRecorder.class);
        var svc = new StageTransitionService(deals, stages, history, tenant, outbox, new ObjectMapper());

        // deal.version == 0; client sends expectedVersion=5 -> stale -> 409
        assertThatThrownBy(() -> svc.move(dealId, stageId, 5, UUID.randomUUID()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409));
    }
}
