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

## Consequences

Pull requests get fast feedback on build health, formatting, style, test behavior, static analysis, and repository security. Full integration verification depends on Docker because Testcontainers is part of the accepted test strategy.

The tradeoff is stricter build setup: contributors must use Java 25, configure `core.hooksPath` for local commits, and later JDKs are intentionally rejected until the project chooses a new Java baseline.
