package com.salespipe.notification.infra.email;

import com.salespipe.common.resilience.IdempotencyStore;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real SendGrid {@link EmailProvider} (T4.4), wired only when {@code app.email.enabled=true}
 * (see {@link EmailProviderConfig}). Every send goes through, in order:
 * <ol>
 *   <li><b>Idempotency</b> — {@link IdempotencyStore#firstSeen}; a duplicate key is a
 *       successful no-op, so a redelivered notification event never double-sends.</li>
 *   <li><b>TimeLimiter</b> — the SendGrid HTTP call runs on a bounded executor with a
 *       hard timeout, so a hung SendGrid can't block a consumer thread indefinitely
 *       (overview §6.3 "Timeouts on every outbound HTTP call; no unbounded waits").</li>
 *   <li><b>Retry</b> — transient failures retried with backoff.</li>
 *   <li><b>CircuitBreaker</b> — a sustained SendGrid outage opens the breaker so we stop
 *       hammering it; while open, sends fail fast (the notification row is already
 *       persisted by the consumer, so the email is best-effort).</li>
 * </ol>
 * The resilience4j instances are all named {@code emailProvider} (config in
 * {@code application.yml}).
 */
public class SendGridEmailProvider implements EmailProvider {

    public static final String INSTANCE = "emailProvider";
    private static final Logger log = LoggerFactory.getLogger(SendGridEmailProvider.class);

    private final SendGrid sendGrid;
    private final EmailProperties properties;
    private final IdempotencyStore idempotencyStore;
    private final ExecutorService executor;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public SendGridEmailProvider(
        SendGrid sendGrid,
        EmailProperties properties,
        IdempotencyStore idempotencyStore,
        ExecutorService emailExecutor,
        CircuitBreakerRegistry circuitBreakerRegistry,
        RetryRegistry retryRegistry,
        TimeLimiterRegistry timeLimiterRegistry
    ) {
        this.sendGrid = sendGrid;
        this.properties = properties;
        this.idempotencyStore = idempotencyStore;
        this.executor = emailExecutor;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE);
        this.retry = retryRegistry.retry(INSTANCE);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE);
    }

    @Override
    public void send(EmailMessage message, String idempotencyKey) {
        Duration ttl = Duration.ofSeconds(properties.getIdempotencyTtlSeconds());
        if (!idempotencyStore.firstSeen(idempotencyKey, ttl)) {
            log.debug("Email suppressed (duplicate idempotencyKey={})", idempotencyKey);
            return;
        }

        Supplier<CompletableFuture<Response>> futureSupplier = () ->
            CompletableFuture.supplyAsync(() -> callSendGrid(message), executor);

        // Compose TimeLimiter -> Retry -> CircuitBreaker around the async SendGrid call.
        // decorateFutureSupplier yields a Callable; wrap it as a Supplier (rethrowing
        // checked exceptions unchecked) so Retry/CircuitBreaker can decorate it.
        Callable<Response> timed = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);
        Supplier<Response> decorated = () -> {
            try {
                return timed.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new EmailSendException("email send failed", e);
            }
        };
        decorated = Retry.decorateSupplier(retry, decorated);
        decorated = CircuitBreaker.decorateSupplier(circuitBreaker, decorated);

        try {
            Response response = decorated.get();
            if (response.getStatusCode() >= 300) {
                throw new EmailSendException(
                    "SendGrid returned status " + response.getStatusCode() + ": " + response.getBody());
            }
            log.info("Sent email to={} subject=\"{}\" status={}",
                message.toEmail(), message.subject(), response.getStatusCode());
        } catch (Exception e) {
            // The notification row is already persisted; email is best-effort. Log and
            // swallow so a SendGrid outage does not fail (and DLQ) the notification event.
            log.warn("Email send failed to={} subject=\"{}\": {}",
                message.toEmail(), message.subject(), e.toString());
        }
    }

    private Response callSendGrid(EmailMessage message) {
        Mail mail = new Mail(
            new Email(properties.getFrom()),
            message.subject(),
            new Email(message.toEmail()),
            new Content("text/plain", message.body())
        );
        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        try {
            request.setBody(mail.build());
            return sendGrid.api(request);
        } catch (IOException e) {
            throw new EmailSendException("SendGrid call failed", e);
        }
    }

    /** Unchecked wrapper so the resilience4j-decorated supplier can propagate send failures. */
    static final class EmailSendException extends RuntimeException {
        EmailSendException(String message) { super(message); }
        EmailSendException(String message, Throwable cause) { super(message, cause); }
    }
}
