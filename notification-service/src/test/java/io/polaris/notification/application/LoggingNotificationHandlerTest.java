package io.polaris.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.polaris.shared.events.EventMetadata;
import io.polaris.shared.events.OrderCreatedEvent;

@ExtendWith(OutputCaptureExtension.class)
class LoggingNotificationHandlerTest {
    private final LoggingNotificationHandler handler = new LoggingNotificationHandler();

    @Test
    void logsSimulatedConfirmationForOrderCreatedEvent(CapturedOutput output) {
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();

        handler.handle(new OrderCreatedEvent(
                EventMetadata.initial(OrderCreatedEvent.EVENT_VERSION, now, "test"),
                orderId,
                UUID.randomUUID(),
                OrderCreatedEvent.OrderStatus.CONFIRMED,
                List.of(new OrderCreatedEvent.Item(
                        UUID.randomUUID(),
                        "SKU-COFFEE-001",
                        2,
                        new BigDecimal("19.99"))),
                now));

        assertThat(output.getAll())
                .contains("simulated confirmation notification")
                .doesNotContain("email sent")
                .contains(orderId.toString());
    }
}
