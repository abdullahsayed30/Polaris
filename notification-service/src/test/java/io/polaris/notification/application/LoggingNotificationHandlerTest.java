package io.polaris.notification.application;

import io.polaris.shared.events.OrderCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingNotificationHandlerTest {
    private final LoggingNotificationHandler handler = new LoggingNotificationHandler();

    @Test
    void logsConfirmationEmailSentForOrderCreatedEvent(CapturedOutput output) {
        UUID orderId = UUID.randomUUID();

        handler.handle(new OrderCreatedEvent(
                orderId,
                UUID.randomUUID(),
                OrderCreatedEvent.OrderStatus.CONFIRMED,
                List.of(new OrderCreatedEvent.Item(
                        UUID.randomUUID(),
                        "SKU-COFFEE-001",
                        2,
                        new BigDecimal("19.99")
                )),
                Instant.now()
        ));

        assertThat(output.getAll())
                .contains("confirmation email sent")
                .contains(orderId.toString());
    }
}
