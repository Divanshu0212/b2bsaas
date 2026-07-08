package com.salespipe.eventing.consumer;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the {@code processed_events} dedupe table (T2.4).
 *
 * <p>Deliberately does NOT expose a "has this been processed" lookup used in a
 * check-then-insert sequence — the plan's dedupe mechanism is an actual insert attempt
 * that relies on the composite-PK unique constraint to fail on a duplicate
 * {@code (consumer_group, event_id)}, not a check-then-insert (which has a race: two
 * concurrent redeliveries could both pass the check before either inserts). See
 * {@link IdempotentConsumer#consume} for the insert-and-catch-{@code
 * DataIntegrityViolationException} usage of {@link #saveAndFlush}.
 *
 * <p>{@link #saveAndFlush} (not plain {@code save}) is required here: Hibernate batches
 * INSERTs lazily by default, and the whole point of this pattern is that the unique
 * violation surfaces synchronously, inside the caller's try/catch, before the business
 * handler runs — not silently deferred to end-of-transaction flush.
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {}
