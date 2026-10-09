package io.polaris.order.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import io.polaris.order.OrderServiceApplication;
import io.polaris.order.adapter.out.messaging.OutboxPublisher;
import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.domain.model.OrderStatus;
import io.polaris.order.application.domain.service.OrderPlacementTransactions;
import io.polaris.order.application.port.in.PlaceOrderLine;
import io.polaris.order.application.port.out.InventoryClient;
import io.polaris.order.application.port.out.InventoryDecision;
import io.polaris.order.application.port.out.StockCheckResult;
import io.polaris.order.application.port.out.StockReservationResult;

@Testcontainers
class DurableRecoveryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("orders").withUsername("orders").withPassword("orders");
    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    static final List<SpanData> spans = java.util.Collections.synchronizedList(new ArrayList<>());

    @Test
    void oldPendingAndOutboxCarriersSurviveApplicationRestartThenReachObservedKafkaSend() throws Exception {
        UUID id;
        String originalCarrier;
        String traceId;
        String publicationParent;
        try (ConfigurableApplicationContext first = start()) {
            OpenTelemetry otel = first.getBean(OpenTelemetry.class);
            Span incoming = otel.getTracer("test").spanBuilder("incoming.request").startSpan();
            traceId = incoming.getSpanContext().getTraceId();
            try (var scope = incoming.makeCurrent()) {
                id = first.getBean(OrderPlacementTransactions.class).prepare("restart-key", UUID.randomUUID(),
                        List.of(new PlaceOrderLine("restart-sku", 1, new BigDecimal("19.9900")))).order().getId();
            } finally {
                incoming.end();
            }
            originalCarrier = first.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT recovery_trace_context FROM orders WHERE id=?", String.class, id);
            assertThat(originalCarrier).contains("traceparent", traceId, "captured_at");
            // Synthetic old timestamp exercises delay semantics without claiming an hours-long test.
            originalCarrier = oldCarrier(originalCarrier);
            first.getBean(JdbcTemplate.class).update("UPDATE orders SET recovery_trace_context=? WHERE id=?", originalCarrier, id);
            assertThat(first.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT count(*) FROM outbox_events", Integer.class)).isZero();
        }
        // A fresh Spring context creates a fresh tracer/channel/publisher against the same committed database.
        try (ConfigurableApplicationContext restarted = start()) {
            assertThat(restarted.getBean(OrderPlacementTransactions.class).resolve(id).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT recovery_trace_context FROM orders WHERE id=?", String.class, id))
                    .isEqualTo(originalCarrier);
            assertThat(jdbc.queryForObject("SELECT trace_context FROM outbox_events WHERE aggregate_id=?", String.class, id.toString()))
                    .contains("traceparent", traceId);
            String outboxCarrier = oldCarrier(jdbc.queryForObject(
                    "SELECT trace_context FROM outbox_events WHERE aggregate_id=?", String.class, id.toString()));
            publicationParent = new com.fasterxml.jackson.databind.ObjectMapper().readTree(outboxCarrier)
                    .get("traceparent").asText().split("-")[2];
            jdbc.update("UPDATE outbox_events SET trace_context=? WHERE aggregate_id=?", outboxCarrier, id.toString());
            var scheduled = io.micrometer.observation.Observation.start("scheduled.outbox",
                    restarted.getBean(io.micrometer.observation.ObservationRegistry.class));
            try (var scope = scheduled.openScope()) {
                restarted.getBean(OutboxPublisher.class).publishReady();
            } finally {
                scheduled.stop();
            }
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id=?", String.class, id.toString()))
                    .isEqualTo("PUBLISHED");
            Map<String, Object> properties = new HashMap<>();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            try (var consumer = new KafkaConsumer<String, String>(properties)) {
                consumer.subscribe(List.of("polaris.orders.created"));
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                boolean found = false;
                while (!found && System.nanoTime() < deadline) {
                    for (var record : consumer.poll(Duration.ofMillis(200))) {
                        if (record.key().equals(id.toString())) {
                            assertThat(record.headers().lastHeader("traceparent")).isNotNull();
                            assertThat(
                                    new String(record.headers().lastHeader("traceparent").value(), java.nio.charset.StandardCharsets.UTF_8))
                                    .contains(traceId);
                            found = true;
                        }
                    }
                }
                assertThat(found).isTrue();
            }
        }
        assertThat(spans.stream().filter(span -> span.getTraceId().equals(traceId)).map(SpanData::getName))
                .contains("incoming.request", "order.intent.create", "order.resolve.attempt", "outbox.event.create",
                        "outbox.publish.attempt", "db.orders.insert", "db.orders.select", "db.orders.update", "db.outbox_events.insert");
        assertThat(spans.stream().filter(span -> span.getName().equals("order.resolve.attempt")))
                .allMatch(span -> "committed".equals(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                        "polaris.transaction"))));
        assertThat(spans.stream().filter(span -> span.getName().equals("outbox.publish.attempt")))
                .singleElement().satisfies(span -> {
                    assertThat(span.getTraceId()).isEqualTo(traceId);
                    assertThat(span.getParentSpanId()).isEqualTo(publicationParent);
                });
    }

    private static String oldCarrier(String carrier) {
        return carrier.replaceAll("\"captured_at\":\"[^\"]+\"", "\"captured_at\":\"2020-01-01T00:00:00Z\"");
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(OrderServiceApplication.class, Fixtures.class)
                .run("--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + database.getUsername(), "--spring.datasource.password=" + database.getPassword(),
                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(), "--management.otlp.tracing.export.enabled=false",
                        "--polaris.messaging.outbox.initial-delay=3600000", "--polaris.reservation.recovery.initial-delay=3600000");
    }

    static class Fixtures {
        @Bean
        OpenTelemetry openTelemetry() {
            SpanExporter exporter = new SpanExporter() {
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
            };
            return OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
        }
        @Bean
        @Primary
        InventoryClient inventoryClient() {
            return new InventoryClient() {
                public StockCheckResult checkStock(Order order) {
                    throw new UnsupportedOperationException();
                }
                public StockReservationResult reserveStock(Order order) {
                    return new StockReservationResult(true, InventoryDecision.RESERVED);
                }
            };
        }
    }
}
