package io.polaris.notification.adapter.out.observability;

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
        for (String status : new String[]{"PROCESSING", "PROCESSED", "DEAD_LETTERED"}) {
            gauge(registry, "polaris.notification.inbox.events", "inbox." + status, "status", status);
        }
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
            for (String status : new String[]{"PROCESSING", "PROCESSED", "DEAD_LETTERED"}) {
                values.put("inbox." + status, 0.0);
            }
            jdbc.query("SELECT status, count(*) AS total FROM inbox_events GROUP BY status", row -> {
                values.put("inbox." + row.getString("status"), row.getDouble("total"));
            });
            snapshot = Map.copyOf(values);
            timestamp = Instant.now().toEpochMilli() / 1000.0;
            success = 1.0;
        } catch (RuntimeException ex) {
            success = 0.0; // Keep last valid snapshot and timestamp, never manufacture zero backlog.
        }
    }
}
