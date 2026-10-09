# Reviewer demo validation

This record distinguishes script/build checks, Docker-backed integration and the running default Compose journey. It is not a production-readiness or cluster-deployment claim.

## Main integration review (2026-10-10)

PR #10 was updated by a normal merge of main `4d2778ffd7002e91e6dfee5b1e8f1aecaf1bcc0a`, preserving its published history. The AGENTS.md overlap was resolved by retaining both the final Rebase and merge preference and main's purpose-based branch naming/no-attribution rules, including the original user identity safeguards. Review found no submitted PR comments or reviews to resolve. The final scope remains the reviewer journey, extension guide, development fixtures and merge preference.

Main now includes the owner-approved, exact-package Spring accepted-risk policy in [ADR 0017](../docs/adr/0017-use-github-actions-quality-and-security-gates.md) and [CI/CD](../docs/ci-cd.md#dependency-finding-and-accepted-risk). The four CVE/package pairs remain vulnerable; the gate-only exception expires at `2027-07-01T00:00:00Z`, with unsuppressed full reports and unchanged HIGH/CRITICAL thresholds for other findings. The earlier failed scans below remain historical results, not current merge blockers by themselves. This PR introduces no additional exception or platform upgrade.

The earlier Docker/runtime evidence remains tied to its recorded source; it is not exact-head runtime proof for this main integration. No Docker/Compose build or local full verification was repeated while the owner reviews the running Grafana stack. The required local fast gate and fresh remote checks must be evaluated on the final integration head before merge; no successful final-head or post-merge check is implied by earlier results.

## Combined security baseline validation (2026-10-07)

The candidate [14c182343ac5a2dbbb582bc095ed91ad677af8d1](https://github.com/abdullahsayed30/Polaris/commit/14c182343ac5a2dbbb582bc095ed91ad677af8d1) combines the reviewed quick-start changes with main's security baseline `32c0ab925d100ae600d1d59f57e6906a054fac67`. The later AGENTS.md merge-preference and evidence edits do not change that runtime source. The checks below supplement the earlier October 6 evidence; they do not replace its historical outcomes.

- The required fast gate passed in 44.321 seconds: 89 unit/contract tests, zero failures/errors/skips. Docker-backed `./mvnw -B -ntp verify` passed in 3:10: those 89 tests plus 32 integration/system tests, zero failures/errors/skips. Bash syntax, the demo's happy path and nine failure scenarios, Compose configuration and whitespace checks also passed.
- All four local-runtime images built serially from the combined source. Default `COMPOSE_PARALLEL_LIMIT=1 docker compose up --build --wait` passed with all 13 services healthy. The same default project, network, ports and retained compatible volumes were used; no data reset, deletion or migration occurred.
- Real Keycloak Alice/Bob tokens had the canonical subjects, issuer and order scopes. The retained old order remained readable. A new key `polaris-pr10-security-14c1823` created confirmed order `7bd44c2f-1cc3-3cab-9270-817e499f0ccf`. Two exact retries retained its ID and replay header; readback matched, and Bob received HTTP 404. Coffee/mug stock changed 98/99→96/98 once and stayed unchanged on every replay. The tracked fixed-key demo passed twice against its retained original order with no additional stock change.
- Separate owning-database checks found committed PUBLISHED OrderCreated event `b7bb96b5-09c4-4e17-85c0-299eb55d0ec1` and InventoryAdjusted event `0920e548-aef9-4ab0-a1d0-68abb3c0a5ef`; their payload identities matched. Both corresponding Notification inbox records were PROCESSED on partition 0/offset 1. Notifications remain simulated. Application image/JAR provenance, test reports and token-free journey/delivery evidence were retained locally. The default stack then stopped with its volumes retained.
- [Combined CI](https://github.com/abdullahsayed30/Polaris/actions/runs/37531606488) passed formatting, tests, packaging and report generation, but its HIGH/CRITICAL repository severity gate failed on **CVE-2026-47884**, `spring-webmvc` 6.2.19; container-image jobs were skipped. [CodeQL](https://github.com/abdullahsayed30/Polaris/actions/runs/37531606095) passed. These results do not establish successful final-head or post-merge CI.
- At the later policy/evidence head [7be8ad5a80f51b40d0039a1633446089366f1f50](https://github.com/abdullahsayed30/Polaris/commit/7be8ad5a80f51b40d0039a1633446089366f1f50), [exact-head CI](https://github.com/abdullahsayed30/Polaris/actions/runs/37533183096) also failed the unchanged HIGH/CRITICAL gate on the same CVE and dependency version. Formatting, unit/integration tests, packaging and report generation passed; image jobs were skipped. [Exact-head CodeQL](https://github.com/abdullahsayed30/Polaris/actions/runs/37533182648) and GitGuardian passed. The mandatory local fast gate for that commit passed in 42.984 seconds. Runtime proof remains tied to `14c1823`; no successful post-merge validation is claimed. A later documentation correction requires its own final-head checks.

[Spring's advisory](https://spring.io/security/cve-2026-47884/) rates this issue MEDIUM and lists 6.2.20 as enterprise-only; the public fix is 7.0.9. The [GitHub advisory](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) rates it CRITICAL, matching the failed scanner table. Runtime source inspection found no XsltView or MVC view-rendering use, which narrows demonstrated applicability but does not remove the affected artifact or satisfy the existing gate. No dependency baseline, severity threshold or suppression was changed. Merge remains blocked pending an approved compatible remediation and successful final checks.

## Tested source and environment

- Date: 2026-10-06 (Africa/Cairo).
- Corrected application/fixture source: [66316651d29ab45f4b9f34e398012486b2acfe41](https://github.com/abdullahsayed30/Polaris/commit/66316651d29ab45f4b9f34e398012486b2acfe41), based on merged architecture baseline `18e9c5e4d6c298f70dad979ccef5c7741f5aca54`. Subsequent evidence/Quick Start concurrency documentation does not change this runtime source.
- A new remote clone checked out that exact source with unchanged Git status. It used the tracked default Compose file, project `polaris`, network `polaris-net` and original host ports, without an override file or alternate project namespace.
- No default Polaris volumes/network existed before this workstream. A PostgreSQL/Keycloak preflight at `cd843a2` initialized new database volumes without business tables/data; the corrected clone retained those volumes, applied demo migrations and started a new empty Kafka volume. Keycloak was recreated from the same corrected import fixture. Other workstreams' stopped containers/volumes were untouched.
- Local host: macOS 26.6.2, arm64; Amazon Corretto JDK 25.0.4; Maven Wrapper 3.9.15; curl 8.7.1; jq 1.7.1.
- Docker client/server: 29.7.2; Compose: 5.3.1; allocation: 8 CPUs, 7,936 MiB. This is an observed allocation, not a recommended minimum or measured capacity. Application container JRE: Temurin 25.0.4.1.
- PostgreSQL runtime: 18.6, using `/var/lib/postgresql/18/docker`. Broker: native Linux arm64 `confluentinc/cp-kafka:7.7.12`, image manifest digest `sha256:686262702020bc0513a5bfa6468bd4e3c10463d783f0569ad9ba0205ea11c4c5`. Registry manifests also include amd64.

## Actual checks

| Command/check | Outcome | What it establishes |
| --- | --- | --- |
| Required commit `./mvnw -B -ntp spotless:check checkstyle:check test` | Passed at `cd843a2` (36.553 seconds) and `6631665` (39.529 seconds); 58 tests, zero failures/errors/skips | Formatting, style, unit tests, contracts and source architecture guards |
| Local `./mvnw -B -ntp verify` at `cd843a2` | BUILD SUCCESS in 2:36; 58 unit/contract + 32 integration/system tests, zero failures/errors/skips | Actual PostgreSQL/Kafka integration under the exclusive local slot; Testcontainers retains its separate broker baseline |
| Fresh corrected clone `./mvnw -B -ntp clean verify` | BUILD SUCCESS in 2:48; 58 unit/contract + 32 integration/system tests, zero failures/errors/skips | Full clean-source verification at `6631665`, after the default stack stopped |
| Bash syntax and `python3 demo/test-polaris-demo.py` | Passed; happy path and nine failure scenarios | Broken authentication, owner/items, replay ID/header, readback and isolation fail without exposing fixture tokens |
| `docker compose config --quiet`, `git diff --check`, relative Markdown links | Passed | Resolved configuration, whitespace and local navigation |
| Serial `docker compose build <service>` for all four applications | Passed | Local-runtime images built from the exact fresh source; no concurrent Maven image builds |
| `COMPOSE_PARALLEL_LIMIT=1 docker compose up --build --wait` | Passed; all 13 services running/healthy | Default corrected infrastructure/application startup; application TCP checks alone are supplemented by the HTTP journey below |
| Fresh Keycloak Alice/Bob claim predicates | Subject, issuer and both order scopes matched for each user | Diagnostic claim decoding plus the actual signed-token gateway/order journey below; no raw tokens retained |
| `./demo/polaris-demo.sh`, then repeat with retained data | Both passed | Confirmed order, same-ID replay/header, matching readback and Bob's HTTP 404 |
| Read-only stock/reservation/order inspection | Passed | Coffee 100→98 and mug 100→99 once; second run unchanged; one RESERVED inventory reservation, one CONFIRMED persisted order and one order outbox record |
| Separate read-only owning-database delivery checks | Both outboxes PUBLISHED; matching Notification inbox events PROCESSED | Actual OrderCreated/InventoryAdjusted delivery through the new default broker, with stable payload/event/order identities |
| Exact-source GitHub checks | All nine successful | [Quality/image workflow](https://github.com/abdullahsayed30/Polaris/actions/runs/37436993994), [CodeQL workflow](https://github.com/abdullahsayed30/Polaris/actions/runs/37436993900), Trivy and GitGuardian checks; image/scanning checks are separate from live Compose proof |
| `./deploy/scripts/validate-helm.sh` | Could not run: Helm absent | No local chart lint/render/schema result; charts unchanged |

## Default authenticated journey

The real imported Keycloak fixtures issued tokens for Alice and Bob. Both subjects matched their canonical configured user IDs; issuer matched `http://localhost:8089/realms/polaris`; both order scopes were present. Evidence prints only `subject_matches`, `issuer_matches` and `order_scopes_present`. The gateway and order service then performed their real JWT validation and ownership checks; no service authorization policy was changed for this proof.

Both script runs confirmed order `dce5f1bb-59f7-35cf-9314-e6c306e660a3`. Alice's creation/replay returned 201, the replay kept its ID and `Idempotency-Replayed: true`, Alice's GET returned 200 with matching owner/status/items, and Bob's GET returned 404. The fixed key/payload/customer remained unchanged across the repeat run.

Read-only inventory queries recorded coffee/mug quantities 100/100 before, 98/99 after the first run, and 98/99 after the second. The only reservation matched that order with `RESERVED`; order persistence showed its canonical Alice owner and `CONFIRMED`, with one order outbox record. This is a concrete single-journey/replay stock observation, not a load/concurrency benchmark or exactly-once external-delivery claim.

The stack stopped before the final clean verification. All default containers/data volumes remain retained; no volume deletion, reset, file relocation, database upgrade or existing broker-data migration was performed. Build/up/stop output, safe claim predicates, both demo runs, stock snapshots, reservation/order state, image provenance and test reports are retained locally for review. The three databases were started alone after clean verification for the delivery inspection below, then stopped again; applications and Kafka were kept stopped during that inspection. The coordinator controls the next local test slot.

## Current-broker asynchronous delivery evidence

Read-only queries inspected the Order, Inventory and Notification databases separately after stopping the stack. Each outbox row's payload metadata event ID matched its persisted event ID; payload order ID and message key matched the confirmed demo order. Both committed `PUBLISHED` rows had publication timestamps, and their corresponding committed Notification inbox rows had `PROCESSED` status and processing timestamps.

| Event | Stable event ID | Producer state | Notification source/state |
| --- | --- | --- | --- |
| OrderCreated | `3b94906b-064d-4377-ab1b-0c48b6975557` | Order outbox PUBLISHED | `polaris.orders.created`, partition 0/offset 0, PROCESSED |
| InventoryAdjusted | `4e1d47c1-0cd7-481a-82c1-953b3617043c` | Inventory outbox PUBLISHED | `polaris.inventory.adjusted`, partition 0/offset 0, PROCESSED |

The producer publication timestamps were 08:53:28 UTC and Notification processing completed at 08:53:29 UTC on the same date, during the default Kafka 7.7.12 fixture run. This establishes actual publication and matching consumer processing for these two events, beyond broker health alone. It does not establish full tracing, DLQ/replay behavior, provider delivery or exactly-once external effects; notifications remain simulated. No runtime database dependency, join, transport implementation or service/business source was added for this inspection.

## Corrected development fixtures and existing data

Three `postgres:18` mounts changed only their targets from `/var/lib/postgresql/data` to `/var/lib/postgresql`, preserving volume identities, separate service databases and all other PostgreSQL settings. [Official image guidance](https://hub.docker.com/_/postgres) specifies the PostgreSQL 18 layout. Existing data requires the explicit compatibility/migration decision described in [the quick-start guide](README.md#postgresql-18-volume-layout-and-existing-data); a mount change is not a major-version upgrade.

The `polaris-cli` client gained one `oidc-sub-mapper` with `access.token.claim=true`. [Pinned Keycloak 26.0.7 source](https://github.com/keycloak/keycloak/blob/26.0.7/services/src/main/java/org/keycloak/protocol/oidc/mappers/SubMapper.java) obtains the subject from the authenticated user's ID. User IDs/credentials, issuer, scopes and service policies remain unchanged. Startup import skips existing realms; the fresh/recreated local identity fixture here is not proof that editing JSON updates an already-imported realm.

The actual default `docker compose pull kafka` at `cd843a2` failed: `docker.io/bitnami/kafka:3.7.2` was not found. The exact legacy tag also had no manifest. The coordinator authorized the minimal development broker repair in this same PR. Live [Confluent 7.7 listener documentation](https://docs.confluent.io/platform/7.7/kafka/multi-node.html) and [release notes](https://docs.confluent.io/platform/7.7/release-notes/index.html) include 7.7.12 in the Kafka 3.7 family; its registry tag resolved for native arm64 and amd64.

The tracked broker maps Confluent KRaft properties individually, uses the explicit local cluster ID/log directory, mounts the same named `kafka-data` volume at `/var/lib/kafka/data`, and uses `kafka-topics` for health. Original internal/controller/external addresses, published 9092 and delivery settings stay intact. No unrelated dependency/Testcontainers baseline or production Helm infrastructure changed. Existing Bitnami storage is not automatically compatible/migrated; see [Kafka existing-data handling](README.md#local-kafka-fixture-and-existing-data).

## Earlier failed local run and limits

At earlier implementation [4313b5c79daf8910bea3c2bf7aa1daad89590934](https://github.com/abdullahsayed30/Polaris/commit/4313b5c79daf8910bea3c2bf7aa1daad89590934), local clean verification failed after 5:21 during order integration setup: Testcontainers' `confluentinc/cp-kafka:7.7.1` readiness log timed out while KRaft controller/quorum startup was incomplete. PostgreSQL/business integration cases had not started and later modules were skipped. Docker SIGKILL/exit 137/removal followed timeout cleanup; they do not establish an independently terminal broker crash.

A post-failure host snapshot showed load 62.71, about 15 GiB physical memory used, 383 MiB unused and 6,739 MiB compressed. Pressure is consistent with slow startup, but causality is not established. Tests/timeouts were not weakened. That failure remains distinct from the subsequent successful exclusive retry. Earlier [Linux quality CI](https://github.com/abdullahsayed30/Polaris/actions/runs/37387010691) passed 58 unit/contract + 32 integration tests, zero failures/errors/skips; it did not turn the failed local attempt into a pass.

No universal startup duration/memory minimum, cluster deployment, load test, measured business SLO, trace/dashboard/alert proof or real notification delivery is claimed. Password grant, local credentials, plaintext single-node combined KRaft and replication factor one are development fixtures. Production infrastructure remains external to Helm. CI's application image scans do not establish a vulnerability scan of this local broker image.
