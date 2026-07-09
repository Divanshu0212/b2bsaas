/**
 * Exposed as a Spring Modulith {@code NamedInterface} so other modules can call {@link
 * com.salespipe.eventing.producer.EventPublisher#publish} directly for events that
 * bypass the outbox (async, non-transactional emission — e.g. T2.6's email tracking
 * pixel/click/webhook endpoints, which the plan explicitly calls out as emitting
 * {@code email.event.received} outside the outbox+CDC path).
 *
 * <p>Same reasoning as {@link com.salespipe.eventing.consumer}'s {@code
 * package-info.java}: without this annotation, {@code
 * com.salespipe.ModuleBoundaryTest} rejects any cross-module use of {@code
 * EventPublisher} as a Spring Modulith boundary violation.
 */
@org.springframework.modulith.NamedInterface("producer")
package com.salespipe.eventing.producer;
