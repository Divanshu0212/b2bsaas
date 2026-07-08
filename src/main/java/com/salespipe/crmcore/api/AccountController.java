package com.salespipe.crmcore.api;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.crmcore.api.dto.AccountRequest;
import com.salespipe.crmcore.api.dto.AccountResponse;
import com.salespipe.crmcore.api.mapper.AccountMapper;
import com.salespipe.crmcore.domain.Account;
import com.salespipe.crmcore.infra.AccountRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {
    private final AccountRepository repo;
    private final AccountMapper mapper;
    private final TenantContext tenant;

    public AccountController(AccountRepository repo, AccountMapper mapper, TenantContext tenant) {
        this.repo = repo; this.mapper = mapper; this.tenant = tenant;
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody AccountRequest req) {
        Account a = new Account(UUID.randomUUID(), tenant.getOrgId(), req.name());
        a.setIndustry(req.industry()); a.setEmployeeCount(req.employeeCount());
        a.setWebsite(req.website());
        return mapper.toResponse(repo.save(a));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) { return mapper.toResponse(find(id)); }

    @GetMapping
    public Page<AccountResponse> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return repo.findAll(PageRequest.of(page, size)).map(mapper::toResponse);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { repo.delete(find(id)); }

    private Account find(UUID id) {
        return repo.findByIdFiltered(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    }
}
