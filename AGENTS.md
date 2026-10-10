# Polaris agent instructions

These instructions apply to the entire repository and to every delegated task. Read this file before editing. The project uses **Architecture Decision Records (ADRs)**; references to EDRs mean those records unless the user says otherwise.

## Read before changing code

1. Read [the service architecture standard](docs/service-architecture-standard.md), [architecture overview](docs/architecture.md), and [ADR index](docs/adr/README.md).
2. Read the accepted ADRs and service documentation relevant to the affected behavior. Follow explicit superseding records: ADR 0019 replaces ADR 0009's after-commit listener mechanism with a transactional outbox; ADR 0020 extends ADR 0010 with durable pending-order recovery; ADR 0021 supersedes ADR 0005 for the three business services and updates ADR 0015/0019 package placement.
3. Inspect the current code, tests, Git diff, and overlapping work before editing. Existing uncommitted changes belong to the user; preserve them.
4. State any actual conflict between the requested change and an accepted ADR. Prefer an implementation within the agreed architecture. A material architectural change requires a concrete proposal and an explicit ADR amendment or superseding record; do not silently change a rule to make code pass.

## Boundaries that must remain true

- Runtime services are independently deployable. Never add a runtime service module dependency on another runtime service, copy its implementation, or access its database. `contract-tests` alone may depend on all services with **test scope**. Do not disable production Maven Enforcer or import controls to accommodate tests.
- Each stateful service owns its PostgreSQL database and Liquibase migrations. Order, inventory, and notification have separate databases; the gateway owns no business database. Never add cross-service joins, foreign keys, shared tables, or distributed database transactions.
- External APIs use REST through the gateway; synchronous internal calls use generated gRPC contracts from `proto-contracts`; asynchronous events use Kafka. Do not replace starter-managed gRPC server/channel lifecycle with manually managed transport code.
- `shared` contains stable Java event contracts and small value types only. It must not contain Spring configuration, business use cases, entities, repositories, clients, or messaging infrastructure. Do not centralize service-specific outbox implementations there merely to remove duplication.
- Keep Java 25 and the pinned Maven Wrapper baseline. Manage common dependency/plugin versions in the root POM and preserve production module-boundary enforcement.

## Package ownership and dependency direction

ADR 0021 applies to **order-service, inventory-service and notification-service only**. Gateway retains its edge structure; supporting modules and generated contracts are excluded.

| Package | Responsibility |
| --- | --- |
| `application.domain.model` | Plain business entities, values, transitions and invariants; no JPA or framework dependencies |
| `application.domain.service` | Use cases, orchestration and business transactions |
| `application.port.in` / `.out` | Inbound use-case contracts and outbound capabilities with plain inputs/results |
| `adapter.in.web`, `.grpc`, `.messaging`, `.scheduling` | Transport/request mapping and use-case triggers through inbound ports |
| `adapter.out.persistence` | Separate JPA entities, repositories, mappers, storage adapters, inbox/outbox recording |
| `adapter.out.grpc`, `.messaging`, `.observability`, `.retry`, `.logging` | Existing integration, delivery, telemetry, retry and simulated handler adapters |
| Service root | Bootstrap, Spring wiring and typed configuration records |

- Application services depend on outbound ports and pure models. Spring DI/transaction annotations and SLF4J are allowed here; Spring Data, JPA, transport types, concrete adapters, bound configuration, Micrometer and Resilience4j are not.
- Inbound adapters invoke inbound ports, not implementation classes or outbound adapters. Kafka record decoding and acknowledgement remain in the adapter; business transactions and outcomes remain in the use case.
- Models and ports are framework independent. The former ADR 0005 convention permitting JPA annotations on business models is superseded by ADR 0021.
- Plain models are detached snapshots. Persist mutations explicitly through a storage port; map onto managed JPA entities while preserving identity, child ownership, timestamps, decimals and optimistic versions. Do not rely on dirty checking of a business model.
- Storage adapters join the business transaction and retain locks until it completes. Outbox recording must join that same transaction. Infrastructure publishers may own transactions that lock/update delivery records.
- Inbox/outbox bookkeeping entities belong in `adapter.out.persistence`. Configuration properties remain records and live at the service root. Root wiring maps bound properties to plain application policy.
- Do not create packages, business models or ports for hypothetical features. Preserve package consistency and update imports, tests and documentation when moving classes. Keep justified exceptions explicit and narrowly guarded.

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

- Prefer Rebase and merge for final pull request integration unless the user explicitly chooses another strategy. If it is unavailable, first use the authorized branch-history updates below to make it possible. Obtain the user's agreement before using another merge strategy.
- Name new branches by purpose: `feature/`, `fix/` or `bugfix/`, `chore/`, `docs/`, or `refactor/`, followed by a short descriptive name. Do not use an assistant or tool name as a branch prefix. This convention does not authorize renaming existing published branches.
- Force-pushing working and pull request branches is allowed when needed to rebase, update, or linearize history within an authorized task. This is standing permission; do not ask again for each such push.
- Use an explicit `--force-with-lease=<ref>:<expected-remote-SHA>`, never plain `--force`. Fetch and inspect the current remote branch, preserve its original tip in a local backup ref, and retain other contributors' changes. If the remote tip changes or the lease is rejected, inspect and reconcile the new commits before trying again; never bypass the lease.
- After rewriting a published branch, review the resulting diff and rerun required checks on the new head before merging. This permission does not authorize force-pushing `main`, protected branches, or release tags; those targets require an explicit user request.
- Keep repository-facing content free of coding-assistant or assistant-vendor branding and attribution, including branch names, commit messages, pull request titles/descriptions, documentation, and release notes. Use the user's identity and describe the project change directly.
- Commit and push only using the user's GitHub account, `abdullahsayed30`, and their Git identity. Verify the authenticated account and commit author/committer before pushing; if authentication is missing or belongs to another account, stop and ask the user to sign in with their account.
- Use the user's existing repository identity (`abdullahsayed30 <abdullahsayed30@users.noreply.github.com>`) unless they explicitly request another identity. Keep any necessary Git identity configuration local to this repository, not global.
- Do not add an assistant, delegated agent, or vendor as author, committer, or co-author. Do not add `Co-authored-by` trailers or automated assistant attribution to commit messages.
- Inspect the final commit metadata and message to confirm these rules. Do not rewrite existing history or force-push merely to change attribution without explicit authorization.

## Verification and handoff

1. Run the fast gate: `./mvnw -B -ntp spotless:check checkstyle:check test`. Architecture guard tests live in `contract-tests` and must stay enabled.
2. For persistence, concurrency, recovery, Kafka, or transaction changes, run the applicable Testcontainers suites with Docker and ultimately `./mvnw -B -ntp verify`. If Docker is unavailable, explicitly report the unexecuted checks; compilation and mocks are not proof of database concurrency behavior.
3. For deployment changes, run Compose configuration checks or `./deploy/scripts/validate-helm.sh` as applicable. Schema-valid manifests do not prove a successful cluster deployment.
4. Run `git diff --check`; reconcile README, service docs, contracts, and ADR status/index with the final behavior. Do not replace implementation fixes with weaker documentation or weaker tests.
5. Report actual validations, limitations, and outstanding risks. Do not claim all architectural rules are enforced just because Checkstyle passes.

When delegating, assign file ownership, relay these rules, and coordinate overlapping files. Do not run simultaneous Maven reactors in this shared checkout: they overwrite the same `target/` outputs. Let one coordinator run the final combined validation.
