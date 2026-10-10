# Sustained order-path system errors

Alerts: `PolarisOrderApiSystemErrors`, `PolarisInventoryRpcSystemErrors`. Owners: gateway/order owners for HTTP, inventory owner with order support for RPC. Severity `critical` is a demo response priority, not a configured paging integration.

## Meaning and diagnosis

More than 5% system errors persist for five minutes, with at least 20 eligible requests/calls in each trailing five-minute window. HTTP excludes client 4xx and actuator traffic; RPC counts only `ReserveStock` system statuses (`UNKNOWN`, `INTERNAL`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `DATA_LOSS`, `RESOURCE_EXHAUSTED`) and successful calls. Invalid arguments, preconditions and authentication failures are excluded; insufficient stock is a valid business outcome. Low/no traffic hides a ratio condition and does not prove health.

1. Open the alert's Overview or gRPC dashboard. Identify the actual route/method/status, error onset, traffic volume and deployment changes. Avoid adding server and client observations or two metric families to the same denominator.
2. Follow request/trace IDs through ECS logs and Tempo in the full stack. Compare gateway, order and inventory health, database pools, deadlines, DNS/channel recovery and Kafka delivery.
3. Check committed pending intent and reservation decisions. A timeout can follow a successful remote reservation; never assume the caller's rollback also rolled back stock.

## Mitigation and resolution

Restore the identified dependency, capacity or compatible application version. Preserve original order IDs/idempotency bindings and let durable recovery reconcile uncertain reservations. Do not blindly release stock, change the payload or retry with a fresh idempotency key.

Resolution needs successful representative authenticated requests, valid reservations/persistence, clearing pending work and a sustained return below the rule threshold. Waiting for traffic to fall below the guard is not proof of recovery. Preserve error samples, impact, timing and the remaining telemetry limits in the incident record.
