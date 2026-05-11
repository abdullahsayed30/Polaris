# 0004 - Use SQL-Based Liquibase Migrations

Date: 2026-05-11

## Status

Accepted

## Context

The service databases need repeatable schema management that works locally, in CI, and in containerized runtime environments. Hibernate schema generation is convenient during prototyping, but it is not an acceptable production migration strategy.

## Decision

Polaris will use Liquibase for schema migrations. Changelogs are SQL-based and split into small ordered files under each service's `db/changelog/changes` directory. Spring Boot runs the Liquibase master changelog for the service at startup.

JPA uses `hibernate.ddl-auto=validate` so application startup verifies that the mapped entities match the migrated schema without allowing Hibernate to mutate production schema.

## Consequences

Schema changes are reviewable, deterministic, and owned by the service that uses the database. The tradeoff is that every model change must include a migration, even during early feature work.
