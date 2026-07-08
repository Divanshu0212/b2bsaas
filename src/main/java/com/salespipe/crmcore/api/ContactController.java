package com.salespipe.crmcore.api;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.crmcore.api.dto.ContactRequest;
import com.salespipe.crmcore.api.dto.ContactResponse;
import com.salespipe.crmcore.api.mapper.ContactMapper;
import com.salespipe.crmcore.domain.Contact;
import com.salespipe.crmcore.infra.ContactRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/contacts")
public class ContactController {
    private final ContactRepository repo;
    private final ContactMapper mapper;
    private final TenantContext tenant;

    public ContactController(ContactRepository repo, ContactMapper mapper, TenantContext tenant) {
        this.repo = repo; this.mapper = mapper; this.tenant = tenant;
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ContactResponse create(@Valid @RequestBody ContactRequest req) {
        Contact c = new Contact(UUID.randomUUID(), tenant.getOrgId());
        c.setAccountId(req.accountId()); c.setFirstName(req.firstName());
        c.setLastName(req.lastName()); c.setEmail(req.email());
        c.setPhone(req.phone()); c.setTitle(req.title());
        return mapper.toResponse(repo.save(c));
    }

    @GetMapping("/{id}")
    public ContactResponse get(@PathVariable UUID id) { return mapper.toResponse(find(id)); }

    @GetMapping
    public Page<ContactResponse> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return repo.findAll(PageRequest.of(page, size)).map(mapper::toResponse);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { repo.delete(find(id)); }

    private Contact find(UUID id) {
        return repo.findByIdFiltered(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "contact not found"));
    }
}
