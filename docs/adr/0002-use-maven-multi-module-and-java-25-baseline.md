# 0002 - Use Maven Multi-Module and Java 25 Baseline

Date: 2026-05-11

## Status

Accepted

## Context

Polaris needs to show multiple independently deployable services while remaining easy to build, review, and run as a single portfolio repository. The project also needs a clear runtime and dependency baseline so every module compiles against the same Java, Spring Boot, Spring Cloud, gRPC, Kafka, Liquibase, and Testcontainers versions.

## Decision

We will use a Maven multi-module build with the root project `io.polaris:polaris` as a parent POM. The parent manages dependency versions, plugin versions, Java compilation, and Maven Enforcer rules. Runtime service modules, `shared`, and `proto-contracts` all participate in the same reactor build.

Java 25 is the exact project baseline. The compiler release is set to `25`, and the Maven Enforcer plugin requires `java.specification.version` to be `25`. The Maven Wrapper is pinned to Maven `3.9.15`.

## Consequences

The full system can be verified with one Maven command, and version drift across modules is reduced. The tradeoff is that module releases are coupled while Polaris remains a monorepo. If the services are split later, each repository will need its own build and dependency management policy.
