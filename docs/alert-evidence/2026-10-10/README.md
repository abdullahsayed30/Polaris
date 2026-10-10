# Local alert firing and recovery evidence — 2026-10-10

This is an isolated application demonstration, not measured production SLO attainment, cluster readiness or external notification delivery. All times below are UTC.

| Checkpoint | Observed result |
| --- | --- |
| 02:47:51 healthy baseline | Four application scrapes, three complete valid business snapshots, an authenticated warm order and committed simulated processing; no active alerts |
| 02:48:08 pending | Only this fixture's inventory service stopped; a failed create returned 503 and retained a committed `PENDING` order; scrape-loss alert visibly pending |
| 02:52:09 firing | `PolarisServiceScrapeUnavailable` and `PolarisPendingOrdersStalled` both firing, with normal checked-in thresholds/durations |
| 02:53:28 recovery | Original persisted order committed `CONFIRMED` after inventory restart |
| 02:53:39 resolved | All alerts inactive, three valid business snapshots; authenticated idempotent replay returned the original confirmed order ID |
| Cleanup | Fixture containers, network and volumes removed; cleanup query results empty |

The fixture ran the exact application source at `bf6c2c9bec104e356fa51510eb709b94fa1db97b`, freshly packaged with Java 25 and the pinned Maven Wrapper. This alert PR does not modify application source. [Provenance](provenance.json) records the source commit, each mounted JAR SHA-256 and the evaluated rule SHA-256; [timeline](timeline.json) records the unique fixture identity and action times. [Firing rule state](firing-rules.json) retains evaluator health and actual expressions. The rule hash matches the checked-in rule file.

Raw Prometheus API results are retained for [healthy](healthy-alerts.json), [pending](pending-alerts.json), [firing](firing-alerts.json) and [recovered](recovered-alerts.json) states, alongside each phase's `*-queries.json`. Those queries verify actual emitted HTTP/gateway/gRPC label signatures, snapshot/backlog gauges, notification completion/DLQ outcomes and the explicit one-hour freshness bucket. The [60-second boundary check](freshness-boundary-check.json) returned no series, so the delay rule uses the observed one-hour boundary rather than an invented 60-second bucket.

[Pending committed state](pending-order.json), the [failed create](failed-create-response.json) and [recovered order/replay](recovered-order.json) show the same persisted identity moving from `PENDING` to `CONFIRMED`. [Cleanup](cleanup.json) confirms no resources from the generated project remain. The pre-existing Grafana review stack stayed paused and was excluded from all fixture commands; no fixed host port, shared network or existing volume was reused.

The [validation summary](validation-summary.json) records the fast gate (119 tests, zero failures/errors/skips), 47 alert scenarios (600 alert-state assertions plus 3 query assertions), six existing freshness scenarios (12 exact dashboard-query assertions), syntax/configuration checks and both Compose configuration checks. Other HTTP/gRPC error, outbox, notification failure/delay and missing/stale telemetry paths have synthetic rule coverage, not a live injected fault in this run. The demo does not exercise actual provider delivery, broker backlog, automatic redrive, cluster deployment or a production on-call receiver.

An earlier disposable run correctly cleaned up after a harness startup race: a scrape had recovered before the next rule evaluation cleared its pending state. The final harness waits for that evaluation; no rule threshold was weakened. Trace export and Grafana rendering are excluded from this smaller fixture. The full observability evidence is documented separately.

Reproduce with the commands in [operations](../../operations.md#repeatable-alert-evidence). Evidence timestamps are retained observations from this run; they do not imply that the disposable URLs are still running.

Rule validation was integrated into the existing required CI quality job after this live run. That CI wiring does not change evaluated rules or application JARs; both retained hashes remain unchanged. The CI failure-handling unit suite also passed 28 tests. Exact PR-head remote checks are reported on the PR separately from this retained local evidence.

The harness subsequently reads its local demo login from the existing checked-in Keycloak realm instead of duplicating an inline credential pair. The declared account/values and token request behavior are unchanged; no real account/provider integration was added. This narrow harness change did not alter application JARs or evaluated rules.
