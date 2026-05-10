package io.polaris.notification.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeadLetterPublisher {
    private final KafkaTemplate<String, NotificationDeadLetterEvent> kafkaTemplate;
    private final String topic;

    public NotificationDeadLetterPublisher(
            KafkaTemplate<String, NotificationDeadLetterEvent> kafkaTemplate,
            @Value("${polaris.kafka.topics.notifications-dlq}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(NotificationDeadLetterEvent event) {
        kafkaTemplate.send(topic, event.sourceKey(), event);
    }

    String topic() {
        return topic;
    }
}
