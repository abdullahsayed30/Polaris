# 0016 - Use Tempo for Distributed Tracing

Date: 2026-05-11

## Status

Accepted

## Context

Polaris uses OpenTelemetry as the tracing instrumentation contract. The local runtime already includes Prometheus and Grafana, and the project favors a compact developer stack that can show metrics and traces from one observability UI.

A standalone tracing backend with a separate UI would add a parallel observability surface. Tempo fits better with the Grafana-centered local stack because Grafana can query traces and correlate them with Prometheus metrics from a provisioned datasource.

## Decision

Polaris will use Grafana Tempo as the distributed tracing backend.

Services should export traces through OTLP. The Docker Compose stack exposes Tempo OTLP gRPC on `4317`, OTLP HTTP on `4318`, and the Tempo HTTP API on `3200`. Grafana provisions Tempo as a datasource alongside Prometheus.

As of `v0.8.0`, runtime services use Micrometer Tracing with the OpenTelemetry bridge. Trace export is disabled by default for direct local JVM runs and enabled by Docker Compose through OTLP HTTP at `/v1/traces`.

## Consequences

Local trace exploration happens through Grafana instead of a dedicated tracing UI.

The observability stack remains centered on Prometheus, Grafana, and Tempo.

Tempo keeps the local stack aligned with a production path that can later use object storage and scalable trace retention.

If a future use case needs a standalone tracing UI or a different trace-query workflow, that should be captured in a superseding ADR.
