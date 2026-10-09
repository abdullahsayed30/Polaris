package io.polaris.notification.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import io.polaris.notification.adapter.out.messaging.DeadLetterPublicationException;
import io.polaris.notification.adapter.out.messaging.NotificationDeadLetterEvent;
import io.polaris.notification.adapter.out.messaging.NotificationDeadLetterPublisher;
import io.polaris.shared.events.EventMetadata;

class DeadLetterTracingTest {
    private final List<SpanData> spans = new ArrayList<>();
    private final OpenTelemetrySdk otel = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
                public CompletableResultCode export(Collection<SpanData> data) {
                    spans.addAll(data);
                    return CompletableResultCode.ofSuccess();
                }
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            })).build()).build();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final KafkaTemplate<String, NotificationDeadLetterEvent> kafka = mock(KafkaTemplate.class);
    private final NotificationDeadLetterPublisher publisher = new NotificationDeadLetterPublisher(kafka, "dlq",
            Duration.ofSeconds(1), new DurableTelemetry(otel, new ObjectMapper()), metrics);

    @Test
    void brokerFailureIsMarkedBeforeAttemptEndsAndEscapesToTheSourceListener() {
        NotificationDeadLetterEvent event = event();
        when(kafka.send("dlq", event.sourceKey(), event))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("private failure detail")));

        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(DeadLetterPublicationException.class);

        assertFailure("failed", "java.util.concurrent.ExecutionException");
        assertThat(spans.getFirst().getAttributes().toString()).doesNotContain("private failure detail");
    }

    @Test
    void interruptedSendIsMarkedBeforeAttemptEndsAndRetainsTheInterruptFlag() {
        NotificationDeadLetterEvent event = event();
        when(kafka.send("dlq", event.sourceKey(), event))
                .thenReturn(new CompletableFuture<SendResult<String, NotificationDeadLetterEvent>>());
        boolean previouslyInterrupted = Thread.interrupted();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(DeadLetterPublicationException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertFailure("interrupted", InterruptedException.class.getName());
        } finally {
            Thread.interrupted();
            if (previouslyInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void assertFailure(String outcome, String errorType) {
        assertThat(spans).hasSize(1);
        SpanData span = spans.getFirst();
        assertThat(span.getName()).isEqualTo("notification.dlq.publish.attempt");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("polaris.outcome"))).isEqualTo(outcome);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("error.type"))).isEqualTo(errorType);
        assertThat(metrics.get("polaris.notification.dlq.publications").tag("outcome", "failed").counter().count()).isEqualTo(1.0);
        assertThat(metrics.get("polaris.notification.dlq.publications").tag("outcome", "acknowledged").counter().count()).isZero();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    private NotificationDeadLetterEvent event() {
        Instant now = Instant.now();
        UUID source = UUID.randomUUID();
        return new NotificationDeadLetterEvent(EventMetadata.causedBy(1, now, "request-1", source),
                "polaris.orders.created", 0, 1L, "order-1", source, 1, "OrderCreated", "{}",
                IllegalStateException.class.getName(), "failure", now);
    }
}
