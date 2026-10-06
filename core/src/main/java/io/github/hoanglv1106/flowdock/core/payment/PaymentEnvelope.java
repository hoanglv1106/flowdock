package io.github.hoanglv1106.flowdock.core.payment;

import java.util.Objects;
import java.util.UUID;

/**
 * Canonical envelope published to the payment topic: logical identity, physical emission
 * identity and the business payload. Section 2.2 excludes both identity levels from the hash.
 */
public record PaymentEnvelope(PaymentEventIdentity identity, String emissionId, PaymentPayload payload) {
    public PaymentEnvelope {
        identity = Objects.requireNonNull(identity, "identity must not be null");
        payload = Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(emissionId, "emissionId must not be null");
        if (emissionId.isEmpty() || emissionId.length() > IdentityLimits.MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("emissionId must be 1.." + IdentityLimits.MAX_IDENTIFIER_LENGTH + " characters");
        }
    }

    /** Creates an envelope with a fresh physical emission identity for one produce attempt. */
    public static PaymentEnvelope of(PaymentEventIdentity identity, PaymentPayload payload) {
        return new PaymentEnvelope(identity, "emission-" + UUID.randomUUID(), payload);
    }

    /** Business payload hash computed locally; never taken from a sender-provided header. */
    public String canonicalPayloadHash() {
        return CanonicalPaymentHasher.hash(payload);
    }
}
