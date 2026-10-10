# Business telemetry is unavailable

Alert: `PolarisBusinessTelemetryUnavailable`. Owner: the alerted service owner with platform/monitoring support. Environment and owner labels are demo responsibility placeholders; assign a real operator before production use.

## Meaning and diagnosis

The service has a reachable metrics listener but no replica with a complete successful business snapshot younger than 45 seconds and a non-future timestamp, for two minutes. Missing gauges, initial NaN values, database sampling failure and stale/future timestamps all invalidate that replica. A notification histogram count without its one-hour bucket also raises this alert. There is no sum of database snapshots across replicas.

1. Open Polaris Business Flow using the alert link. Query `up`, `polaris_observability_snapshot_success` and `time() - polaris_observability_snapshot_timestamp_seconds` by **job and instance**. Check required metric families against the [metric contract](../observability.md#metric-contract).
2. Inspect database connectivity/pool saturation, migrations, service logs, scheduler execution, scrape filters and clock synchronization. Scraping a retained value is not a successful database refresh.
3. During rolling upgrades, compare each replica's schema/buckets. Do not substitute another replica's success flag for a failed replica's backlog.

## Mitigation and resolution

Restore database/sampling or metric collection according to the cause. Keep last-known backlog evidence and separately inspect service-owned committed state when authorized. Do not delete pending/outbox/inbox rows or set gauges to zero to clear the alert.

Confirm required series, successful current timestamps and valid gauges on the affected replica, then confirm backlog/workflow progress separately. A service-level alert can clear when another replica becomes healthy even while one remains impaired. A stopped Prometheus or suppressed backlog alert is not recovery. Record the detection gap and remaining replica failures.
