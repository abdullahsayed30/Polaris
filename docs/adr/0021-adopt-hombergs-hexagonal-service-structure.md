# 0021 - Adopt Hombergs Hexagonal Service Structure

Date: 2026-10-06

## Status

Accepted. Supersedes ADR 0005 for order-service, inventory-service and notification-service.
Updates ADR 0015 and ADR 0019 package placement; configuration properties remain records and transactional delivery semantics remain unchanged.

## Context

Polaris is a portfolio blueprint intended to demonstrate service internals that can grow while preserving explicit dependency direction. The existing lightweight structure permits Spring Data repositories in application code and JPA annotations on business models. Those conventions conflict with the requested persistence-independent domain and explicit inbound/outbound ports.

## Decision

Use the hierarchy in Tom Hombergs' [BuckPal example at commit dc819c66640be4f42100a622b9e97b1e82ad75a7](https://github.com/thombergs/buckpal/tree/dc819c66640be4f42100a622b9e97b1e82ad75a7):

- `application/domain/model`: plain business models, invariants and state transitions.
- `application/domain/service`: use cases, orchestration and business transaction boundaries.
- `application/port/in` and `application/port/out`: inbound use-case contracts and outbound capabilities, with plain inputs/results.
- `adapter/in/web|grpc|messaging`: transport mapping and delegation to inbound ports.
- `adapter/out/persistence|grpc|messaging`: database, RPC and event-delivery implementations of outbound capabilities.

Bootstrap, Spring wiring and typed configuration properties live directly in the service root package, as in BuckPal. Spring DI and transaction annotations are explicitly allowed in application services; JPA, transport, Spring Data, concrete adapters and configuration binding types are not. Domain models and ports remain framework independent. Small extensions are `adapter/in/scheduling`, `adapter/out/observability`, `adapter/out/retry` and `adapter/out/logging`, for existing recovery triggers, telemetry, retry machinery and simulated notification delivery.

Separate JPA entities, repositories and mappers live in outbound persistence adapters. Persistence bookkeeping (inbox/outbox) stays there; no artificial business domain is created for notification inbox records. Storage ports document lock/transaction semantics. Mappers preserve aggregate/child identity, decimal values, timestamps and optimistic versions. Updates apply to managed entities and explicitly flush within the existing business transaction; detached domain objects must never rely on JPA dirty checking.

Keep ADRs 0019 and 0020: pending intent commits before remote mutation; deterministic reservation identity and locking survive retries; final state and outbox entries commit together; notification processing and inbox outcomes roll back when DLQ publication fails. No schema, HTTP, protobuf or Kafka contract migration is required.

The scope is exactly the three business services. Gateway is excluded. `shared`, `proto-contracts`, generated code and build/test modules retain their structure. Supporting architecture checks and test imports must follow the new service packages. No Spring Modulith, capability wrapper or additional Maven runtime modules are introduced.

## Consequences

Dependencies become reviewable and enforceable at explicit ports. Pure models can be exercised without persistence, and use cases can be tested with fake outbound capabilities. Mapping adds code and makes state persistence explicit; it also introduces a risk of lost updates, child identity changes or rollback regressions. Database integration tests must cover these risks, not just mocks or package checks. Source architecture guards supplement review; they do not prove all runtime semantics.
