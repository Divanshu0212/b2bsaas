package com.salespipe.eventing.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/**
 * Plain {@link JpaRepository} — outbox rows are insert-only from domain code (via
 * {@link OutboxRecorder}) and never looked up by id here, so this doesn't need the
 * {@code findByIdFiltered} tenant-scoping helper pattern used by e.g.
 * {@code DealRepository} for primary-key reads. (Debezium/CDC and the T2.3 polling
 * relay read this table directly outside JPA.)
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {}
