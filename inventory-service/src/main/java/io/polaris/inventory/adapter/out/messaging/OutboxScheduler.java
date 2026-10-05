package io.polaris.inventory.adapter.out.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.polaris.inventory.OutboxPublisherProperties;

@Component
@EnableScheduling
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxScheduler {
    private static final String POLL_INTERVAL = "${polaris.messaging.outbox.poll-interval:500ms}";
    private static final String INITIAL_DELAY = "${polaris.messaging.outbox.initial-delay:500ms}";

    private final OutboxPublisher publisher;

    public OutboxScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = POLL_INTERVAL, initialDelayString = INITIAL_DELAY)
    public void publish() {
        publisher.publishReady();
    }
}
