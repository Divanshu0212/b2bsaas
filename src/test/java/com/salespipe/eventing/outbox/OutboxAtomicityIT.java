package com.salespipe.eventing.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.common.tenant.TenantContext;
import com.salespipe.pipeline.domain.DealStageHistory;
import com.salespipe.pipeline.infra.DealStageHistoryRepository;
import com.salespipe.support.PostgresRedisTestBase;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * T2.2 acceptance test: proves the transactional-outbox guarantee — an
 * {@link OutboxRecorder#record} call and a representative business write (a
 * {@link DealStageHistory} insert, standing in for "a stage change") commit together
 * in the same transaction, and a forced rollback drops both rows, not just one.
 *
 * <p>Exercises {@link OutboxRecorder} directly rather than a full HTTP round trip —
 * per the task, the point is the same-transaction guarantee, not the API layer
 * (wiring into real write paths is T2.7).
 *
 * <p>{@link TenantContext} is {@code @RequestScope}, so a request scope is bound
 * manually here (no real servlet request is in flight in this focused test) —
 * mirroring what a real HTTP request through {@code TenantFilter} would provide.
 */
@Import(OutboxAtomicityIT.TransactionalWriter.class)
class OutboxAtomicityIT extends PostgresRedisTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantContext tenant;
    @Autowired TransactionalWriter writer;

    UUID orgId;
    UUID stageId;

    @BeforeEach
    void setup() {
        RequestAttributes attrs = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(attrs);

        orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?)",
            orgId, "Outbox Test Org", "FREE");
        tenant.setOrgId(orgId);

        // deal_stage_history.deal_id carries a FK to deals(id) (V1__init.sql), which in
        // turn FKs deal_stages(id) -- satisfy the chain so the representative business
        // write ("a stage change") is a row PostgreSQL actually accepts.
        stageId = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_stages (id, org_id, name, position) VALUES (?, ?, ?, ?)",
            stageId, orgId, "Qualified", 0);
    }

    private UUID createDeal() {
        UUID dealId = UUID.randomUUID();
        jdbc.update("INSERT INTO deals (id, org_id, stage_id) VALUES (?, ?, ?)",
            dealId, orgId, stageId);
        return dealId;
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void businessWriteAndOutboxRowCommitTogether() {
        UUID dealId = createDeal();

        UUID historyId = writer.writeBusinessChangeAndOutboxEvent(dealId, false);

        Integer historyCount = jdbc.queryForObject(
            "SELECT count(*) FROM deal_stage_history WHERE id = ?", Integer.class, historyId);
        Integer outboxCount = jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND org_id = ?",
            Integer.class, dealId.toString(), orgId);

        assertThat(historyCount).isEqualTo(1);
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void rollbackDropsBothTheBusinessWriteAndTheOutboxRow() {
        UUID dealId = createDeal();

        assertThatThrownBy(() -> writer.writeBusinessChangeAndOutboxEvent(dealId, true))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("forced rollback after business write");

        Integer historyCount = jdbc.queryForObject(
            "SELECT count(*) FROM deal_stage_history WHERE deal_id = ?", Integer.class, dealId);
        Integer outboxCount = jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND org_id = ?",
            Integer.class, dealId.toString(), orgId);

        assertThat(historyCount).isEqualTo(0);
        assertThat(outboxCount).isEqualTo(0);
    }

    /**
     * Real {@code @Service}-shaped component (not the test class itself) so
     * {@code @Transactional} gets a Spring AOP proxy and the exception thrown inside
     * actually triggers a rollback, exactly like a production caller of
     * {@link OutboxRecorder} would experience it (e.g. {@code StageTransitionService}
     * once T2.7 wires this in). Registered via {@code @Import} on the test class rather
     * than component-scanned — it's a nested class inside a test, not part of any
     * {@code @ApplicationModule} package Spring Modulith would scan.
     */
    static class TransactionalWriter {
        private final DealStageHistoryRepository history;
        private final OutboxRecorder outbox;
        private final ObjectMapper objectMapper;
        private final TenantContext tenant;

        TransactionalWriter(DealStageHistoryRepository history, OutboxRecorder outbox,
                             ObjectMapper objectMapper, TenantContext tenant) {
            this.history = history;
            this.outbox = outbox;
            this.objectMapper = objectMapper;
            this.tenant = tenant;
        }

        @Transactional
        UUID writeBusinessChangeAndOutboxEvent(UUID dealId, boolean forceRollback) {
            UUID historyId = UUID.randomUUID();
            DealStageHistory row = new DealStageHistory(
                historyId, tenant.getOrgId(), dealId, null, UUID.randomUUID(), UUID.randomUUID());
            history.save(row);

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("dealId", dealId.toString());
            outbox.record("deal", dealId.toString(), "deal.stage.changed", payload);

            if (forceRollback) {
                throw new RuntimeException("forced rollback after business write");
            }
            return historyId;
        }
    }
}
