package io.polaris.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import io.polaris.notification.adapter.out.observability.DurableTelemetry;
import io.polaris.notification.application.port.in.NotificationDelivery;
import io.polaris.notification.application.port.out.NotificationInbox;
import io.polaris.shared.events.EventMetadata;

class NotificationFreshnessMetricsTest {
    private final PrometheusMeterRegistry metrics = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final InboxEventRepository events = mock(InboxEventRepository.class);
    private final NotificationInboxAdapter inbox = new NotificationInboxAdapter(events,
            DurableTelemetry.noop(new ObjectMapper()), metrics);

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            complete(false);
        }
        metrics.close();
    }

    @Test
    void committedOccurrenceAgeBeyondOneHourHasUsefulFiniteBuckets() {
        receive(Duration.ofHours(36)).markProcessed();
        assertThat(freshness().count()).isZero();
        assertThat(overflow()).isZero();

        complete(true);

        assertThat(freshness().count()).isEqualTo(1);
        assertThat(freshness().totalTime(TimeUnit.HOURS)).isBetween(36.0, 37.0);
        assertThat(overflow()).isZero();
        assertThat(bucket("3600.0")).isZero();
        assertThat(bucket("86400.0")).isZero();
        assertThat(bucket("259200.0")).isEqualTo(1);
        assertThat(bucket("604800.0")).isEqualTo(1);
        assertThat(bucket("+Inf")).isEqualTo(1);
    }

    @Test
    void committedOccurrenceAgeBeyondLargestBucketIsRetainedAndCountedAsOverflow() {
        receive(Duration.ofDays(10)).markProcessed();
        assertThat(overflow()).isZero();

        complete(true);

        assertThat(freshness().count()).isEqualTo(1);
        assertThat(freshness().totalTime(TimeUnit.DAYS)).isBetween(10.0, 11.0);
        assertThat(bucket("604800.0")).isZero();
        assertThat(bucket("+Inf")).isEqualTo(1);
        assertThat(overflow()).isEqualTo(1);
        assertThat(metrics.get("polaris.notification.freshness.overflow").tag("event_type", "InventoryAdjusted")
                .counter().count()).isZero();
        assertThat(metrics.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("polaris.notification.freshness")))
                .allSatisfy(meter -> {
                    assertThat(meter.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                            .containsExactly("event_type");
                    assertThat(meter.getId().getTag("event_type")).isIn("OrderCreated", "InventoryAdjusted");
                });
    }

    @Test
    void rollbackAndCommittedDuplicatesDoNotRecordFreshnessOrOverflow() {
        receive(Duration.ofDays(10)).markProcessed();
        complete(false);
        assertThat(freshness().count()).isZero();
        assertThat(overflow()).isZero();

        UUID duplicate = UUID.randomUUID();
        when(events.existsById(duplicate)).thenReturn(true);
        assertThat(inbox.contains(duplicate)).isTrue();
        assertThat(freshness().count()).isZero();
        assertThat(overflow()).isZero();
    }

    @Test
    void futureOccurrenceAndDeadLetteredOutcomesDoNotProduceFreshnessSamples() {
        receive(Duration.ofHours(-1)).markProcessed();
        complete(true);
        assertThat(metrics.get("polaris.notification.invalid.occurrence.time").counter().count()).isEqualTo(1);

        receive(Duration.ofDays(10)).markDeadLettered(new IllegalStateException("simulated failure"));
        complete(true);

        assertThat(freshness().count()).isZero();
        assertThat(overflow()).isZero();
    }

    private NotificationInbox.Receipt receive(Duration age) {
        TransactionSynchronizationManager.initSynchronization();
        when(events.saveAndFlush(any(InboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventMetadata metadata = EventMetadata.initial(1, Instant.now().minus(age), "private-correlation");
        return inbox.receive(metadata, new NotificationDelivery("orders", 0, 1L, "private-key", "private-payload", "OrderCreated"));
    }

    private void complete(boolean committed) {
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        try {
            if (committed) {
                synchronizations.forEach(TransactionSynchronization::afterCommit);
            }
            synchronizations.forEach(sync -> sync.afterCompletion(committed
                    ? TransactionSynchronization.STATUS_COMMITTED
                    : TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private io.micrometer.core.instrument.Timer freshness() {
        return metrics.get("polaris.notification.freshness").tag("event_type", "OrderCreated").timer();
    }

    private double overflow() {
        return metrics.get("polaris.notification.freshness.overflow").tag("event_type", "OrderCreated").counter().count();
    }

    private double bucket(String upperBound) {
        return metrics.scrape().lines().filter(line -> line.startsWith("polaris_notification_freshness_seconds_bucket{"))
                .filter(line -> line.contains("event_type=\"OrderCreated\"") && line.contains("le=\"" + upperBound + "\""))
                .findFirst().map(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1))).orElseThrow();
    }
}
