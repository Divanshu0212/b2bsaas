package com.salespipe.crmcore.api;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.crmcore.api.dto.LeadRequest;
import com.salespipe.crmcore.api.dto.LeadResponse;
import com.salespipe.crmcore.api.mapper.LeadMapper;
import com.salespipe.crmcore.domain.Lead;
import com.salespipe.crmcore.domain.LeadService;
import com.salespipe.crmcore.domain.LeadStatus;
import com.salespipe.crmcore.infra.LeadRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/leads")
public class LeadController {

    private final LeadRepository repo;
    private final LeadMapper mapper;
    private final TenantContext tenant;
    private final LeadService leadService;

    public LeadController(LeadRepository repo, LeadMapper mapper, TenantContext tenant, LeadService leadService) {
        this.repo = repo; this.mapper = mapper; this.tenant = tenant; this.leadService = leadService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeadResponse create(@Valid @RequestBody LeadRequest req) {
        Lead lead = new Lead(UUID.randomUUID(), tenant.getOrgId(), req.status());
        apply(lead, req);
        // T2.7: delegate to LeadService so the lead insert + lead.created outbox row
        // commit in the same transaction (see LeadService javadoc for why this moved
        // out of the controller rather than making this method @Transactional).
        return mapper.toResponse(leadService.create(lead));
    }

    @GetMapping("/{id}")
    public LeadResponse get(@PathVariable UUID id) {
        return mapper.toResponse(find(id));
    }

    @PutMapping("/{id}")
    public LeadResponse update(@PathVariable UUID id, @Valid @RequestBody LeadRequest req) {
        Lead lead = find(id);
        lead.setStatus(req.status());
        apply(lead, req);
        return mapper.toResponse(repo.save(lead));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { repo.delete(find(id)); }

    @GetMapping
    public Page<LeadResponse> list(@RequestParam(required = false) LeadStatus status,
                                   @RequestParam(required = false) UUID owner,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return repo.search(status, owner, PageRequest.of(page, size))
                   .map(mapper::toResponse);
    }

    private void apply(Lead lead, LeadRequest req) {
        lead.setSource(req.source());
        lead.setRawNotes(req.rawNotes());
        lead.setContactId(req.contactId());
        lead.setAccountId(req.accountId());
        lead.setOwnerId(req.ownerId());
    }

    private Lead find(UUID id) {
        return repo.findByIdFiltered(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    }
}
