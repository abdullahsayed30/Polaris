# Reviewer quick start and authenticated demo

Run Polaris from a separate clean clone, then verify order creation, idempotent replay, readback and customer isolation through the gateway. Notifications are **simulated**. Local identity, passwords, password grant and plaintext infrastructure are development fixtures.

## Prerequisites and preflight

Use a Bash-compatible environment (macOS/Linux, or WSL with Docker available). Install:

- A JDK **25 exactly** and select it for this shell; a JRE alone cannot compile the project.
- Git, Docker Engine/Desktop with a running daemon, and the Docker Compose plugin supporting `up --wait`.
- `curl` and `jq`; Bash, `awk`, `mktemp` and standard file utilities are used by the demo.

The repository includes the Maven Wrapper pinned to **3.9.15**; a separate Maven installation is unnecessary. First-run wrapper/dependency downloads and container builds need network access to Maven repositories and image registries.

```bash
git clone https://github.com/abdullahsayed30/Polaris.git polaris-review
cd polaris-review
git rev-parse HEAD
git status --short
java -version
./mvnw -v
docker version
docker compose version
curl --version
jq --version
```

Confirm the wrapper reports Java 25/Maven 3.9.15 and Docker reports a server. An empty Git status establishes an unchanged clone; record the commit for reproducible evidence. For a PR review, check out its exact commit before running the steps.

Only one default Polaris Compose stack can use this host at a time: it uses project `polaris`, network `polaris-net`, persistent volumes and fixed host ports. Another checkout/worktree does **not** isolate it. Check who owns an existing stack before starting, stopping or resetting it.

Required free host ports in the current configuration: `3000`, `3200`, `4317`, `4318`, `5432–5434`, `6379`, `8080–8083`, `8089`, `9090`, `9092` and `19090`. Inspect `docker-compose.yml` when changing this configuration.

If this host already has Polaris volumes, read [PostgreSQL 18 volume compatibility](#postgresql-18-volume-layout-and-existing-data) and [Kafka data compatibility](#local-kafka-fixture-and-existing-data) before starting. A fresh Git clone does not establish fresh or migrated database volumes.

## Build, start and prove the HTTP journey

Run from the repository root, sequentially:

```bash
./mvnw -B -ntp clean verify
docker compose config --quiet
COMPOSE_PARALLEL_LIMIT=1 docker compose up --build --wait
docker compose ps
./demo/polaris-demo.sh
```

The Maven command runs unit/contract/architecture checks and Docker-backed integration tests. Read Failsafe reports under each module's `target/failsafe-reports`: tests configured to skip without Docker can still leave a successful Maven exit. A skip does not prove database/Kafka behavior.

The concurrency limit builds/starts services sequentially to avoid competing local Maven image builds. Compose builds the four local-runtime images, starts infrastructure and applies each service's Liquibase migrations. Demo inventory seeds are enabled only in the demo context. First startup downloads image layers and build dependencies; `--wait` waits for configured healthchecks. Some application checks establish TCP availability, so follow them with the authenticated demo. No universal startup duration or memory minimum is claimed; measured observations belong in [demo validation evidence](validation.md).

Expected script output ends with:

```text
Demo passed: order <id> is CONFIRMED; replay kept its ID and header; readback matches; Bob receives 404.
Notifications are simulated. This checks the HTTP journey; database/Kafka guarantees need integration tests.
```

The script exits nonzero at the failing stage and omits response bodies/tokens from diagnostics. It verifies:

1. Two local identities can obtain tokens; Alice's order returns HTTP 201, a confirmed status, the authenticated owner and the requested items.
2. Sending the **same** customer/key/payload again returns HTTP 201, the same order ID and `Idempotency-Replayed: true`.
3. Alice's GET returns HTTP 200 and the same ID, owner, status and full item values/IDs. Item order is normalized; timestamp formatting is not compared.
4. Bob's GET for that order returns HTTP 404.

The fixed demo key makes repeat script runs reuse the same order while the database is retained. It proves HTTP replay behavior, not stock locking or exactly-once external effects. Those guarantees need the integration suites. It does not verify a Kafka delivery, trace or alert merely by printing an observability URL.

## Local configuration and identity

No owner is supplied in the request: the order service uses the validated JWT subject. The checked-in [realm fixture](../deploy/keycloak/polaris-realm.json) and script define the two development users. The public development client is `polaris-cli`, with `orders:read`/`orders:write`; gateway and order service enforce them independently.

The client explicitly includes Keycloak's `oidc-sub-mapper` with `access.token.claim=true`. [Keycloak 26.0.7's mapper](https://github.com/keycloak/keycloak/blob/26.0.7/services/src/main/java/org/keycloak/protocol/oidc/mappers/SubMapper.java) obtains `sub` from the authenticated user's ID; it does not inject a fabricated customer or hardcoded subject. The existing issuer and order scopes stay unchanged.

For fresh-clone runtime evidence, check **both** Alice and Bob against their configured fixture user IDs, the expected issuer and both order scopes. Report only safe predicates such as `subject_matches=true`, `issuer_matches=true` and `order_scopes_present=true`; keep tokens/passwords/admin credentials out of evidence. Claim decoding is a diagnostic: the actual gateway/order journey must still validate the server signature, issuer, scopes and ownership.

[Startup realm import skips an existing realm](https://www.keycloak.org/server/importExport). Editing the JSON and restarting an already-imported Keycloak instance does not automatically apply this mapper. Coordinate with the owner to apply the mapper explicitly or deliberately recreate the disposable local Keycloak service from the fixture, preserving the business database/broker volumes. This is a development-only identity fixture, not an identity migration or production security proof.

The script defaults to `http://localhost:8080` for gateway and `http://localhost:8089` for identity. `POLARIS_GATEWAY_URL`, `POLARIS_IDENTITY_URL`, `POLARIS_REALM` and `POLARIS_CLIENT_ID` can override those values for a compatible demo fixture. Changing the script's URL alone does not change Compose ports, token issuer or service JWKS configuration.

Use appropriate production OAuth flows, TLS, secret management, external identity storage and restricted administration in deployed environments. Compose host bindings are for debugging; production exposes the gateway through TLS ingress and keeps service/data/operational endpoints private. See [deployment](../docs/deployment.md).

## Inspect health and observability

```bash
curl --silent --show-error --fail http://localhost:8080/actuator/health | jq .
curl --silent --show-error --fail http://localhost:3200/ready
docker compose logs --tail=100 gateway order-service inventory-service notification-service
```

| Surface | Local endpoint |
| --- | --- |
| Gateway | `http://localhost:8080` |
| Keycloak realm | `http://localhost:8089/realms/polaris` |
| Order/inventory/notification health | `http://localhost:8081/actuator/health`, `8082`, `8083` |
| Inventory internal gRPC | `localhost:19090` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` |
| Tempo readiness | `http://localhost:3200/ready` |

Follow [observability](../docs/observability.md) for the current scrape, trace, correlation and dashboard conventions and [operations](../docs/operations.md) for triage. Inspect logs before sharing them; exclude credentials, bearer tokens and sensitive payloads. Do not enable `bash -x` or verbose curl tracing for the authenticated script.

## PostgreSQL 18 volume layout and existing data

Each of the three service-owned database volumes mounts at `/var/lib/postgresql`. The [official PostgreSQL image documentation](https://hub.docker.com/_/postgres) specifies the default PostgreSQL 18 data directory as `/var/lib/postgresql/18/docker`. The older `/var/lib/postgresql/data` mount is incompatible with the 18+ default layout, including an unused mount with no database files.

The fix retains the same named volumes and separate order, inventory and notification databases. It changes only their mount targets. **It does not move files, upgrade a cluster or delete data.**

Before using pre-existing volumes, stop writers with the stack owner and identify their actual cluster version/layout; preserve backups and any needed anonymous volumes from previous container configurations. Do not assume an unfamiliar or partially initialized volume is disposable.

| Existing state | Deliberate next action |
| --- | --- |
| Empty/new volume | PostgreSQL 18 can initialize its versioned data directory; the owning service then applies its migrations/demo context. |
| PostgreSQL 18 cluster already under `18/docker` | Retain that volume and use the compatible mount after checking ownership/configuration. |
| Cluster files at the volume root or another legacy location | Preserve and back up first. Changing the target does not relocate files; a same-major layout adjustment needs an explicit plan, correct ownership and validation before restart. |
| Cluster from PostgreSQL 17 or another major version | Keep the original data available to its matching server. Use a planned [PostgreSQL major upgrade](https://www.postgresql.org/docs/18/upgrading.html), such as logical dump/restore or `pg_upgrade`, and validate the result. Moving files or changing the image tag alone is not an upgrade. |
| Disposable local-demo data | The owner may explicitly choose the reset below. It removes all project volumes, including broker/telemetry state; it is not an automatic recovery step. |

If startup reports the 18+ layout error, preserve the volumes and investigate their version/location. No migration or automatic reset is bundled with this quick-start fix.

## Local Kafka fixture and existing data

The default development broker is pinned to `confluentinc/cp-kafka:7.7.12`. The previous `bitnami/kafka:3.7.2` tag is unavailable from the registry. Confluent Platform 7.7 remains in the [Kafka 3.7 family](https://docs.confluent.io/platform/7.7/release-notes/index.html); its [Docker configuration](https://docs.confluent.io/platform/7.7/installation/docker/config-reference.html) uses `KAFKA_*` properties and a `CLUSTER_ID` for KRaft.

The tracked fixture retains the internal `kafka:9092`, controller `kafka:9093` and host `localhost:9092` listeners, a single combined broker/controller, auto-created topics and replication factor one. Its fixed cluster ID is a reproducible local fixture, not a production cluster identity. The `kafka-data` named volume now mounts at `/var/lib/kafka/data`, matching the explicit log directory; the healthcheck uses `kafka-topics`.

**This vendor/data-path change does not migrate existing Bitnami data.** Preserve the old volume, broker metadata, topics and offsets before any transition. Do not point an unfamiliar existing volume at the new broker and assume compatibility: a different layout, owner or cluster ID can cause failure or initialize different storage. Coordinate an explicit, tested migration with the stack owner, or let them deliberately choose a disposable-demo reset. This work only validates a new empty Kafka volume and later retained-data replay in that same fixture. Testcontainers keep their separately pinned broker baseline.

Single-node combined KRaft, plaintext listeners and replication factor one are development fixtures. Production broker operation remains external to Helm; no production deployment or broker vulnerability scan is established by this image change.

## Stop, restart or explicitly reset

**Stop and remove containers/network while retaining data:**

```bash
docker compose down
```

**Restart with retained data compatible with the PostgreSQL 18 layout above:**

```bash
COMPOSE_PARALLEL_LIMIT=1 docker compose up --build --wait
./demo/polaris-demo.sh
```

**Reset this local demo's persisted state:** only after confirming that you own the stack and do not need its data. This deletes service databases, broker data and persisted observability data; it does not delete source files.

```bash
docker compose down --volumes
COMPOSE_PARALLEL_LIMIT=1 docker compose up --build --wait
./demo/polaris-demo.sh
```

The startup reimports development identity fixtures and reapplies migrations/demo seeds. Do not use reset as a fix for production data, incompatible contracts or a migration checksum failure.

## Troubleshooting

| Symptom | Inspect and resolve |
| --- | --- |
| Java/Enforcer failure | Check `java -version` and `./mvnw -v` in the same shell; select JDK 25. Preserve the baseline instead of disabling Enforcer. |
| Docker unavailable or integration suites skipped | Start the daemon and check `docker version`. Rerun verification and inspect Failsafe counts. Unit/mock success is separate evidence. |
| Compose lacks `--wait` | Check `docker compose up --help` and the installed plugin. Use a compatible Compose plugin. |
| Image/dependency download fails | Inspect the exact registry/repository error and network/proxy access. Record unavailable image tags; do not hide failure with undocumented substitutions. |
| Port already allocated / existing project | Check `docker compose ps` and host listeners. Coordinate the other stack owner; another worktree or `-p` alone does not solve fixed ports/network. |
| Unhealthy startup | Run `docker compose ps --all` and `docker compose logs --tail=100 <service>`. Check database, broker and identity dependencies plus migration errors before restarting. |
| PostgreSQL 18+ data-layout error | Inspect the image/mount and [existing-volume layout](#postgresql-18-volume-layout-and-existing-data). Retain data; choose an explicit migration or disposable-demo reset rather than deleting volumes automatically. |
| Liquibase validation/checksum error | Preserve data and inspect the owning service's ordered changelog/schema. Applied changesets are immutable; do not disable validation or automatically delete volumes. |
| Local authentication fails | Check Keycloak readiness and realm import. Keep script realm/client, issuer URL and internal JWKS URL consistent; never share token output. |
| Token obtained but gateway returns 500 / missing JWT subject | Check that `polaris-cli` has the explicit Subject(sub) access-token mapper and both users receive their real subject IDs. An existing realm may have skipped reimport. Verify safe claim predicates without logging tokens; do not fabricate an owner or relax service authorization. |
| Order creation HTTP 401/403 | Check issuer/JWKS reachability and method-specific scopes in both gateway and order service. A client-supplied customer field is not a fix. |
| Order creation HTTP 503 | Inspect inventory readiness/gRPC configuration and durable pending-order recovery. Retry with the original key/payload; do not blindly release stock after a timeout. |
| Replay/readback assertion fails | Record stage/status and order ID, then inspect order logs. Do not change the key/payload to make replay pass or describe the failure as successful idempotency. |
| Bob receives anything other than 404 | Treat customer isolation as failed. Verify gateway and order-service ownership policy; do not weaken the assertion. |
| HTTP demo passes but telemetry is absent | Check scrape targets, trace-export settings and the current observability guide. HTTP success is not telemetry evidence. |

## Checks and evidence

For infrastructure-free checks:

```bash
./mvnw -B -ntp spotless:check checkstyle:check test
bash -n demo/polaris-demo.sh
python3 demo/test-polaris-demo.py
git diff --check
```

Python 3 is needed only for the demo's local HTTP regression harness. It tests success plus broken replay/readback/isolation/authentication responses without Docker; it is not proof that Polaris services work.

Keep [validation.md](validation.md) specific to the tested commit/environment. Record commands, actual integration test counts/skips, Compose/demo outcomes and any fixture changes. For Helm, `./deploy/scripts/validate-helm.sh` requires Helm 3; report lint/render and schema validation separately, including unavailable schema tooling. Neither proves cluster deployment.

To reuse the architecture, follow [How to add a service](../docs/add-service.md).
