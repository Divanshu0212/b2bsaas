package com.salespipe.eventing.admin;

/**
 * A message sitting in a DLQ topic, as surfaced by the DLQ admin API (T4.5). Carries the
 * coordinates needed to replay it ({@code dlqTopic}/{@code partition}/{@code offset}) plus
 * the failure metadata the {@link com.salespipe.eventing.consumer.DlqPublisher} stamped on
 * it ({@code originalTopic}/{@code failureReason}/{@code attempts}).
 */
public record DlqMessage(
    String dlqTopic,
    int partition,
    long offset,
    String key,
    String originalTopic,
    String failureReason,
    String attempts
) {}
