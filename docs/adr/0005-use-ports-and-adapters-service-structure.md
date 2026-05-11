# 0005 - Use Ports-and-Adapters Service Structure

Date: 2026-05-11

## Status

Accepted

## Context

The services need enough structure to show production discipline without turning a compact portfolio project into an overbuilt clean architecture example. Spring Boot conventions should remain recognizable, but domain logic should not be spread through controllers, Kafka listeners, persistence classes, and gRPC clients.

## Decision

Polaris services will follow a lightweight ports-and-adapters structure. Inbound adapters live under packages such as `api` or `messaging`. Use cases and transaction boundaries live in `application`. Domain state and behavior live in `domain`. Persistence adapters live in `persistence`. Outbound integrations use small ports and adapters where the application logic depends on another system.

The detailed package standard is documented in [Service Architecture Standard](../service-architecture-standard.md).

## Consequences

The code stays close to Spring Boot while preserving clear boundaries. Application services are easier to test, and infrastructure choices are easier to replace. The tradeoff is a small amount of package structure and mapping code around each service boundary.
