package io.polaris.notification.adapter.out.observability;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

/** Service-owned durable context adapter. Never stores arbitrary request headers or baggage. */
@Component
public class DurableTelemetry {
    private static final Set<String> FIELDS = Set.of("traceparent", "tracestate", "b3", "x-b3-traceid",
            "x-b3-spanid", "x-b3-sampled", "x-b3-flags", "x-b3-parentspanid");
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };
    private final OpenTelemetry telemetry;
    private final ObjectMapper mapper;
    private final io.micrometer.tracing.CurrentTraceContext currentTraceContext;
    @Autowired
    public DurableTelemetry(ObjectProvider<OpenTelemetry> provider,
            ObjectProvider<io.micrometer.tracing.CurrentTraceContext> contextProvider, ObjectMapper mapper) {
        this(provider.getIfAvailable(OpenTelemetry::noop), mapper,
                contextProvider.getIfAvailable(io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext::new));
    }
    public DurableTelemetry(OpenTelemetry telemetry, ObjectMapper mapper) {
        this(telemetry, mapper, new io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext());
    }
    public DurableTelemetry(OpenTelemetry telemetry, ObjectMapper mapper, io.micrometer.tracing.CurrentTraceContext currentTraceContext) {
        this.currentTraceContext = currentTraceContext;
        this.telemetry = telemetry;
        this.mapper = mapper;
    }

    public String capture() {
        try {
            Map<String, String> carrier = new HashMap<>();
            // No baggage is currently part of the service propagation policy. Exclude all incoming baggage.
            Context context = Context.current().with(Baggage.empty());
            telemetry.getPropagators().getTextMapPropagator().inject(context, carrier, (map, key, value) -> {
                if (FIELDS.contains(key) && value != null && value.length() <= 2048) {
                    map.put(key, value);
                }
            });
            if (carrier.isEmpty()) {
                return null;
            }
            carrier.put("captured_at", Instant.now().toString());
            String serialized = mapper.writeValueAsString(carrier);
            return serialized.length() <= 4096 ? serialized : null;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    public Session child(String name, String identity) {
        return start(name, identity, Context.current(), false);
    }

    public Session attempt(String name, String identity) {
        Session session = child(name, identity);
        session.transactional = false;
        return session;
    }

    public Session resume(String name, String carrier, String identity) {
        Context parent = Context.root();
        try {
            if (carrier != null && carrier.length() <= 4096) {
                Map<String, String> fields = mapper.readValue(carrier, new TypeReference<Map<String, String>>() {
                });
                if (fields.size() <= 9 && fields.entrySet().stream()
                        .allMatch(entry -> (FIELDS.contains(entry.getKey()) || entry.getKey().equals("captured_at"))
                                && entry.getValue() != null && entry.getValue().length() <= 2048)) {
                    TextMapPropagator propagator = telemetry.getPropagators().getTextMapPropagator();
                    parent = propagator.extract(Context.root(), fields, GETTER);
                    // Validate the retained timestamp without imposing an age limit on parentage.
                    Instant.parse(fields.get("captured_at"));
                }
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            parent = Context.root();
        }
        return start(name, identity, parent, false);
    }

    private Session start(String name, String identity, Context parent, boolean database) {
        try {
            SpanBuilder builder = telemetry.getTracer("polaris.notification").spanBuilder(name);
            SpanContext parentSpan = Span.fromContext(parent).getSpanContext();
            SpanContext ambient = Span.current().getSpanContext();
            builder.setParent(parent);
            if (ambient.isValid() && !ambient.equals(parentSpan)) {
                builder.addLink(ambient);
            }
            if (database) {
                builder.setSpanKind(SpanKind.CLIENT);
            }
            Span span = builder.startSpan();
            if (identity != null) {
                span.setAttribute("polaris.identity", identity);
            }
            return new Session(span, !database, currentTraceContext);
        } catch (RuntimeException ex) {
            return new Session(Span.getInvalid(), false, currentTraceContext);
        }
    }

    public static DurableTelemetry noop(ObjectMapper mapper) {
        return new DurableTelemetry(OpenTelemetry.noop(), mapper);
    }

    public <T> T database(String operation, String table, Supplier<T> work) {
        try (var session = start("db." + table + "." + operation.toLowerCase(java.util.Locale.ROOT),
                null, Context.current(), true)) {
            safely(() -> {
                session.span.setAttribute("db.system.name", "postgresql");
                session.span.setAttribute("db.operation.name", operation);
                session.span.setAttribute("db.collection.name", table);
            });
            try {
                T result = work.get();
                session.outcome("executed");
                return result;
            } catch (RuntimeException ex) {
                session.error(ex);
                throw ex;
            }
        }
    }

    public void databaseWrite(String operation, String table, Runnable work) {
        database(operation, table, () -> {
            work.run();
            return null;
        });
    }

    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                public void afterCommit() {
                    safely(action);
                }
            });
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            /* Telemetry must not change business outcomes. */ }
    }

    public static final class Session implements AutoCloseable {
        private final Span span;
        private final Scope baggageScope;
        private final io.micrometer.tracing.CurrentTraceContext.Scope scope;
        private final String previousTrace;
        private final String previousSpan;
        private boolean closed;
        private boolean transactional;
        private Session(Span span, boolean transactional, io.micrometer.tracing.CurrentTraceContext currentTraceContext) {
            this.transactional = transactional;
            this.span = span;
            previousTrace = MDC.get("traceId");
            previousSpan = MDC.get("spanId");
            this.baggageScope = Context.current().with(Baggage.empty()).makeCurrent();
            // A native scope alone leaves Micrometer's ambient scheduler context key in place.
            // Use the managed bridge so transport observations see this restored span as their parent.
            this.scope = bridgeScope(currentTraceContext, span);
            if (span.getSpanContext().isValid()) {
                MDC.put("traceId", span.getSpanContext().getTraceId());
                MDC.put("spanId", span.getSpanContext().getSpanId());
            }
        }
        private static io.micrometer.tracing.CurrentTraceContext.Scope bridgeScope(
                io.micrometer.tracing.CurrentTraceContext currentTraceContext, Span span) {
            try {
                return currentTraceContext.newScope(io.micrometer.tracing.otel.bridge.OtelSpan.fromOtel(span,
                        Context.root().with(Baggage.empty()).with(span)).context());
            } catch (RuntimeException ex) {
                // A broken telemetry scope must not change a business transaction's outcome.
                Scope fallback = Context.root().with(Baggage.empty()).with(span).makeCurrent();
                return fallback::close;
            }
        }
        public void outcome(String outcome) {
            safely(() -> span.setAttribute("polaris.outcome", outcome));
        }
        public void error(Throwable failure) {
            safely(() -> {
                span.setAttribute("error.type", failure.getClass().getName());
                span.setStatus(StatusCode.ERROR);
            });
        }
        /** Keep a resolution attempt scoped until its existing business transaction completes. */
        public void closeWithTransaction() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    public void afterCompletion(int status) {
                        finish(status);
                        closeScope();
                    }
                });
            } else {
                close();
            }
        }
        public void close() {
            if (closed) {
                return;
            }
            closeScope();
            if (transactional && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    public void afterCompletion(int status) {
                        finish(status);
                    }
                });
            } else {
                safely(span::end);
            }
        }
        private void finish(int status) {
            safely(() -> {
                span.setAttribute("polaris.transaction", status == TransactionSynchronization.STATUS_COMMITTED
                        ? "committed"
                        : "rolled_back");
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    span.setStatus(StatusCode.ERROR);
                }
                span.end();
            });
        }
        private void closeScope() {
            if (closed) {
                return;
            }
            closed = true;
            safely(scope::close);
            safely(baggageScope::close);
            restore("traceId", previousTrace);
            restore("spanId", previousSpan);
        }
        private static void restore(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
