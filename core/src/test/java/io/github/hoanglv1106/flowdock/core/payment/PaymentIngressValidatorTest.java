package io.github.hoanglv1106.flowdock.core.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIngressValidatorTest {

    @Test
    void validUsdPayloadPassesValidationAndRoutesToPublish() {
        var payload = PaymentIngressValidator.validate("tx-1", 1500L, "USD");

        assertThat(payload.transactionId()).isEqualTo("tx-1");
        assertThat(payload.amount()).isEqualTo(1500L);
        assertThat(payload.currency()).isEqualTo("USD");
        assertThat(PaymentIngressValidator.route(payload)).isEqualTo(PaymentIngressValidator.IngressRouting.PUBLISH);
    }

    @Test
    void validNonUsdCurrencyIsFilteredRatherThanRejected() {
        // contracts.md section 1.1: valid ISO non-USD is FILTERED, not an error and not a DLQ event.
        for (String code : new String[] {"EUR", "GBP", "JPY"}) {
            var payload = PaymentIngressValidator.validate("tx-1", 1500L, code);

            assertThat(payload.currency()).isEqualTo(code);
            assertThat(PaymentIngressValidator.route(payload))
                    .isEqualTo(PaymentIngressValidator.IngressRouting.FILTERED);
        }
    }

    @Test
    void lowercaseCurrencyIsNormalisedBeforeRouting() {
        assertThat(PaymentIngressValidator.validate("tx-1", 1500L, "usd").currency()).isEqualTo("USD");
        assertThat(PaymentIngressValidator.route(PaymentIngressValidator.validate("tx-1", 1500L, "eur")))
                .isEqualTo(PaymentIngressValidator.IngressRouting.FILTERED);
    }

    @Test
    void malformedOrMissingCurrencyIsRejectedBeforeFiltering() {
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", 1500L, "US"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", 1500L, "USDD"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", 1500L, "ZZZ"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", 1500L, "12$"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", 1500L, null))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", 1500L, "  "))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
    }

    @Test
    void missingOrBlankTransactionIdIsRejected() {
        assertThatThrownBy(() -> PaymentIngressValidator.validate(null, 1500L, "USD"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
        assertThatThrownBy(() -> PaymentIngressValidator.validate("   ", 1500L, "USD"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
    }

    @Test
    void missingAmountIsRejectedSeparatelyFromBounds() {
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", null, "USD"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amount is required");
    }

    @Test
    void amountBoundsAreEnforcedAtIngress() {
        assertThat(PaymentIngressValidator.validate("tx-1", 1L, "USD").amount()).isEqualTo(1L);
        assertThat(PaymentIngressValidator.validate("tx-1", 9007199254740991L, "USD").amount())
                .isEqualTo(9007199254740991L);

        // Zero and negatives are rejected rather than silently accepted as authorisations.
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", 0L, "USD"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", -1L, "USD"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", 9007199254740992L, "USD"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class);
    }

    @Test
    void rejectedInputNeverReachesCanonicalHashing() {
        // A malformed currency must fail at ingress, so no payload exists to hash at all.
        assertThatThrownBy(() -> PaymentIngressValidator.validate("tx-1", 1500L, "ZZZ"))
                .isInstanceOf(PaymentIngressValidator.IngressRejectionException.class)
                .hasMessageContaining("ISO-4217");
    }
}
