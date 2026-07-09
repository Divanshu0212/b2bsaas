/**
 * Exposed as a Spring Modulith {@code NamedInterface} so other modules can construct
 * these {@code MeterBinder}s (see {@link com.salespipe.eventing.metrics.MetricsConfig},
 * which wires {@link com.salespipe.common.metrics.DlqDepthMetrics}, {@link
 * com.salespipe.common.metrics.ConsumerLagMetrics}, and {@link
 * com.salespipe.common.metrics.RelayLagMetrics} against Kafka-{@code AdminClient}- and
 * repository-backed sources). Same reasoning as {@link com.salespipe.common.tenant}'s
 * {@code package-info.java}: without this annotation, {@code
 * com.salespipe.ModuleBoundaryTest} rejects the cross-module construction as a Spring
 * Modulith boundary violation. This is a one-way {@code eventing -> common.metrics}
 * dependency (these classes never reference {@code eventing} back), so it doesn't
 * reintroduce the {@code common <-> eventing} cycle {@code MetricsConfig}'s own javadoc
 * explains.
 */
@org.springframework.modulith.NamedInterface("metrics")
package com.salespipe.common.metrics;
