package io.github.hoanglv1106.flowdock.core.payment;

import java.util.Objects;

/**
 * Immutable run configuration snapshot per contracts.md section 9. The map-free shape keeps
 * the future hash input deterministic and makes accidental mutation a compile-time problem.
 */
public record RunConfigSnapshot(String runId, int templateVersion, String sourceTopic,
                               String deadLetterTopic, String expectedCurrency) {
    public RunConfigSnapshot {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sourceTopic = requireText(sourceTopic, "sourceTopic");
        deadLetterTopic = requireText(deadLetterTopic, "deadLetterTopic");
        expectedCurrency = requireText(expectedCurrency, "expectedCurrency");
        if (templateVersion < 1) {
            throw new IllegalArgumentException("templateVersion must be positive");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value;
    }
}
