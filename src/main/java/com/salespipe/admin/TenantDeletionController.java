package com.salespipe.admin;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only GDPR tenant hard-delete endpoint (T4.7). {@code DELETE /admin/tenants/{orgId}}
 * removes all data for that org across every table and returns the per-table row counts
 * removed (useful as a deletion receipt for a data-subject request).
 */
@RestController
@RequestMapping("/admin/tenants")
@PreAuthorize("hasRole('ADMIN')")
public class TenantDeletionController {

    private final TenantDeletionService service;

    public TenantDeletionController(TenantDeletionService service) {
        this.service = service;
    }

    @DeleteMapping("/{orgId}")
    public ResponseEntity<Map<String, Integer>> delete(@PathVariable UUID orgId) {
        return ResponseEntity.ok(service.hardDelete(orgId));
    }
}
