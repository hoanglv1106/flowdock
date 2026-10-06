package io.github.hoanglv1106.flowdock.backend.ingress;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIngressPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentIngressProperties.class)
    static class PropertiesConfiguration {
    }

    @Test
    void defaultsAndOverridesBindThroughSpring() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(PaymentIngressProperties.class))
                    .isEqualTo(new PaymentIngressProperties("flowdock.payment.events", Duration.ofSeconds(10)));
        });
        context.withPropertyValues("flowdock.ingress.source-topic=payments.test",
                "flowdock.ingress.acknowledgement-timeout=2s").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(PaymentIngressProperties.class))
                    .isEqualTo(new PaymentIngressProperties("payments.test", Duration.ofSeconds(2)));
        });
    }

    @Test
    void invalidConfigurationFailsStartup() {
        context.withPropertyValues("flowdock.ingress.acknowledgement-timeout=0ms").run(application ->
                assertThat(application).hasFailed());
        context.withPropertyValues("flowdock.ingress.source-topic=invalid topic").run(application ->
                assertThat(application).hasFailed());
    }

    @Test
    void limitsRejectInvalidTopicsAndUnboundedTimeouts() {
        for (String topic : new String[] {"", ".", "..", "x".repeat(129), "invalid topic"}) {
            assertThatThrownBy(() -> new PaymentIngressProperties(topic, Duration.ofSeconds(10)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofNanos(1), Duration.ofSeconds(31)}) {
            assertThatThrownBy(() -> new PaymentIngressProperties("payments", timeout))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
