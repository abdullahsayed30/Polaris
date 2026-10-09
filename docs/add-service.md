# How to add a service

Use this guide to extend Polaris with a justified service boundary, rather than cloning a service's business implementation. Start with [the service standard](service-architecture-standard.md), [ADR 0021](adr/0021-adopt-hombergs-hexagonal-service-structure.md), [contract rules](../contracts/README.md) and [AGENTS.md](../AGENTS.md). A change to an accepted architectural rule needs an amended or superseding ADR.

## 1. Define ownership and the smallest useful slice

Write down the use case, data owner, inbound contract, outbound dependencies and failure/retry behavior before adding files. First consider adding a use case to its existing owning service.

| Responsibility | Existing example | What to introduce |
| --- | --- | --- |
| Stateful HTTP business service | [Order](services/order-service.md) | Inbound web adapter, use-case ports, plain models, separate persistence mapping and owned database |
| Stateful internal RPC service | [Inventory](services/inventory-service.md) | Generated gRPC contract, inbound gRPC adapter, transactional use case and owned database |
| Event-driven workflow | [Notification](services/notification-service.md) | Inbound messaging adapter, workflow ports and durable inbox when deduplication is required |
| Stateless edge service | [Gateway](services/gateway.md) | Edge routing/security/configuration; no invented business aggregate or database |

ADR 0021 currently covers order, inventory and notification only. Adopt its structure explicitly for a new business service in the service standard and architecture guards. Gateway, `shared` and `proto-contracts` retain their own structures. Notification has no artificial domain model for inbox bookkeeping. Do not add unused packages, Spring Modulith, capability wrappers or more Maven modules inside a service.

## 2. Register an independently deployable Maven module

Create a service directory with its own POM, bootstrap class, resources and tests. Use [order-service/pom.xml](../order-service/pom.xml) as a build reference, removing dependencies that the new service does not need.

- Inherit the root parent/version; add the module to [root pom.xml](../pom.xml). Keep Java **25 exactly**, the pinned Maven Wrapper and common version management in the parent.
- Depend on `shared` only for stable event/value contracts and on `proto-contracts` only when RPC is needed. Never depend on another runtime service, copy its implementation, or read its tables.
- Extend the parent's `enforce-production-module-boundaries` banned artifact list to include the new runtime artifact, so other modules cannot import it.
- Extend [Checkstyle import controls](../config/checkstyle/import-control.xml) in both directions and [ServiceArchitectureTest](../contract-tests/src/test/java/io/polaris/contracts/ServiceArchitectureTest.java): its module enumeration, business-service roots and cross-service restrictions are explicit lists. Add a negative example proving the new boundary is checked; a directory alone does not enable the guards.
- Only `contract-tests` may add cross-service dependencies, and only with `test` scope. Preserve its narrow Enforcer exception.

The monorepo reactor builds the services together, but each service produces its own runnable artifact/image and can be deployed separately.

## 3. Implement ports, business behavior and adapters

For a new business service, use this shape relative to `io.polaris.<service>`:

```text
application/
  domain/model/       # plain business state and invariants, if needed
  domain/service/     # use cases and business transactions
  port/in/            # use-case interfaces and plain inputs/results
  port/out/           # storage, RPC, event, delivery or telemetry capabilities
adapter/
  in/web|grpc|messaging|scheduling/
  out/persistence|grpc|messaging|observability|retry|logging/
<Service>Application.java
<...>Configuration.java
<...>Properties.java  # configuration records at the service root
```

Create only the branches used by the feature.

Follow an existing vertical slice:

- [PlaceOrderUseCase](../order-service/src/main/java/io/polaris/order/application/port/in/PlaceOrderUseCase.java) and [OrderController](../order-service/src/main/java/io/polaris/order/adapter/in/web/OrderController.java): inbound adapters validate/map requests and call an inbound port, never the implementation or an outbound adapter.
- [OrderStore](../order-service/src/main/java/io/polaris/order/application/port/out/OrderStore.java) and [OrderPersistenceAdapter](../order-service/src/main/java/io/polaris/order/adapter/out/persistence/OrderPersistenceAdapter.java): application-owned storage capability with explicit transaction/lock semantics.
- [Order](../order-service/src/main/java/io/polaris/order/application/domain/model/Order.java), [OrderJpaEntity](../order-service/src/main/java/io/polaris/order/adapter/out/persistence/OrderJpaEntity.java) and [OrderMapper](../order-service/src/main/java/io/polaris/order/adapter/out/persistence/OrderMapper.java): plain business behavior separated from JPA mapping.

Models and ports are framework independent. Application services may use Spring DI/transaction annotations and SLF4J; they must not import JPA, Spring Data, generated transport types, concrete adapters, Micrometer, Resilience4j or bound configuration records. Root wiring converts configuration to plain policies and assembles adapters.

Plain models are detached snapshots. Mutations require explicit storage-port calls. Persistence adapters map onto managed JPA entities, preserve aggregate/child IDs, decimal precision/scale, timestamps and optimistic versions, and return saved/flushed state. Use the entity returned by `save`/`saveAndFlush`; assigned IDs can cause a merge into another instance.

## 4. Own state and define failure semantics

For a stateful service, allocate a separate database, datasource configuration and master changelog. Use the owning service's `src/main/resources/db/changelog` as the reference:

- Add new, small, ordered SQL changesets and include them in `db.changelog-master.xml`. Never edit an applied migration. Retain `ddl-auto=validate`.
- Put demo seeds behind the explicit `demo` Liquibase context, excluded from production. Keep JPA entities, repositories and inbox/outbox bookkeeping in `adapter.out.persistence`.
- Storage/event-recording adapters join the business transaction; locks remain held until that transaction ends. Business state and its outbox row commit together. Publication then delivers the stored payload/identity at least once.
- Keep inbox deduplication and processing outcome in one transaction. Decode/acknowledge Kafka at the adapter boundary; failed DLQ publication must leave the source retryable.
- A remote mutation cannot roll back with your local transaction. Persist recovery intent before calling it; retry uncertain results with the same identity. Never blindly compensate after a timeout.

Use [ADR 0019](adr/0019-use-transactional-outbox-and-consumer-inbox.md) for event delivery and [ADR 0020](adr/0020-recover-pending-orders-durably.md) for durable intent. Preserve customer-bound idempotency keys/fingerprints and order identity. Inventory reservations remain atomic and order-keyed, lock SKUs deterministically, return stored decisions on exact retries, reject conflicting payloads and restore stock only once on repeated release. Do not add check-before-reserve or a distributed database transaction.

A stateless service needs no database/migrations. A real notification provider needs provider-side idempotency; the existing logging handler simulates delivery.

## 5. Review contracts and authorization

| Boundary | Files and rules |
| --- | --- |
| Public HTTP | Add the versioned OpenAPI contract under `contracts/openapi`; route through gateway and keep gateway/service authorization consistent |
| Internal RPC | Define versioned protobuf under `proto-contracts/src/main/proto`; consume generated stubs through the artifact, with starter-managed channel/server lifecycle |
| Events | Define stable payloads in `shared`, JSON schemas under `contracts/events`, and topic/payload mappings in AsyncAPI |
| Compatibility checks | Extend contract baselines/tests with deliberate additions; do not simply replace a baseline to hide an incompatible change |

Never reuse protobuf field numbers or change existing wire types. Breaking changes require a new version or an explicit tested compatibility migration. Test current and representative retained payloads, including exact decimals; do not convert typed money through a floating-point JSON tree.

Preserve stable event IDs during retry/replay. Deploy tolerant consumers before new producers. Missing legacy metadata is distinct from explicit malformed/null metadata; do not erase retained data or reset offsets to conceal incompatibility. Follow [the detailed rollout rules](../contracts/README.md).

For customer-facing APIs, derive ownership from a validated JWT identity, never a request owner field. Test missing authentication, missing method-specific scopes, allowed access and cross-customer denial at both gateway and owning service. Local Keycloak users/password grant/plaintext are development fixtures, not production authentication guidance.

## 6. Wire configuration, containers and telemetry

| Surface | Changes to review |
| --- | --- |
| Application configuration | Default/local/docker profiles; own ports and datasource; external RPC/broker/identity endpoints; typed configuration records |
| Docker image | Own Dockerfile with local-runtime and production targets, executable jar and non-root runtime; preserve resource/probe conventions |
| Reactor build context | Existing Dockerfiles copy every module POM explicitly: update those COPY lists when registering another module, or reactor discovery will fail |
| Local Compose | Add only the new service and its needed demo dependencies, unused host ports, profile/env settings, healthcheck and correct startup dependencies |
| Helm | Update values/schema/example values and templates where the new role needs config, secret references or network flows |
| CI | Extend the explicit service image matrix, archive checks and scanning/SBOM coverage in the reusable workflow |
| Observability | Actuator/Prometheus scrape target, ECS logs, request/trace propagation, OTLP configuration and relevant dashboards |
| Documentation | Service page, service/index links, architecture diagram and rollout/operations guidance |

Read [deployment](deployment.md) before Helm wiring: values alone are insufficient because config, database Secret mappings and NetworkPolicies have service-specific branches. The chart owns application workloads; PostgreSQL, Kafka, Redis, identity, secrets and telemetry storage remain external platform responsibilities. Keep non-root/read-only security settings, probes, resources and explicit network-policy limitations.

Use [observability](observability.md) as the current source of truth. Put telemetry behind application-owned ports where use cases need it; do not introduce infrastructure imports into models or ports. Verify actual metric names and scrape/trace behavior before adding dashboards or alerts. Do not label proposed SLOs as measured results.

## 7. Validate the slice and hand it off

1. Test invariants/use cases with plain models and fake outbound ports. Test transport validation/authorization separately.
2. For mapping, concurrency, recovery or delivery, use the service's Testcontainers suite to assert **committed** state, stable child IDs/versions/decimals, duplicate handling, locking and rollback after failures. Handler invocation alone is insufficient.
3. Extend `contract-tests` for cross-service HTTP/RPC/Kafka behavior without weakening production boundaries.
4. Run sequentially in the same checkout:

   ```bash
   ./mvnw -B -ntp spotless:check checkstyle:check test
   ./mvnw -B -ntp verify
   docker compose config --quiet
   ./deploy/scripts/validate-helm.sh
   git diff --check
   ```

5. Exercise the authenticated journey from a separate clean clone using [the reviewer quick start](../demo/README.md). Coordinate fixed Compose ports/network before starting a second stack.
6. Submit a focused PR with contract/migration rollout notes, actual checks and explicit skips/limits. Tests do not prove a cluster deployment; archive smoke checks do not prove a running application.

See [CI/CD](ci-cd.md) for the existing checks and [operations](operations.md) for recovery responsibilities. No new runtime service is added by this guide.
