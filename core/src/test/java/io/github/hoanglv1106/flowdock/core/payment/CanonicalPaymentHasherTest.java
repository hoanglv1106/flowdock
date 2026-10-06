package io.github.hoanglv1106.flowdock.core.payment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalPaymentHasherTest {

    /**
     * External vectors. Every expected digest below is the SHA-256 of the canonical UTF-8 bytes
     * computed outside this code path with an independent implementation of RFC 8785 section
     * 3.2.2.2 string serialization in Python 3. The plain case was additionally reproduced with
     * .NET System.Security.Cryptography.SHA256, which agreed byte for byte. These vectors pin the
     * production hasher to externally derived values instead of echoing its own output.
     *
     * <p>They are hand-checked vectors for this project's field set, not yet the formal
     * RFC 8785 test-suite vectors that contracts.md section 2.3 still lists as pending.
     */
    @Test
    void hashesMatchIndependentlyComputedVectors() {
        assertVector("{\"amount\":1500,\"currency\":\"USD\",\"transactionId\":\"tx-20261005-001\"}",
                "0e3765b7724b99422a8e59ef3a1ad894e258603c77cd9e638b73f3dd1062f7d2",
                new PaymentPayload("tx-20261005-001", 1500L, "USD"));
        assertVector("{\"amount\":1500,\"currency\":\"USD\",\"transactionId\":\"tx\\\"quote\"}",
                "18acfe0739fe343f07654316ea8ec2bc2d8e2c79317a457699df7fce46c5efba",
                new PaymentPayload("tx\"quote", 1500L, "USD"));
        assertVector("{\"amount\":1500,\"currency\":\"USD\",\"transactionId\":\"tx\\\\slash\"}",
                "225cdb7d9e1d9fda4a067bf6319ab6b038bb609d1174acaf5e8ca797027ba0ed",
                new PaymentPayload("tx\\slash", 1500L, "USD"));
        assertVector("{\"amount\":1500,\"currency\":\"USD\",\"transactionId\":\"tx\\u0001\\u001f\"}",
                "893112e2538a00ad87b8446fd5bd3127e52163917d8197156e4a8e8031264780",
                new PaymentPayload("tx\u0001\u001f", 1500L, "USD"));
        assertVector("{\"amount\":1500,\"currency\":\"USD\",\"transactionId\":\"tx\\t\\n\\r\\b\\f\"}",
                "32902743b2cf8e85b37e78652468be6c3e8574b9b1dff14f7c32559631582044",
                new PaymentPayload("tx\t\n\r\b\f", 1500L, "USD"));
        assertVector("{\"amount\":9007199254740991,\"currency\":\"USD\",\"transactionId\":\"tx-max\"}",
                "22a3de84b2ade711bcac5fdae741f8709c08fefd76f217e9a2833b180e0565f4",
                new PaymentPayload("tx-max", 9007199254740991L, "USD"));
        assertVector("{\"amount\":1,\"currency\":\"USD\",\"transactionId\":\"tx-min\"}",
                "2128c6bc40c1ef3ff8982029258895283a339a8f4fdffd4667973158eaf3840e",
                new PaymentPayload("tx-min", 1L, "USD"));
        assertVector("{\"amount\":250,\"currency\":\"USD\",\"transactionId\":\"tx-\u00f6\"}",
                "f0da606b7dc5df1a762aa74a3d6415123b7bade27e22107aa9ed969e60b4bd07",
                new PaymentPayload("tx-\u00f6", 250L, "USD"));
    }

    private static void assertVector(String expectedCanonicalJson, String expectedHash, PaymentPayload payload) {
        assertThat(CanonicalPaymentHasher.canonicalJson(payload)).isEqualTo(expectedCanonicalJson);
        assertThat(new String(CanonicalPaymentHasher.canonicalBytes(payload), StandardCharsets.UTF_8))
                .isEqualTo(expectedCanonicalJson);
        assertThat(CanonicalPaymentHasher.hash(payload))
                .isEqualTo(expectedHash)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void nonAsciiCharactersAreEmittedAsIsRatherThanEscaped() {
        // RFC 8785 3.2.2.2: only U+005C and U+0022 are escaped outside the control range.
        assertThat(CanonicalPaymentHasher.canonicalJson(new PaymentPayload("tx-\u00f6", 250L, "USD")))
                .isEqualTo("{\"amount\":250,\"currency\":\"USD\",\"transactionId\":\"tx-\u00f6\"}")
                .contains("tx-\u00f6")
                .doesNotContain("\\u00f6");

        // The two characters that must be escaped, and a control character that must not be raw.
        assertThat(CanonicalPaymentHasher.canonicalJson(new PaymentPayload("a\"b\\c", 1L, "USD")))
                .contains("\"a\\\"b\\\\c\"");
        assertThat(CanonicalPaymentHasher.canonicalJson(new PaymentPayload("a\u0001b", 1L, "USD")))
                .contains("\"a\\u0001b\"");
    }

    @Test
    void hashingIsStableForEqualPayloadsAndSensitiveToEveryBusinessField() {
        String baseline = CanonicalPaymentHasher.hash(new PaymentPayload("tx-1", 100L, "USD"));

        assertThat(CanonicalPaymentHasher.hash(new PaymentPayload("tx-1", 100L, "USD"))).isEqualTo(baseline);
        assertThat(CanonicalPaymentHasher.hash(new PaymentPayload("tx-2", 100L, "USD"))).isNotEqualTo(baseline);
        assertThat(CanonicalPaymentHasher.hash(new PaymentPayload("tx-1", 101L, "USD"))).isNotEqualTo(baseline);
        assertThat(CanonicalPaymentHasher.hash(new PaymentPayload("tx-1", 100L, "EUR"))).isNotEqualTo(baseline);
    }

    @Test
    void identitiesAreExcludedFromTheHash() {
        var payload = new PaymentPayload("tx-1", 100L, "USD");
        var first = PaymentEnvelope.of(new PaymentEventIdentity(UUID.randomUUID(), "pipe-a", "event-1"), payload);
        var second = PaymentEnvelope.of(new PaymentEventIdentity(UUID.randomUUID(), "pipe-b", "event-2"), payload);

        assertThat(first.canonicalPayloadHash()).isEqualTo(second.canonicalPayloadHash());
        assertThat(first.emissionId()).isNotEqualTo(second.emissionId());
    }

    @Test
    void loneSurrogatesTerminateSerialization() {
        // RFC 8785 3.2.2.2 requires an error for lone surrogates; well-formed pairs serialize as is.
        assertThatThrownBy(() -> CanonicalPaymentHasher.hash(new PaymentPayload("tx-\uD83D", 1L, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lone surrogate");
        assertThatThrownBy(() -> CanonicalPaymentHasher.hash(new PaymentPayload("tx-\uDE00", 1L, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lone surrogate");

        assertThat(CanonicalPaymentHasher.hash(new PaymentPayload("tx-\uD83D\uDE00", 1L, "USD")))
                .hasSize(64);
    }

    @Test
    void amountBoundsAreEnforcedIncludingTheSafeIntegerLimit() {
        assertThat(new PaymentPayload("tx-1", 1L, "USD").amount()).isEqualTo(1L);
        assertThat(new PaymentPayload("tx-1", 9007199254740991L, "USD").amount()).isEqualTo(9007199254740991L);

        assertThatThrownBy(() -> new PaymentPayload("tx-1", 0L, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentPayload("tx-1", -1L, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentPayload("tx-1", 9007199254740992L, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiersAreBoundedAndNonNull() {
        assertThatThrownBy(() -> new PaymentPayload("", 1L, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentPayload("tx-1", 1L, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentPayload("x".repeat(65), 1L, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
