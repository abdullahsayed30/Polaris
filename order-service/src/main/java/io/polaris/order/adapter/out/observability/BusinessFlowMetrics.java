package io.polaris.order.adapter.out.observability;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Committed, service-owned database snapshots. Never query a database on a metrics scrape. */
@Component
@EnableScheduling
public class BusinessFlowMetrics {
    private final JdbcTemplate jdbc;
    private volatile Map<String, Double> snapshot = Map.of();
    private volatile double success;
    private volatile double timestamp = Double.NaN;
    public BusinessFlowMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String status : new String[]{"PENDING", "RETRY", "FAILED", "PUBLISHED"}) {
            gauge(registry, "polaris.outbox.events", "outbox." + status, "status", status);
        }
        gauge(registry, "polaris.outbox.oldest.age.seconds", "outbox.age");
        gauge(registry, "polaris.pending.orders", "orders.pending");
        gauge(registry, "polaris.pending.order.oldest.age.seconds", "orders.age");
        Gauge.builder("polaris.observability.snapshot.success", this, value -> value.success).register(registry);
        Gauge.builder("polaris.observability.snapshot.timestamp.seconds", this, value -> value.timestamp).register(registry);
    }
    private void gauge(MeterRegistry registry, String name, String key, String... tags) {
        Gauge.builder(name, this, value -> value.snapshot.getOrDefault(key, Double.NaN)).tags(tags).register(registry);
    }
    @Scheduled(fixedDelayString = "${polaris.observability.snapshot.interval:15000}", initialDelayString = "1500")
    public void refresh() {
        // Autocommit aggregate reads see committed rows; acquisition errors remain inside this failure boundary.
        try {
            Map<String, Double> values = new HashMap<>();
            for (String status : new String[]{"PENDING", "RETRY", "FAILED", "PUBLISHED"}) {
                values.put("outbox." + status, 0.0);
            }
            jdbc.query("SELECT status, count(*) AS total FROM outbox_events GROUP BY status", row -> {
                values.put("outbox." + row.getString("status"), row.getDouble("total"));
            });
            values.put("outbox.age", age("SELECT min(created_at) FROM outbox_events WHERE status IN ('PENDING','RETRY')"));
            values.put("orders.pending", jdbc.queryForObject("SELECT count(*) FROM orders WHERE status='PENDING'", Double.class));
            values.put("orders.age", age("SELECT min(created_at) FROM orders WHERE status='PENDING'"));
            snapshot = Map.copyOf(values);
            timestamp = Instant.now().toEpochMilli() / 1000.0;
            success = 1.0;
        } catch (RuntimeException ex) {
            success = 0.0; // Keep last valid snapshot and timestamp, never manufacture zero backlog.
        }
    }
    private double age(String sql) {
        Timestamp oldest = jdbc.queryForObject(sql, Timestamp.class);
        return oldest == null ? 0.0 : Math.max(0.0, Duration.between(oldest.toInstant(), Instant.now()).toMillis() / 1000.0);
    }
}
