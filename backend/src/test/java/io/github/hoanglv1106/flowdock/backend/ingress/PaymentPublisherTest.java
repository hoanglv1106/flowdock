package io.github.hoanglv1106.flowdock.backend.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hoanglv1106.flowdock.core.payment.PaymentEnvelope;
import io.github.hoanglv1106.flowdock.core.payment.PaymentEventIdentity;
import io.github.hoanglv1106.flowdock.core.payment.PaymentPayload;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentPublisherTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PaymentEnvelope envelope = PaymentEnvelope.of(
            new PaymentEventIdentity(UUID.randomUUID(), "payments", "event-1"),
            new PaymentPayload("tx-1", 100, "USD"));

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);

    private PaymentPublisher publisher() {
        return new PaymentPublisher(template, new PaymentEnvelopeSerializer(mapper),
                new PaymentIngressProperties("flowdock.payment.events", Duration.ofSeconds(10)));
    }

    @Test
    void confirmedAcknowledgementReturnsExactBrokerCoordinates() {
        var metadata = new RecordMetadata(new TopicPartition("flowdock.payment.events", 2),
                41L, 0, -1L, 0, 0);
        var result = new SendResult<>(new ProducerRecord<>("flowdock.payment.events", "event-1", "body"), metadata);
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));

        var receipt = publisher().publish(envelope);

        assertThat(receipt.topic()).isEqualTo("flowdock.payment.events");
        assertThat(receipt.partition()).isEqualTo(2);
        assertThat(receipt.offset()).isEqualTo(41L);
        assertThat(receipt.emissionId()).isEqualTo(envelope.emissionId());
        assertThat(receipt.payloadHash()).isEqualTo(envelope.canonicalPayloadHash());
    }

    @Test
    void failedBrokerAcknowledgementNeverReturnsReceipt() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThatThrownBy(() -> publisher().publish(envelope))
                .isInstanceOf(PaymentPublisher.PublishFailedException.class)
                .hasRootCauseMessage("broker unavailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acknowledgementTimeoutNeverReturnsReceipt() throws Exception {
        CompletableFuture<SendResult<String, String>> pending = mock(CompletableFuture.class);
        when(pending.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new TimeoutException("ack deadline"));
        when(template.send(anyString(), anyString(), anyString())).thenReturn(pending);

        assertThatThrownBy(() -> publisher().publish(envelope))
                .isInstanceOf(PaymentPublisher.PublishFailedException.class)
                .hasCauseInstanceOf(TimeoutException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void synchronousSendFailureNeverReturnsReceipt() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("producer closed"));
        assertThatThrownBy(() -> publisher().publish(envelope))
                .isInstanceOf(PaymentPublisher.PublishFailedException.class)
                .hasRootCauseMessage("producer closed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptionPreservesThreadFlagAndNeverReturnsReceipt() throws Exception {
        CompletableFuture<SendResult<String, String>> pending = mock(CompletableFuture.class);
        when(pending.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new InterruptedException("cancelled"));
        when(template.send(anyString(), anyString(), anyString())).thenReturn(pending);
        try {
            assertThatThrownBy(() -> publisher().publish(envelope))
                    .isInstanceOf(PaymentPublisher.PublishFailedException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void serializationPreservesLogicalPhysicalAndBusinessFields() throws Exception {
        String json = new PaymentEnvelopeSerializer(mapper).serialize(envelope);
        assertThat(mapper.readValue(json, PaymentEnvelope.class)).isEqualTo(envelope);
        assertThat(mapper.readTree(json).get("payload").get("amount").isIntegralNumber()).isTrue();
        assertThat(mapper.readTree(json).get("payload").has("amount_cents")).isFalse();
    }
}
