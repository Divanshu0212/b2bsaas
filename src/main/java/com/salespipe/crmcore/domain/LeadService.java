package com.salespipe.crmcore.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.common.tenant.TenantContext;
import com.salespipe.crmcore.infra.LeadRepository;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.outbox.OutboxRecorder;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2.7: thin service layer extracted from {@code LeadController#create} so the lead
 * insert and the {@code lead.created} outbox row can share one {@code @Transactional}
 * method — the same transactional-outbox shape {@link
 * com.salespipe.pipeline.domain.StageTransitionService} already uses for {@code
 * deal.stage.changed}. Before this task lead creation lived directly in the controller
 * with no service layer; extracting a service (rather than making the controller
 * method {@code @Transactional}) keeps the module's write-path shape consistent with
 * {@code pipeline} and gives {@link OutboxRecorder} an unambiguous, testable
 * transaction boundary to join. Deliberately scoped to lead creation only — {@code
 * AccountController}/{@code ContactController} are out of scope for T2.7 and are left
 * untouched.
 */
@Service
public class LeadService {

    private final LeadRepository repo;
    private final TenantContext tenant;
    private final OutboxRecorder outbox;
    private final ObjectMapper objectMapper;

    public LeadService(LeadRepository repo, TenantContext tenant, OutboxRecorder outbox, ObjectMapper objectMapper) {
        this.repo = repo;
        this.tenant = tenant;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * Saves the given (already-populated, not-yet-persisted) lead and records {@code
     * lead.created} in the outbox, in the same transaction — the whole point of the
     * outbox pattern (see {@link OutboxRecorder}'s javadoc). Payload shape matches
     * {@code src/main/resources/eventing/schema/lead.created.json}.
     */
    @Transactional
    public Lead create(Lead lead) {
        Lead saved = repo.save(lead);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("leadId", saved.getId().toString());
        putOrNull(payload, "contactId", saved.getContactId());
        putOrNull(payload, "accountId", saved.getAccountId());
        payload.put("source", saved.getSource());
        payload.put("status", saved.getStatus() != null ? saved.getStatus().name() : null);
        outbox.record("lead", saved.getId().toString(), Topics.LEAD_CREATED, payload);

        return saved;
    }

    private void putOrNull(ObjectNode node, String field, UUID value) {
        if (value != null) {
            node.put(field, value.toString());
        } else {
            node.putNull(field);
        }
    }
}
