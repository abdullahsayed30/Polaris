package io.polaris.order;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.polaris.order.application.domain.service.ReservationRecoveryPolicy;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReservationRecoveryProperties.class)
public class OrderApplicationConfiguration {
    @Bean
    ReservationRecoveryPolicy reservationRecoveryPolicy(ReservationRecoveryProperties properties) {
        return new ReservationRecoveryPolicy(properties.batchSize(), properties.retryDelay());
    }
}
