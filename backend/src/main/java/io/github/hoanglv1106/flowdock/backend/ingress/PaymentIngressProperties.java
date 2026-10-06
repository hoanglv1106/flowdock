package io.github.hoanglv1106.flowdock.backend.ingress;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("flowdock.ingress")
public record PaymentIngressProperties(
        @DefaultValue("flowdock.payment.events") String sourceTopic,
        @DefaultValue("10s") Duration acknowledgementTimeout) {
    public PaymentIngressProperties {
        if (sourceTopic == null || !sourceTopic.matches("[a-zA-Z0-9._-]{1,128}")
                || sourceTopic.equals(".") || sourceTopic.equals("..")) {
            throw new IllegalArgumentException("sourceTopic must be a valid Kafka topic of 1..128 characters");
        }
        if (acknowledgementTimeout == null
                || acknowledgementTimeout.compareTo(Duration.ofMillis(1)) < 0
                || acknowledgementTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("acknowledgementTimeout must be between 1ms and 30s");
        }
    }
}
