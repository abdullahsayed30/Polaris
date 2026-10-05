package io.polaris.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerErrorConfiguration {
    @Bean
    CommonErrorHandler notificationKafkaErrorHandler(
            @Value("${polaris.notifications.dlq.redelivery-backoff:1s}") java.time.Duration backoff) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new FixedBackOff(backoff.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        errorHandler.setAckAfterHandle(false);
        return errorHandler;
    }
}
