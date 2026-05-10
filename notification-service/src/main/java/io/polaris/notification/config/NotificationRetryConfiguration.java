package io.polaris.notification.config;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotificationRetryProperties.class)
public class NotificationRetryConfiguration {
    @Bean
    Retry notificationRetry(NotificationRetryProperties properties) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        properties.initialInterval().toMillis(),
                        properties.multiplier()
                ))
                .retryExceptions(RuntimeException.class)
                .build();

        return Retry.of("notification-workflow", config);
    }
}
