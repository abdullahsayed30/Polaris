# 0011 - Use Testcontainers for Integration Tests

Date: 2026-05-11

## Status

Accepted

## Context

The services depend on PostgreSQL, Kafka, HTTP routing, and gRPC behavior. Mock-only tests would miss important integration risks, while shared developer infrastructure would make tests brittle and hard to run in CI.

## Decision

Polaris will use Testcontainers for integration tests that need infrastructure. `order-service` tests run against PostgreSQL and Kafka containers plus a fake gRPC inventory server. `inventory-service` tests run against PostgreSQL and Kafka containers and call the real gRPC server. Following ADR 0019, `notification-service` tests run against PostgreSQL and Kafka and verify inbox deduplication, retry, and dead-letter behavior.

The `contract-tests` module exercises wire compatibility and the real cross-service order lifecycle. Its service dependencies are test-scoped; this does not permit dependencies between runtime service modules. Infrastructure-free architecture and contract tests run during `test`; Testcontainers integration tests run during `verify`. A Docker-dependent skip is not evidence of a passing integration test.

Gateway route, security, CORS, and rate-limit behavior are verified with Spring WebFlux test support and stubbed upstream routing.

## Consequences

Integration tests exercise realistic infrastructure and remain portable across developer machines and CI. The tradeoff is slower test execution and a Docker dependency for the full verification path.
