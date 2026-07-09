/**
 * Exposed as a Spring Modulith {@code NamedInterface} so other modules can call {@link
 * com.salespipe.eventing.outbox.OutboxRecorder#record} — the documented T2.2 entry
 * point for recording an outbox row in the same transaction as a domain write. T2.7
 * wires this into {@code pipeline.domain.StageTransitionService} and {@code
 * crmcore.domain.LeadService}, both outside the {@code eventing} module.
 *
 * <p>Same reasoning as {@link com.salespipe.eventing.consumer}'s {@code
 * package-info.java}: without this annotation, {@code
 * com.salespipe.ModuleBoundaryTest} rejects any cross-module use of {@code
 * OutboxRecorder} as a Spring Modulith boundary violation.
 */
@org.springframework.modulith.NamedInterface("outbox")
package com.salespipe.eventing.outbox;
