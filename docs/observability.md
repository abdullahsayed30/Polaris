# Observability

Polaris keeps local observability close to production conventions without requiring a managed backend. Services expose actuator health and Prometheus metrics, emit ECS JSON logs, and can export OpenTelemetry traces to Tempo.

## Tracing

All runtime services include Micrometer Tracing with the OpenTelemetry bridge and OTLP exporter. The default application configuration sets sampling to `1.0` and disables trace export:

| Property | Default | Purpose |
| --- | --- | --- |
| `POLARIS_TRACING_EXPORT_ENABLED` | `false` | Enables or disables OTLP trace export |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP HTTP trace endpoint |
| `POLARIS_TRACING_SAMPLING_PROBABILITY` | `1.0` | Trace sampling probability |
| `POLARIS_DEPLOYMENT_ENVIRONMENT` | `local` | OpenTelemetry resource attribute |

Docker Compose sets `POLARIS_TRACING_EXPORT_ENABLED=true` and sends traces to `http://tempo:4318/v1/traces`.

## Request Correlation

The gateway accepts or creates `X-Request-Id`, returns it to the caller, and forwards it upstream. `order-service` stores that value in MDC as `request.id`; the gRPC client interceptor propagates it as `x-request-id` metadata to `inventory-service`. The inventory gRPC server interceptor restores the same value into MDC while handling the RPC.

## Logs

Services use Spring Boot structured console logging with Elastic Common Schema. Logs include Boot-managed `traceId` and `spanId` when tracing context exists. Polaris request and gRPC access logs also include the request ID in the message and MDC field `request.id`.

## Metrics and Dashboards

Prometheus scrapes `/actuator/prometheus` for gateway, order, inventory, and notification services. Grafana provisions:

| Dashboard | File | Purpose |
| --- | --- | --- |
| Polaris Overview | `deploy/grafana/dashboards/polaris-overview.json` | HTTP traffic, service scrape health, JVM, CPU, and gRPC summary panels |
| Polaris gRPC | `deploy/grafana/dashboards/polaris-grpc.json` | gRPC server/client request rates, latency, and errors |

## gRPC Health and Reflection

`inventory-service` exposes gRPC health checks through the starter health service. Reflection is disabled by default and enabled only in `local` and `docker` profiles so local tools can inspect the internal protobuf contract without making reflection a production default.
