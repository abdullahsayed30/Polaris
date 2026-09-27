# Polaris agent instructions

These instructions apply to the entire repository and to every delegated task. Read this file before editing. The project uses **Architecture Decision Records (ADRs)**; references to EDRs mean those records unless the user says otherwise.

## Read before changing code

1. Read [the service architecture standard](docs/service-architecture-standard.md), [architecture overview](docs/architecture.md), and [ADR index](docs/adr/README.md).
2. Read the accepted ADRs and service documentation relevant to the affected behavior. Follow explicit superseding records: ADR 0019 replaces ADR 0009's after-commit listener mechanism with a transactional outbox; ADR 0020 extends ADR 0010 with durable pending-order recovery.
3. Inspect the current code, tests, Git diff, and overlapping work before editing. Existing uncommitted changes belong to the user; preserve them.
4. State any actual conflict between the requested change and an accepted ADR. Prefer an implementation within the agreed architecture. A material architectural change requires a concrete proposal and an explicit ADR amendment or superseding record; do not silently change a rule to make code pass.

## Boundaries that must remain true

- Runtime services are independently deployable. Never add a runtime service module dependency on another runtime service, copy its implementation, or access its database. `contract-tests` alone may depend on all services with **test scope**. Do not disable production Maven Enforcer or import controls to accommodate tests.
- Each stateful service owns its PostgreSQL database and Liquibase migrations. Order, inventory, and notification have separate databases; the gateway owns no business database. Never add cross-service joins, foreign keys, shared tables, or distributed database transactions.
- External APIs use REST through the gateway; synchronous internal calls use generated gRPC contracts from `proto-contracts`; asynchronous events use Kafka. Do not replace starter-managed gRPC server/channel lifecycle with manually managed transport code.
- `shared` contains stable Java event contracts and small value types only. It must not contain Spring configuration, business use cases, entities, repositories, clients, or messaging infrastructure. Do not centralize service-specific outbox implementations there merely to remove duplication.
- Keep Java 25 and the pinned Maven Wrapper baseline. Manage common dependency/plugin versions in the root POM and preserve production module-boundary enforcement.

## Package ownership and dependency direction

| Package | Responsibility |
| --- | --- |
| `api` | Thin REST/gRPC request mapping, validation, response conversion, exception mapping |
| `application` | Use cases, orchestration, business transaction boundaries, small application-owned ports and plain inputs/results |
| `domain` | Business entities, value objects, state transitions and invariants |
| `persistence` | Spring Data repositories, persistence adapters, infrastructure bookkeeping entities such as outbox/inbox records |
| `messaging` | Kafka transport decoding/encoding, producer/consumer adapters, scheduled outbox transport publishing |
| `inventory` or another named integration | Small outbound client interface and its adapter, following `InventoryClient` |
| `config` | Spring wiring, schedulers that trigger application use cases, typed configuration records |

- Application services depend on small outbound ports, not concrete Kafka/outbox adapters. Recording an event through a port must join the local business transaction; the adapter owns serialization and persistence.
- Kafka listeners delegate transactional deduplication, processing and state transitions to application services. Keep Kafka `ConsumerRecord`, offsets and transport decoding at the adapter boundary; pass plain delivery metadata when needed.
- Infrastructure publishers may own transactions that lock/update delivery records. This is distinct from moving a business use case into a transport adapter.
- Domain types must not depend on Spring, Kafka, gRPC, HTTP, clients, repositories, or other service implementations. JPA mapping annotations on business entities are an existing, intentional lightweight-architecture convention; this is not a mandate to rewrite the project as strict clean architecture.
- Put outbox/inbox repositories and bookkeeping models under `persistence`, not `messaging`. Put `@ConfigurationProperties` records under `config`. Do not move a Kafka-dependent model into `domain` to satisfy a package rule; remove the transport coupling.
- Preserve package consistency and update imports, tests, and documentation when moving classes. A justified exception must be explicit in the standard and narrowly reflected in the architecture checks.

## Reliability and contracts

- Keep atomic, order-keyed inventory reservation with deterministic SKU locking. An exact retry returns the stored decision; conflicting payloads fail; repeated release must not restore stock twice. Do not reintroduce check-before-reserve.
- Bind HTTP idempotency keys to the authenticated customer and original request fingerprint. Preserve the order ID across retries and recovery.
- Do not assume a remote operation rolls back with the caller's database transaction. Persist recovery intent before remote mutation and recover uncertain results using the same identity. Never blindly release stock after a timeout: the outcome may be unknown.
- Business state and its outbox entry commit together. Kafka publication follows that commit. Delivery is at least once; preserve stable event IDs and consumer inbox deduplication. Never acknowledge a source record when DLQ publication fails.
- Use the managed entity returned by repository `save`/`saveAndFlush` when changing it afterward; assigned IDs may cause JPA to merge into a different instance. Tests must verify committed inbox/outbox state and rollback, not only handler invocations.
- A notification stub must say that delivery is simulated. Real email/SMS/webhook integrations require provider idempotency; the inbox alone cannot guarantee exactly-once external effects.
- Treat HTTP, Kafka, and protobuf as public contracts. Read [contract rules](contracts/README.md), test representative previous payloads as well as current shapes, and document rollout/replay behavior. Do not silently reject retained metadata-less events or reinterpret malformed metadata as legacy input.
- Preserve monetary precision and scale when adapting event payloads. Avoid passing typed decimal fields through a floating-point JSON tree; test both old and current wire shapes with exact decimal values.
- Never reuse protobuf field numbers or change existing wire types. Introduce a new contract version for incompatible changes unless an explicit migration strategy preserves existing consumers/producers.
- Existing Liquibase changesets are immutable. Add small ordered SQL migrations, include them in the service master changelog, and retain `ddl-auto=validate`. Demo data must stay behind an explicit demo context.

## Security and deployment

- Enforce JWT scopes and customer ownership; derive the customer from the validated identity, never a client-supplied owner field. Keep gateway edge policy and order-service authorization consistent.
- Local Keycloak credentials/password grant and plaintext demo infrastructure are development fixtures. Never describe them as production security.
- Helm owns application workloads and references external infrastructure/secrets. Do not silently add production PostgreSQL/Kafka operation to the chart. Preserve non-root containers, probes, resource controls, and documented network policy limitations.
- SLOs, deployment readiness, notification delivery, and test results must be truthful. Distinguish a proposed objective from a measured result, and an archive smoke test from a live application check.

## Git identity and attribution

- Commit and push only using the user's GitHub account, `abdullahsayed30`, and their Git identity. Verify the authenticated account and commit author/committer before pushing; if authentication is missing or belongs to another account, stop and ask the user to sign in with their account.
- Use the user's existing repository identity (`abdullahsayed30 <abdullahsayed30@users.noreply.github.com>`) unless they explicitly request another identity. Keep any necessary Git identity configuration local to this repository, not global.
- Do not add Codex, OpenAI, an assistant, or a delegated agent as author, committer, or co-author. Do not add `Co-authored-by` trailers or automated assistant attribution to commit messages.
- Inspect the final commit metadata and message to confirm these rules. Do not rewrite existing history or force-push merely to change attribution without explicit authorization.

## Verification and handoff

1. Run the fast gate: `./mvnw -B -ntp spotless:check checkstyle:check test`. Architecture guard tests live in `contract-tests` and must stay enabled.
2. For persistence, concurrency, recovery, Kafka, or transaction changes, run the applicable Testcontainers suites with Docker and ultimately `./mvnw -B -ntp verify`. If Docker is unavailable, explicitly report the unexecuted checks; compilation and mocks are not proof of database concurrency behavior.
3. For deployment changes, run Compose configuration checks or `./deploy/scripts/validate-helm.sh` as applicable. Schema-valid manifests do not prove a successful cluster deployment.
4. Run `git diff --check`; reconcile README, service docs, contracts, and ADR status/index with the final behavior. Do not replace implementation fixes with weaker documentation or weaker tests.
5. Report actual validations, limitations, and outstanding risks. Do not claim all architectural rules are enforced just because Checkstyle passes.

When delegating, assign file ownership, relay these rules, and coordinate overlapping files. Do not run simultaneous Maven reactors in this shared checkout: they overwrite the same `target/` outputs. Let one coordinator run the final combined validation.
