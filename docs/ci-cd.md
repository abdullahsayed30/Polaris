# CI/CD

Polaris uses GitHub Actions as the project quality and security gate. The build is intentionally pinned to Java 25, and the Maven Wrapper is pinned to Maven 3.9.15 so local and CI behavior match.

## Active Gates

| Gate | Tool | Purpose |
| --- | --- | --- |
| Formatting | Spotless | Java formatting, import ordering, trailing whitespace, and final newlines |
| Style | Checkstyle | Import hygiene, package declarations, braces, naming, and service boundary checks |
| Unit tests | Maven Surefire | Fast non-container tests |
| Integration tests | Maven Failsafe + Testcontainers | PostgreSQL, Kafka, HTTP, and gRPC integration behavior |
| Coverage | JaCoCo | Per-module HTML and XML line coverage reports plus a CI job summary |
| Static analysis | CodeQL | Java security and quality analysis |
| Repository security | Trivy | Dependency, secret, Dockerfile, Compose, and configuration scanning |
| Container security | Docker + Trivy | Production image build, archive smoke test, and image vulnerability scanning |
| Software bill of materials | CycloneDX + Trivy | Aggregate Maven dependency SBOM and one SBOM for each production image |
| Local hooks | Git `core.hooksPath` | Required pre-commit Spotless, Checkstyle, and unit test gate |

The main CI workflow calls a reusable workflow so later release, publish, or deployment pipelines can reuse the same quality gate.

## Local Commands

Use the Maven Wrapper from the repository root:

```bash
./mvnw spotless:check checkstyle:check test
./mvnw verify
```

Configure the required local hooks once per clone:

```bash
git config core.hooksPath .githooks
git config --get core.hooksPath
```

The checked-in `.githooks/pre-commit` hook runs `./mvnw -B -ntp spotless:check checkstyle:check test`. `./mvnw test` excludes `*IntegrationTest` classes. `./mvnw verify` runs integration tests and requires Docker for Testcontainers.

## Trivy Scope

The repository security gate runs Trivy with vulnerability, secret, and misconfiguration scanners enabled. It uploads a full-severity SARIF report when GitHub permissions allow it, then runs a separate blocking scan that fails on `HIGH` and `CRITICAL` findings. Keeping reporting and enforcement as separate scans makes the severity policy explicit and avoids deriving the gate from SARIF text.

After the Maven gate passes, CI builds the production target of every service image, smoke-tests its Java runtime and executable Spring Boot archive, generates a CycloneDX image SBOM, uploads image SARIF, and applies the same `HIGH` and `CRITICAL` severity gate. The repository scan can inspect checked-in Helm source, but the main workflow does not lint, render, schema-validate, or scan the rendered Kubernetes manifests. Those local checks live in `deploy/scripts/validate-helm.sh`; a future release workflow should scan the rendered output that will actually be promoted.

Production targets use the digest-pinned Temurin Java 25 Alpine JRE for both amd64 and arm64. This replaces the Debian 13 Distroless runtime whose base reported eight HIGH Expat and util-linux findings in the 2026-09-30 scan. The selected Alpine base contains Expat 2.8.5 and does not include util-linux. The image still runs as UID/GID 65532, matching Helm, and the HIGH/CRITICAL gate remains unchanged with no vulnerability exclusions. Dependabot tracks the tagged digest so runtime security updates remain reviewable.

The root POM imports Jackson BOM 2.21.7 before the Spring Boot BOM to apply the 2.21 security patches consistently across runtime JSON modules. The 2026-10-05 CI scan identified five HIGH findings across `jackson-core` and `jackson-databind` in Spring Boot 3.5.16's managed 2.21.4 version. Keep the explicit override until the managed baseline includes those fixes; retained-event and exact-decimal contract tests remain part of the gate.

Alpine uses musl rather than glibc and includes a shell and package manager; it is not a shell-free runtime. Validate application startup and native-library compatibility when updating the base, in addition to the archive smoke test. The local-runtime target continues to serve the Compose demo.

Dependabot checks Maven dependencies, GitHub Actions, Docker Compose images, and each service Dockerfile every week. CI artifacts retain test and coverage reports for 14 days and SBOMs for 30 days.

## Deferred Deployment Automation

The enterprise-style deployment workflow is intentionally documented but not active in the current CI scope. Later deployment releases should add:

- Semantic version calculation for release and snapshot branches.
- Git tag creation for release builds.
- Container image publish (CI already builds and scans images without pushing them).
- Helm chart packaging and publish.
- Helm lint/render, Kubernetes schema validation, and Trivy scanning of rendered manifests.
- Optional ArgoCD deployment promotion once target clusters and credentials exist.
