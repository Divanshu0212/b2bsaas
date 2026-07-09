package com.salespipe.eventing.admin;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only DLQ management API (T4.5): list what's stuck, count it, and replay a specific
 * poison message back to its source topic once the underlying cause is fixed — the
 * failure→fix→replay loop the plan calls for, with no silent message loss.
 */
@RestController
@RequestMapping("/admin/dlq")
@PreAuthorize("hasRole('ADMIN')")
public class DlqAdminController {

    private final DlqAdminService service;

    public DlqAdminController(DlqAdminService service) {
        this.service = service;
    }

    /** Known DLQ topic names. */
    @GetMapping("/topics")
    public List<String> topics() {
        return service.dlqTopics();
    }

    /** Lists up to {@code limit} messages in {@code topic} (oldest first). */
    @GetMapping
    public List<DlqMessage> list(
        @RequestParam String topic,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return service.list(topic, limit);
    }

    /** Message count in {@code topic}. */
    @GetMapping("/count")
    public Map<String, Long> count(@RequestParam String topic) {
        return Map.of("count", service.count(topic));
    }

    /** Replays one DLQ record back to its original topic; returns the topic it went to. */
    @PostMapping("/replay")
    public ResponseEntity<Map<String, Object>> replay(@RequestBody ReplayRequest request) {
        String originalTopic = service.replay(request.topic(), request.partition(), request.offset());
        return ResponseEntity.ok(Map.of(
            "replayed", true,
            "originalTopic", originalTopic,
            "partition", request.partition(),
            "offset", request.offset()));
    }

    public record ReplayRequest(String topic, int partition, long offset) {}
}
