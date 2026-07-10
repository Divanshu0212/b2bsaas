package com.salespipe.notification.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.common.tenant.TenantContext;
import com.salespipe.common.tenant.TenantFilter.AuthPrincipal;
import com.salespipe.notification.domain.Notification;
import com.salespipe.notification.infra.NotificationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * T6.7: in-app notification read API. Lists the current user's notifications (org + user
 * scoped) with an unread count for the bell badge, and marks a notification read. The
 * frontend polls {@code GET /notifications} (~20s); WebSocket/SSE push is the STRETCH
 * upgrade. Notifications are produced by the Phase 2/4 consumers (hot-lead, stage-change).
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private static final int DEFAULT_LIMIT = 50;

    private final NotificationRepository repo;
    private final TenantContext tenant;
    private final ObjectMapper objectMapper;

    public NotificationController(NotificationRepository repo, TenantContext tenant,
                                  ObjectMapper objectMapper) {
        this.repo = repo; this.tenant = tenant; this.objectMapper = objectMapper;
    }

    @GetMapping
    public NotificationListResponse list(
        @AuthenticationPrincipal AuthPrincipal principal,
        @RequestParam(defaultValue = "50") int limit
    ) {
        UUID orgId = tenant.getOrgId();
        UUID userId = principal.userId();
        int capped = Math.min(Math.max(limit, 1), 200);
        List<NotificationDto> items = repo
            .findForUser(orgId, userId, PageRequest.of(0, capped))
            .stream().map(this::toDto).toList();
        long unread = repo.countUnread(orgId, userId);
        return new NotificationListResponse(items, unread);
    }

    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<Void> markRead(
        @AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable UUID id
    ) {
        int updated = repo.markRead(id, tenant.getOrgId(), principal.userId());
        // 204 whether it flipped a row or was already read; 404 only if it isn't the user's.
        return updated > 0
            ? ResponseEntity.noContent().build()
            : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    private NotificationDto toDto(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), parse(n.getPayload()),
            n.getReadAt(), n.getCreatedAt());
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    public record NotificationDto(UUID id, String type, JsonNode payload,
                                  OffsetDateTime readAt, OffsetDateTime createdAt) {}

    public record NotificationListResponse(List<NotificationDto> items, long unreadCount) {}
}
