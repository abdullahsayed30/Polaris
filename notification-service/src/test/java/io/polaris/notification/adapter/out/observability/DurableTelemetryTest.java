package io.polaris.notification.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class DurableTelemetryTest {
    private final List<SpanData> spans = new ArrayList<>();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
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
            })).build();
    private final OpenTelemetrySdk otel = OpenTelemetrySdk.builder().setTracerProvider(provider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
    private final DurableTelemetry telemetry = new DurableTelemetry(otel, new ObjectMapper());

    @Test
    void durableCarrierRetainsParentSamplingAndTraceStateAcrossNewAdapterInstance() {
        SpanContext original = SpanContext.createFromRemoteParent("012345678901234567890123456789ab",
                "0123456789012345", TraceFlags.getSampled(), TraceState.builder().put("vendor", "value").build());
        String carrier;
        try (Scope scope = Context.root().with(Span.wrap(original)).makeCurrent()) {
            carrier = telemetry.capture();
        }
        assertThat(carrier).contains("traceparent", original.getTraceId(), original.getSpanId(), "vendor=value");
        DurableTelemetry restarted = new DurableTelemetry(otel, new ObjectMapper());
        try (var attempt = restarted.resume("restarted.attempt", carrier, "event-1")) {
            assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(original.getTraceId());
            assertThat(Span.current().getSpanContext().getTraceState().get("vendor")).isEqualTo("value");
            assertThat(Span.current().getSpanContext().isSampled()).isTrue();
        }
        assertThat(spans.getLast().getParentSpanId()).isEqualTo(original.getSpanId());
    }

    @Test
    void missingInvalidAndOversizedContextsFallBackWithoutInheritingBatchParent() {
        Span ambient = otel.getTracer("test").spanBuilder("batch").startSpan();
        try (Scope scope = ambient.makeCurrent()) {
            for (String invalid : new String[]{null, "not json", "{\"traceparent\":null}", "x".repeat(4097)}) {
                try (var attempt = telemetry.resume("fallback", invalid, null)) {
                    assertThat(Span.current().getSpanContext().getTraceId()).isNotEqualTo(ambient.getSpanContext().getTraceId());
                }
            }
        } finally {
            ambient.end();
        }
        assertThat(spans.stream().filter(data -> data.getName().equals("fallback"))).hasSize(4);
    }

    @Test
    void unsampledContextIsPropagatedWithoutInventingExportedSpans() {
        SpanContext original = SpanContext.createFromRemoteParent("112345678901234567890123456789ab",
                "1123456789012345", TraceFlags.getDefault(), TraceState.getDefault());
        String carrier;
        try (Scope scope = Context.root().with(Span.wrap(original)).makeCurrent()) {
            carrier = telemetry.capture();
        }
        for (String retained : new String[]{carrier,
                carrier.replaceAll("\"captured_at\":\"[^\"]+\"", "\"captured_at\":\"2020-01-01T00:00:00Z\"")}) {
            try (var attempt = telemetry.resume("unsampled", retained, null)) {
                assertThat(Span.current().getSpanContext().isSampled()).isFalse();
                assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(original.getTraceId());
            }
        }
        assertThat(spans).isEmpty();
    }

    @Test
    void oldSampledCarrierRetainsOriginalTraceAndParentAfterOriginalSpanEnds() {
        Span original = otel.getTracer("test").spanBuilder("request").startSpan();
        String carrier;
        try (Scope scope = original.makeCurrent()) {
            carrier = telemetry.capture();
        }
        original.end();
        carrier = carrier.replaceAll("\"captured_at\":\"[^\"]+\"", "\"captured_at\":\"2020-01-01T00:00:00Z\"");
        try (var attempt = telemetry.resume("replay", carrier, null)) {
            assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(original.getSpanContext().getTraceId());
            assertThat(Span.current().getSpanContext().isSampled()).isTrue();
        }
        SpanData replay = spans.getLast();
        assertThat(replay.getParentSpanId()).isEqualTo(original.getSpanContext().getSpanId());
        assertThat(replay.getLinks()).isEmpty();
    }

    @Test
    void senderObservationUsesDurableParentInsteadOfAmbientMicrometerSchedulerContext() {
        Span original = otel.getTracer("test").spanBuilder("original.request").startSpan();
        String carrier;
        try (Scope scope = original.makeCurrent()) {
            carrier = telemetry.capture();
        }
        original.end();
        var bridge = new io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext();
        var tracer = new io.micrometer.tracing.otel.bridge.OtelTracer(otel.getTracer("micrometer"), bridge, event -> {
        });
        var registry = io.micrometer.observation.ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler(
                        new io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler<>(tracer,
                                new io.micrometer.tracing.otel.bridge.OtelPropagator(otel.getPropagators(), otel.getTracer("micrometer"))),
                        new io.micrometer.tracing.handler.DefaultTracingObservationHandler(tracer)));
        var scheduled = io.micrometer.observation.Observation.start("scheduler", registry);
        String resumedSpan;
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        try (var scope = scheduled.openScope()) {
            String ambientTrace = tracer.currentSpan().context().traceId();
            try (var attempt = new DurableTelemetry(otel, new ObjectMapper(), bridge).resume("resumed.publish", carrier, "event-1")) {
                resumedSpan = Span.current().getSpanContext().getSpanId();
                assertThat(tracer.currentSpan().context().traceId()).isEqualTo(original.getSpanContext().getTraceId());
                var sender = new io.micrometer.observation.transport.SenderContext<java.util.Map<String, String>>(
                        (map, key, value) -> map.put(key, value));
                sender.setCarrier(headers);
                io.micrometer.observation.Observation.createNotStarted("kafka.send", () -> sender, registry).observe(() -> {
                });
                assertThat(headers.get("traceparent")).contains(original.getSpanContext().getTraceId());
            }
            assertThat(tracer.currentSpan().context().traceId()).isEqualTo(ambientTrace);
        } finally {
            scheduled.stop();
        }
        assertThat(spans.stream().filter(span -> span.getName().equals("kafka.send"))).singleElement()
                .satisfies(span -> assertThat(span.getParentSpanId()).isEqualTo(resumedSpan));
    }

    @Test
    void rollbackDoesNotReportCommittedCompletionAndScopesAreRestored() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            try (var session = telemetry.child("transaction", null)) {
                DurableTelemetry.afterCommit(() -> {
                    throw new AssertionError("Must not run after rollback");
                });
            }
            assertThat(spans).isEmpty();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(spans.getLast().getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                    "polaris.transaction"))).isEqualTo("rolled_back");
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void captureExcludesSensitiveBaggageAndUnavailableExporterDoesNotThrow() {
        Span original = otel.getTracer("test").spanBuilder("request").startSpan();
        Baggage baggage = Baggage.builder().put("authorization", "secret").put("customer.email", "private").build();
        try (Scope scope = Context.root().with(original).with(baggage).makeCurrent()) {
            assertThat(telemetry.capture()).doesNotContain("secret", "private", "authorization", "customer.email");
        } finally {
            original.end();
        }
        var noExporter = OpenTelemetrySdk.builder().setPropagators(otel.getPropagators()).build();
        try (var session = new DurableTelemetry(noExporter, new ObjectMapper()).resume("no-export", null, null)) {
            session.outcome("business_continues");
        }
    }
}
