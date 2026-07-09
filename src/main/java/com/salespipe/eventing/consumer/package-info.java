/**
 * Exposed as a Spring Modulith {@code NamedInterface} so other modules can subclass
 * {@link com.salespipe.eventing.consumer.IdempotentConsumer} — the documented extension
 * point for T2.4 ("Base consumer that ... check/inserts processed_events ...";
 * IdempotentConsumer's own javadoc: "Subclassing contract"). Every real consumer built
 * on this framework necessarily lives outside the {@code eventing} module (e.g. T2.5's
 * {@code activity.consumer.*}, T2.6's {@code emailtracking} consumers, T2.7's
 * notification consumer) since {@code eventing} is the transport-layer module, not a
 * business-logic one.
 *
 * <p>Without this {@code @NamedInterface}, Spring Modulith's default boundary
 * verification (see {@code com.salespipe.ModuleBoundaryTest}) treats every subpackage
 * other than a module's root as internal, so any cross-module subclass of {@link
 * com.salespipe.eventing.consumer.IdempotentConsumer} fails {@code
 * ApplicationModules.verify()} — this went unnoticed through T2.4 because that task's
 * only concrete subclasses were nested test classes inside the {@code eventing} module
 * itself ({@code IdempotentConsumerIT}), which is same-module and therefore never
 * exercised the boundary. Added here (T2.5, the first task to actually place a
 * concrete {@link com.salespipe.eventing.consumer.IdempotentConsumer} subclass in
 * another module) so the documented extension pattern is actually usable.
 */
@org.springframework.modulith.NamedInterface("consumer")
package com.salespipe.eventing.consumer;
