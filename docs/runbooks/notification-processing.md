# Simulated notification processing is failing or delayed

Alerts: `PolarisNotificationFailures`, `PolarisNotificationProcessingLate`. Owner: notification/messaging owner with platform support.

## Meaning and diagnosis

A new failed/acknowledged DLQ send or committed dead-letter observation in the trailing five-minute window persists for one minute; or more than 5% of at least 20 completed observations per five minutes exceed **one hour**, sustained for five minutes. Delivery is simulated. This one-hour operational threshold is distinct from the proposed 60-second SLO.

1. Open Polaris Business Flow and inspect completion outcomes, retry attempts, DLQ publication outcomes, histogram count and the adjacent seven-day overflow panel. A hidden p95, zero completions or missing finite bucket is not zero delay. The alert uses the one-hour bucket/count fraction, so overflow is included without quantile clipping.
2. Correlate source event ID/topic/partition/offset and trace/log evidence with committed inbox status. DLQ sends are process-local attempts, not unique failures or queue depth; one event can cause both a send and a committed dead-letter observation. Poison records can publish a DLQ event without a valid inbox event ID.
3. Distinguish acknowledged DLQ publication from failed publication. A failed send must escape the listener without acknowledging its source record; redelivery continues. Preserve consumer-group/offset evidence before changing anything.

## Mitigation and resolution

Restore broker/handler/database dependencies as diagnosed. Do not advance offsets to bypass failed DLQ sends. For acknowledged terminal failures, decide whether the payload can be processed safely before controlled replay; preserve event identity and propagation headers and account for inbox duplicate suppression. No production DLQ consumer or automatic redrive exists. Real providers would require provider-side idempotency and separate delivery proof.

Check subsequent successful simulated processing, broker-acknowledged DLQ sends where appropriate, committed inbox state, retry progression and fresh telemetry. Counter alerts clear when observations leave their window or a process restarts; neither proves a terminal failure was repaired. Completed freshness cannot detect events the consumer has not seen: inspect broker lag through separately available platform tools rather than claim bundled backlog coverage. Record unresolved terminal events and follow-up ownership.
