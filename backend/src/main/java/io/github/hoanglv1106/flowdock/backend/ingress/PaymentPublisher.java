package io.github.hoanglv1106.flowdock.backend.ingress;

import io.github.hoanglv1106.flowdock.core.payment.PaymentEnvelope;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes payment envelopes to Kafka and waits for the broker acknowledgement.
 *
 * <p>Contract (contracts.md section 1.1 and ADR-002): ingress must not answer "published"
 * until the broker has acknowledged the record. Any publish failure propagates to the caller
 * so the API can answer with an error instead of a false success.
 */
@Component
public class PaymentPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PaymentEnvelopeSerializer serializer;
    private final String sourceTopic;
    private final long acknowledgementTimeoutMillis;

    public PaymentPublisher(KafkaTemplate<String, String> kafkaTemplate,
                            PaymentEnvelopeSerializer serializer,
                            PaymentIngressProperties properties) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.sourceTopic = properties.sourceTopic();
        this.acknowledgementTimeoutMillis = properties.acknowledgementTimeout().toMillis();
    }

    /**
     * Publishes one envelope and blocks until the broker acknowledges it.
     *
     * @return the acknowledged physical coordinates and the locally computed payload hash
     * @throws PublishFailedException when the broker does not acknowledge within the deadline,
     *                               or acknowledges with an error
     */
    public PublishReceipt publish(PaymentEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        String key = envelope.identity().eventId();
        String value = serializer.serialize(envelope);
        String payloadHash = envelope.canonicalPayloadHash();

        try {
            var metadata = kafkaTemplate
                    .send(sourceTopic, key, value)
                    .get(acknowledgementTimeoutMillis, TimeUnit.MILLISECONDS)
                    .getRecordMetadata();
            return new PublishReceipt(
                    metadata.topic(),
                    metadata.partition(),
                    metadata.offset(),
                    envelope.emissionId(),
                    payloadHash);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("publish interrupted before acknowledgement", exception);
        } catch (ExecutionException | TimeoutException exception) {
            // Timeout does not prove absence from Kafka: a late acknowledgement is still possible.
            throw new PublishFailedException("no confirmed acknowledgement; publication may be ambiguous", exception);
        } catch (RuntimeException exception) {
            throw new PublishFailedException("publish failed before confirmed acknowledgement", exception);
        }
    }

    /** Physical coordinates acknowledged by the broker, plus the local payload hash. */
    public record PublishReceipt(String topic, int partition, long offset, String emissionId, String payloadHash) {
    }

    /** Raised when a publish is not acknowledged; callers must not treat this as success. */
    public static class PublishFailedException extends RuntimeException {
        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
