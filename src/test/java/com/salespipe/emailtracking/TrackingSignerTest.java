package com.salespipe.emailtracking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salespipe.emailtracking.security.TrackingProperties;
import com.salespipe.emailtracking.security.TrackingSigner;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link TrackingSigner} (plan T2.6 acceptance: "Unit test: HMAC signer
 * (valid sig accepted, tampered sig rejected, different tracking_id with same sig
 * rejected)"). No Spring context needed -- constructs the signer directly against a
 * fixed test secret.
 */
class TrackingSignerTest {

    private TrackingSigner signer;

    @BeforeEach
    void setUp() {
        TrackingProperties props = new TrackingProperties();
        props.setSecret("unit-test-secret-not-used-anywhere-else-xxxxxxxxxxxxxxxxxxxx");
        signer = new TrackingSigner(props);
    }

    @Test
    void validSignatureIsAccepted() {
        UUID trackingId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String sig = signer.sign(trackingId, orgId);

        assertThat(signer.isValid(trackingId, orgId, sig)).isTrue();
    }

    @Test
    void tamperedSignatureIsRejected() {
        UUID trackingId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String sig = signer.sign(trackingId, orgId);

        // Flip a middle character to simulate tampering while staying valid base64url.
        // (Not the last character: a 32-byte HMAC-SHA256 output base64-encodes to 43
        // chars with 2 unused/don't-care bits in the final character, so some
        // last-character substitutions decode to byte-identical signatures and
        // wouldn't actually test tampering. A middle-character flip always changes
        // the decoded bytes.)
        int mid = sig.length() / 2;
        char midChar = sig.charAt(mid);
        char replacement = midChar == 'A' ? 'B' : 'A';
        String tampered = sig.substring(0, mid) + replacement + sig.substring(mid + 1);

        assertThat(signer.isValid(trackingId, orgId, tampered)).isFalse();
    }

    @Test
    void completelyDifferentSignatureIsRejected() {
        UUID trackingId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        assertThat(signer.isValid(trackingId, orgId, "not-a-real-signature")).isFalse();
    }

    @Test
    void sameSignatureReusedForDifferentTrackingIdIsRejected() {
        UUID trackingId1 = UUID.randomUUID();
        UUID trackingId2 = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String sigForId1 = signer.sign(trackingId1, orgId);

        assertThat(signer.isValid(trackingId2, orgId, sigForId1)).isFalse();
    }

    @Test
    void sameSignatureReusedForDifferentOrgIsRejected() {
        // Cross-org forgery resistance: org_id is baked into the signed material (see
        // TrackingSigner javadoc), so a sig valid for org A must not validate for org B
        // even against the identical tracking_id.
        UUID trackingId = UUID.randomUUID();
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        String sigForOrgA = signer.sign(trackingId, orgA);

        assertThat(signer.isValid(trackingId, orgB, sigForOrgA)).isFalse();
    }

    @Test
    void blankOrNullSignatureIsRejected() {
        UUID trackingId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        assertThat(signer.isValid(trackingId, orgId, null)).isFalse();
        assertThat(signer.isValid(trackingId, orgId, "")).isFalse();
        assertThat(signer.isValid(trackingId, orgId, "   ")).isFalse();
    }
}
