# 0017 - Use GitHub Actions Quality and Security Gates

Date: 2026-05-11

## Status

Accepted

## Context

Polaris needs repeatable proof that each service and supporting module builds, follows shared style rules, preserves module boundaries, passes realistic integration tests, and receives baseline security scanning. The project also needs to keep future deployment concerns visible without adding release, registry, Helm, or cluster automation before those artifacts exist.

## Decision

Use GitHub Actions for CI, CodeQL for Java static analysis, Trivy for repository filesystem security scanning, Spotless for formatting, Checkstyle for source hygiene and import boundaries, repository-managed Git hooks configured with `core.hooksPath` for required local pre-commit checks, and Maven Surefire/Failsafe for the unit and integration test split.

Pin Java exactly to version 25 in Maven Enforcer, Maven compiler settings, and CI. Use the Maven Wrapper pinned to Maven 3.9.15.

Run Trivy against the repository filesystem for vulnerabilities, secrets, and misconfigurations. Defer container image, Helm chart, and Kubernetes manifest scans until the deployment artifacts are introduced.

### Accepted Risk Amendment — 2026-10-10

Owner `abdullahsayed30` explicitly accepts the longer temporary risk of these three current Spring CVEs, limited to the four exact versioned package pairs below, through **June 30, 2027 UTC**. The blocking severity gate resumes at **2027-07-01T00:00:00Z**.

| CVE | Exact installed package scope |
| --- | --- |
| CVE-2026-47884 | `pkg:maven/org.springframework/spring-webmvc@6.2.19` |
| CVE-2026-47890 | `pkg:maven/org.springframework/spring-webmvc@6.2.19`, `pkg:maven/org.springframework/spring-webflux@6.2.19` |
| CVE-2026-47892 | `pkg:maven/org.springframework/spring-webflux@6.2.19` |

The installed versions remain vulnerable and runtime applicability is not yet proven; this is neither a patch nor a VEX non-applicability assertion. The official [XsltView advisory](https://spring.io/security/cve-2026-47884/), [SSE advisory](https://spring.io/security/cve-2026-47890/) and [WebFlux advisory](https://spring.io/security/cve-2026-47892/) list the compatible 6.2.20 fix as Enterprise Support Only and 7.0.9 as the public fix. This decision does not amend ADR 0018's platform baseline. No future CVE, other Spring package, different version or LZ4 finding is accepted automatically.

The scoped [native ignore policy](../../.github/security/trivy-gate-ignore.yaml) applies only during HIGH/CRITICAL gate conversion for repository and production-image reports. Full all-severity JSON, SARIF and SBOM remain unsuppressed. Every other vulnerability, secret and failing misconfiguration retains the existing threshold; scan/report failures and the final CI aggregate fail closed. Validate the exact owner, CVE-to-versioned-PURL mapping and UTC cutoff before native filtering. Missing/ambiguous finding identities or missing/malformed/broadened policy must not pass. Summary pages show raw versus effective blockers and the explicit accepted risk.

Remove each exception earlier when a reviewed compatible dependency fix lands. There is no automatic renewal. Before expiry the owner must reassess advisory changes, runtime applicability, compatible fixes and the migration plan. A later extension requires a new explicit owner decision and reviewed policy, validator and documentation changes. See [CI/CD](../ci-cd.md#dependency-finding-and-accepted-risk) for date-boundary semantics and regression evidence.

## Consequences

Pull requests get fast feedback on build health, formatting, style, test behavior, static analysis, and repository security. Full integration verification depends on Docker because Testcontainers is part of the accepted test strategy.

The tradeoff is stricter build setup: contributors must use Java 25, configure `core.hooksPath` for local commits, and later JDKs are intentionally rejected until the project chooses a new Java baseline.
