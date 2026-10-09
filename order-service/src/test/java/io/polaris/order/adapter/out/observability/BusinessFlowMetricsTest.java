package io.polaris.order.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BusinessFlowMetricsTest {
    @Test
    void startsMissingAndRetainsLastSnapshotWhenDatabaseRefreshFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessFlowMetrics metrics = new BusinessFlowMetrics(jdbc, registry);
        assertThat(registry.get("polaris.pending.orders").gauge().value()).isNaN();
        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(2.0);
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class))).thenReturn(Timestamp.from(Instant.now().minusSeconds(60)));
        metrics.refresh();
        assertThat(registry.get("polaris.pending.orders").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("polaris.pending.order.oldest.age.seconds").gauge().value()).isGreaterThanOrEqualTo(60.0);
        double lastSuccess = registry.get("polaris.observability.snapshot.timestamp.seconds").gauge().value();
        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenThrow(new IllegalStateException("database unavailable"));
        metrics.refresh();
        assertThat(registry.get("polaris.observability.snapshot.success").gauge().value()).isZero();
        assertThat(registry.get("polaris.observability.snapshot.timestamp.seconds").gauge().value()).isEqualTo(lastSuccess);
        assertThat(registry.get("polaris.pending.orders").gauge().value()).isEqualTo(2.0);
    }
}
