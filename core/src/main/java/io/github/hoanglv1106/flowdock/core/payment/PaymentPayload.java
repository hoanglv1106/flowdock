package io.github.hoanglv1106.flowdock.core.payment;

import java.util.Objects;

/**
 * Business payment attributes. The canonical hash covers exactly these three fields
 * (contracts.md section 2.2); transport and logical identifiers are excluded.
 */
public record PaymentPayload(String transactionId, long amount, String currency) {
    public static final long MIN_AMOUNT = 1L;
    public static final long MAX_AMOUNT = 9007199254740991L;

    public PaymentPayload {
        transactionId = requireBoundedIdentifier(transactionId, "transactionId");
        currency = requireBoundedIdentifier(currency, "currency");
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("amount must be between " + MIN_AMOUNT + " and " + MAX_AMOUNT);
        }
    }

    private static String requireBoundedIdentifier(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        if (value.length() > IdentityLimits.MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(field + " must not exceed " + IdentityLimits.MAX_IDENTIFIER_LENGTH + " characters");
        }
        return value;
    }
}
