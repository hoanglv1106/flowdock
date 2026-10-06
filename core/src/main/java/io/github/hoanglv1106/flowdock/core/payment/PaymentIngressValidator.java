package io.github.hoanglv1106.flowdock.core.payment;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Ingress validation per contracts.md section 1.1 and 1.2.
 *
 * <p>Order matters and is contractual: schema/field validation happens BEFORE the USD filter,
 * and null or malformed values are rejected before any canonical hashing (section 2.3).
 */
public final class PaymentIngressValidator {
    private static final Set<String> ISO_CODES = isoCodes();

    private PaymentIngressValidator() {
    }

    private static Set<String> isoCodes() {
        Set<String> codes = new java.util.HashSet<>();
        for (Currency currency : Currency.getAvailableCurrencies()) {
            codes.add(currency.getCurrencyCode());
        }
        return Set.copyOf(codes);
    }

    /**
     * Validates raw ingress fields.
     *
     * @throws IngressRejectionException when any field is missing, malformed or out of bounds
     */
    public static PaymentPayload validate(String transactionId, Long amount, String currency) {
        requirePresent(transactionId, "transactionId");
        requirePresent(currency, "currency");
        Objects.requireNonNull(amount, "amount is required");

        String normalisedCurrency = currency.trim().toUpperCase(Locale.ROOT);
        if (normalisedCurrency.length() != 3 || !ISO_CODES.contains(normalisedCurrency)) {
            throw new IngressRejectionException(
                    "currency must be a valid ISO-4217 alphabetic code, received: " + currency);
        }
        if (amount < PaymentPayload.MIN_AMOUNT || amount > PaymentPayload.MAX_AMOUNT) {
            throw new IngressRejectionException("amount must be between "
                    + PaymentPayload.MIN_AMOUNT + " and " + PaymentPayload.MAX_AMOUNT);
        }
        return new PaymentPayload(transactionId, amount, normalisedCurrency);
    }

    private static void requirePresent(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IngressRejectionException(field + " is required");
        }
    }

    /**
     * Classifies a validated payload for routing. Non-USD passes validation and is then FILTERED
     * explicitly, which is an ingress outcome and never a worker failure or dead-letter event.
     */
    public static IngressRouting route(PaymentPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return "USD".equals(payload.currency()) ? IngressRouting.PUBLISH : IngressRouting.FILTERED;
    }

    /** Ingress outcome for an already validated payload. */
    public enum IngressRouting {
        /** Persist and publish to the payment topic. */
        PUBLISH,
        /** Valid non-USD currency acknowledged without publishing. */
        FILTERED
    }

    /** Thrown when ingress rejects input; the API maps this to HTTP 400. */
    public static final class IngressRejectionException extends RuntimeException {
        public IngressRejectionException(String message) {
            super(message);
        }
    }
}
