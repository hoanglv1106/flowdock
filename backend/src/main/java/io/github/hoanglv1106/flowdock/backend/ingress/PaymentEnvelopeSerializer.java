package io.github.hoanglv1106.flowdock.backend.ingress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hoanglv1106.flowdock.core.payment.PaymentEnvelope;
import org.springframework.stereotype.Component;

@Component
public class PaymentEnvelopeSerializer {
    private final ObjectMapper mapper;

    public PaymentEnvelopeSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String serialize(PaymentEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payment envelope cannot be serialized", exception);
        }
    }
}
