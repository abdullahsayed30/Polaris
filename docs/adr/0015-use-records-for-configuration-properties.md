# 0015 - Use Records for Configuration Properties

Date: 2026-05-11

## Status

Accepted

## Context

Polaris services bind runtime settings with Spring Boot `@ConfigurationProperties`. These configuration types represent externalized application settings such as gRPC endpoints, retry policy, CORS policy, and rate-limit policy.

Spring Boot supports constructor binding for Java records. With a single canonical constructor, records do not require `@ConstructorBinding`. Records provide concise immutable value objects, which fits Polaris' preference for explicit service contracts and runtime configuration that is read after startup rather than mutated during execution.

## Decision

Polaris will use Java records as the default shape for application-owned `@ConfigurationProperties` types.

Defaults and validation should be expressed with compact record constructors or supported Spring Boot binding annotations where appropriate.

Mutable JavaBean-style `@ConfigurationProperties` classes should be reserved for cases where mutation is required by a framework integration, third-party binding target, or another explicit technical constraint.

## Consequences

Configuration property types are immutable after binding.

Configuration contracts stay compact and easy to review.

Services keep a consistent style for typed runtime settings.

When mutable configuration binding is used, the reason should be clear from local code or a follow-up ADR.
