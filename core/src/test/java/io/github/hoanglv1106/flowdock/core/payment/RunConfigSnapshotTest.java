package io.github.hoanglv1106.flowdock.core.payment;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunConfigSnapshotTest {
    @Test
    void snapshotHoldsTheFrozenRunConfiguration() {
        var snapshot = new RunConfigSnapshot("run-1", 1, "flowdock.payment.events", "flowdock.payment.dlq", "USD");

        assertThat(snapshot.templateVersion()).isEqualTo(1);
        assertThat(snapshot.sourceTopic()).isEqualTo("flowdock.payment.events");
        assertThat(snapshot.expectedCurrency()).isEqualTo("USD");
    }

    @Test
    void invalidVersionOrMissingTopicsAreRejected() {
        assertThatThrownBy(() -> new RunConfigSnapshot("run-1", 0, "a", "b", "USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunConfigSnapshot("run-1", 1, "", "b", "USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunConfigSnapshot(null, 1, "a", "b", "USD"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void identityRequiresARealRunIdAndBoundedIdentifiers() {
        UUID runId = UUID.randomUUID();

        assertThat(new PaymentEventIdentity(runId, "pipe-1", "event-1").runId()).isEqualTo(runId);
        assertThatThrownBy(() -> new PaymentEventIdentity(null, "pipe-1", "event-1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentEventIdentity(runId, "pipe-1", "e".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
