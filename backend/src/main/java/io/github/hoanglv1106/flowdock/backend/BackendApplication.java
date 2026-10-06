package io.github.hoanglv1106.flowdock.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.github.hoanglv1106.flowdock.backend.ingress.PaymentIngressProperties;

@SpringBootApplication
@EnableConfigurationProperties(PaymentIngressProperties.class)
public class BackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
