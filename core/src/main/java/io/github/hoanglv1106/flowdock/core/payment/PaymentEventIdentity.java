package io.github.hoanglv1106.flowdock.core.payment;

import java.util.Objects;
import java.util.UUID;

/** Logical run-scoped identity per contracts.md section 3.1. */
public record PaymentEventIdentity(UUID runId, String pipelineId, String eventId) {
    public PaymentEventIdentity {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        pipelineId = requireBounded(pipelineId, "pipelineId");
        eventId = requireBounded(eventId, "eventId");
    }

    private static String requireBounded(String value, String field) {
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
