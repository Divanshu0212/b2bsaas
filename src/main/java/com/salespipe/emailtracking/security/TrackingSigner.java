package com.salespipe.emailtracking.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Generates and validates the anti-forgery {@code tracking_sig} for email open/click
 * tokens (overview §4/§6.1, plan T2.6).
 *
 * <h2>Key-material interpretation</h2>
 * The plan's overview §4 sketch says {@code tracking_sig = HMAC(tracking_id, org secret)}
 * and §6.1 calls it an "org secret". This repo's schema has no per-org secret column: the
 * {@code organizations} table (V1__init.sql) is just {@code id, name, plan, created_at}.
 * Introducing one would mean generating/rotating/storing a secret per tenant, which is a
 * real feature (key management, rotation, storage-at-rest) the plan doesn't otherwise
 * scope for Phase 2.
 *
 * <p>The pragmatic reading used here: a single app-wide secret (configured the same way
 * {@code app.jwt.secret} already is — see {@code JwtProperties} — via
 * {@code app.tracking.secret}, defaulting to an env var in application.yml), with
 * {@code org_id} folded into the signed material alongside {@code tracking_id}:
 * <pre>tracking_sig = HMAC-SHA256(tracking_id || ':' || org_id, app-wide secret)</pre>
 * This still achieves both properties the plan cares about:
 * <ul>
 *   <li><b>Cross-org forgery resistance</b>: an attacker who observes org A's valid
 *   {@code (tracking_id, sig)} pair cannot reuse that {@code sig} against a different
 *   {@code tracking_id} claiming to belong to org B — the signature only validates for
 *   the exact {@code (tracking_id, org_id)} pair it was computed over.</li>
 *   <li><b>Tracking-id enumeration resistance</b>: without the app-wide secret, an
 *   attacker cannot compute a valid {@code sig} for a guessed {@code tracking_id}, so
 *   scanning random UUIDs against {@code /emails/{id}/open} cannot produce a "success".</li>
 * </ul>
 * The gap versus a true per-org secret: a full app-secret compromise lets an attacker
 * forge signatures for every org at once rather than just one. That's the same blast
 * radius {@code app.jwt.secret} already has for auth tokens in this codebase, so it's
 * treated as an accepted, documented tradeoff rather than a new risk class — a per-org
 * secret column is a reasonable future hardening step, noted here rather than
 * speculatively built.
 *
 * <h2>Timing-attack resistance</h2>
 * Validation compares the computed vs. supplied signature with
 * {@link MessageDigest#isEqual(byte[], byte[])}, which performs a constant-time
 * comparison (JDK 6u17+), not {@code String.equals}/{@code Arrays.equals}, which
 * short-circuit on the first differing byte and can leak timing information about how
 * many leading bytes matched.
 */
@Component
@EnableConfigurationProperties(TrackingProperties.class)
public class TrackingSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final TrackingProperties properties;

    public TrackingSigner(TrackingProperties properties) {
        this.properties = properties;
    }

    /** Generates a fresh random {@code tracking_id} for a new outbound email. */
    public UUID newTrackingId() {
        return UUID.randomUUID();
    }

    /** Computes {@code tracking_sig} for a given {@code (tracking_id, org_id)} pair. */
    public String sign(UUID trackingId, UUID orgId) {
        byte[] mac = hmac(signedMaterial(trackingId, orgId));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    /**
     * Validates a caller-supplied signature against the given {@code (tracking_id, org_id)}
     * pair using a constant-time comparison. Returns {@code false} for any malformed
     * input (e.g. not valid base64) rather than throwing — callers treat "invalid" and
     * "malformed" identically (both are a rejected request, no DB write, no event).
     */
    public boolean isValid(UUID trackingId, UUID orgId, String suppliedSig) {
        if (suppliedSig == null || suppliedSig.isBlank()) return false;
        byte[] expected = hmac(signedMaterial(trackingId, orgId));
        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(suppliedSig);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, supplied);
    }

    private String signedMaterial(UUID trackingId, UUID orgId) {
        return trackingId.toString() + ':' + orgId.toString();
    }

    private byte[] hmac(String material) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is a JDK-guaranteed algorithm and the key is always non-empty
            // config; this is unreachable in practice, but the checked exceptions on
            // javax.crypto.Mac still have to go somewhere.
            throw new IllegalStateException("Unable to compute HMAC", e);
        }
    }
}
