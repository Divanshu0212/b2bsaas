package com.salespipe.eventing.consumer;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Abstract base for idempotent Kafka consumers (T2.4, docs/plan/00-overview.md §4-5,
 * §6.3; phase-2 plan T2.4).
 *
 * <p><b>Dedupe.</b> In one transaction: attempt the {@code processed_events} insert
 * keyed on {@code (consumerGroup(), event_id)}. On unique-violation ({@link
 * DataIntegrityViolationException}), the event has already been processed — skip the
 * business handler entirely and roll the transaction back (the failed insert attempt
 * already aborted the underlying DB transaction — Postgres refuses further statements
 * on it until rollback — and there is nothing to undo: the original delivery's dedupe
 * row is already durably committed). Redelivery is therefore a no-op read-only-in-effect
 * transaction. Otherwise run {@link #handle(InboundEvent)} (the business write) and the
 * dedupe insert in that SAME
 * transaction, so a crash between "dedupe row committed" and "business write committed"
 * cannot happen — this is the exact bug called out in the plan's "Risks/gotchas"
 * ("Consumer dedupe insert must share the handler's transaction").
 *
 * <p>Deliberately an insert-and-catch-violation, not a check-then-insert: a
 * check-then-insert has a race window where two concurrent redeliveries could both
 * pass the "not yet processed" check before either commits its insert. Relying on the
 * database's own composite-PK uniqueness constraint closes that window; see {@link
 * ProcessedEventRepository}.
 *
 * <p><b>Retry + DLQ.</b> Handler failures are retried with Resilience4j exponential
 * backoff ({@code resilience4j.retry.instances.idempotentConsumer}, overview §6.3).
 * Once attempts are exhausted, the raw message is published to {@code <topic>.DLQ}
 * with a failure reason via {@link DlqPublisher} — never silently dropped. The
 * Kafka offset is acknowledged (via {@link Acknowledgment}) in both the success and
 * the DLQ-routed case, since in both cases this consumer group is "done" with the
 * message; only a DLQ-publish failure itself leaves the offset unacked so the whole
 * record (including the retry cycle) is redelivered next poll.
 *
 * <p><b>Subclassing contract:</b> implement {@link #consumerGroup()} (stable id used
 * as the {@code processed_events.consumer_group} value — independent from the actual
 * Kafka {@code group.id}, though they'll typically match) and {@link
 * #handle(InboundEvent)} (the actual business write). Call {@link #consume} from a
 * concrete {@code @KafkaListener} method.
 */
public abstract class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    /** Resilience4j retry instance name shared by every {@link IdempotentConsumer} subclass. */
    public static final String RETRY_INSTANCE_NAME = "idempotentConsumer";

    private final InboundEventNormalizer normalizer;
    private final ProcessedEventRepository processedEventRepository;
    private final DlqPublisher dlqPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Retry retry;

    protected IdempotentConsumer(
        InboundEventNormalizer normalizer,
        ProcessedEventRepository processedEventRepository,
        DlqPublisher dlqPublisher,
        TransactionTemplate transactionTemplate,
        RetryRegistry retryRegistry
    ) {
        this.normalizer = normalizer;
        this.processedEventRepository = processedEventRepository;
        this.dlqPublisher = dlqPublisher;
        this.transactionTemplate = transactionTemplate;
        this.retry = retryRegistry.retry(RETRY_INSTANCE_NAME);
    }

    /**
     * Stable dedupe-scope id — the {@code consumer_group} column value in {@code
     * processed_events}. A logical name for this handler, not necessarily the Kafka
     * consumer {@code group.id} (though in practice they should usually match 1:1).
     */
    protected abstract String consumerGroup();

    /** The business write for a not-yet-processed event. Runs inside the dedupe transaction. */
    protected abstract void handle(InboundEvent event) throws Exception;

    /**
     * Drive one Kafka record through normalize → dedupe-insert → (skip | handle+dedupe,
     * same tx) → on handler failure: Resilience4j retry, then DLQ on exhaustion.
     * Concrete {@code @KafkaListener} methods call this directly.
     */
    public final void consume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        InboundEvent event;
        try {
            event = normalizer.normalize(record);
        } catch (Exception e) {
            // Cannot even parse the envelope — nothing meaningful to dedupe/retry against.
            // Straight to DLQ so it isn't silently dropped or stuck redelivering forever.
            log.error("Failed to normalize record on topic={} key={}; routing directly to DLQ",
                record.topic(), record.key(), e);
            dlqPublisher.publish(record.topic(), record.key(), record.value(), 0, e);
            ack.acknowledge();
            return;
        }

        MDC.put("eventId", event.eventId().toString());
        MDC.put("orgId", event.orgId());
        MDC.put("traceId", event.traceId());
        try {
            processWithRetry(record, event);
        } finally {
            MDC.remove("eventId");
            MDC.remove("orgId");
            MDC.remove("traceId");
        }
        ack.acknowledge();
    }

    private void processWithRetry(ConsumerRecord<String, byte[]> record, InboundEvent event) {
        int[] attempts = {0};
        Supplier<Void> attempt = () -> {
            attempts[0]++;
            runInDedupeTransaction(event);
            return null;
        };
        try {
            Retry.decorateSupplier(retry, attempt).get();
        } catch (Exception finalFailure) {
            // Unwrap the HandlerException marker so the DLQ failure-reason header
            // reports the subclass's actual exception, not this class's internal
            // checked-exception-through-TransactionTemplate plumbing detail.
            Throwable reason = finalFailure instanceof HandlerException && finalFailure.getCause() != null
                ? finalFailure.getCause()
                : finalFailure;
            dlqPublisher.publish(record.topic(), record.key(), record.value(), attempts[0], reason);
        }
    }

    /**
     * The one transaction described in the class javadoc: dedupe-insert, then (if not a
     * duplicate) the business handler, both committed together.
     */
    private void runInDedupeTransaction(InboundEvent event) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                processedEventRepository.saveAndFlush(new ProcessedEvent(consumerGroup(), event.eventId()));
            } catch (DataIntegrityViolationException duplicate) {
                // Already processed by this consumer group (the composite PK unique
                // constraint fired) — skip the handler entirely. The failed insert
                // already aborted the underlying DB transaction (Postgres will reject
                // any further statement until rollback), and there's nothing to undo —
                // the other delivery's dedupe row is already durably committed. Roll
                // back cleanly and return; the listener still acks the Kafka offset
                // (see #consume) since this consumer group is done with the message.
                log.info("Duplicate delivery for consumerGroup={} eventId={}, skipping handler",
                    consumerGroup(), event.eventId());
                status.setRollbackOnly();
                return;
            }

            try {
                handle(event);
            } catch (Exception e) {
                status.setRollbackOnly();
                throw new HandlerException(e);
            }
        });
    }

    /** Unchecked wrapper so {@link #handle}'s checked {@code throws Exception} can propagate through {@link TransactionTemplate}. */
    static final class HandlerException extends RuntimeException {
        HandlerException(Throwable cause) {
            super(cause);
        }
    }
}
