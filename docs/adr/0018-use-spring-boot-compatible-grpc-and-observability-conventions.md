# 0018 - Use Spring Boot-Compatible gRPC and Observability Conventions

Date: 2026-05-13

## Status

Accepted

## Context

Polaris originally started the inventory gRPC server manually with `NettyServerBuilder` and created the order-service inventory channel manually. That kept the early implementation small, but it left lifecycle, interceptors, health, reflection, metrics, and tracing outside normal Spring Boot auto-configuration.

Official Spring gRPC `1.0.x` targets Spring Boot `4.0.x`. Polaris is locked to Spring Boot `3.5.x` before `v1.0.0`, so moving to official Spring gRPC would force a larger platform upgrade than this release needs.

## Decision

Polaris will use `net.devh` `grpc-spring-boot-starter` `3.1.0.RELEASE` for the Spring Boot `3.5.x` line.

`inventory-service` uses the server starter and `@GrpcService` for the `InventoryService` implementation. `order-service` uses the client starter to create the generated blocking inventory stub. Existing Polaris inventory host, port, and deadline properties remain the service-facing configuration contract.

The gRPC boundary uses global interceptors for Micrometer Observation, request ID metadata propagation, MDC population, and concise RPC access logs. The inventory server exposes gRPC health checks and enables reflection only for local and Docker profiles.

Polaris services use Micrometer Tracing with OpenTelemetry OTLP export to Tempo. Trace export is disabled by default and enabled by Docker Compose. Console logs use Spring Boot structured ECS JSON with trace IDs, span IDs, and `request.id` in MDC. Grafana provisions an overview dashboard and a dedicated gRPC dashboard backed by Prometheus metrics.

## Consequences

gRPC lifecycle and generated-stub injection now follow Spring Boot conventions without moving Polaris to Spring Boot 4.

Local and Docker development can inspect gRPC services through health and reflection, while production defaults avoid exposing reflection metadata.

HTTP, gRPC, and Kafka activity now share a tracing and request-correlation convention that fits the existing Prometheus, Grafana, and Tempo stack.

When Polaris later upgrades to Spring Boot 4, the project should revisit this ADR and evaluate migration from `net.devh` to official Spring gRPC.
