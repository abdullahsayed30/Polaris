# 0007 - Package Protobuf Contracts in a Dedicated Module

Date: 2026-05-11

## Status

Accepted

## Context

Service-to-service contracts should not be copied between modules or generated from another service's source tree. If the system is split into multiple repositories later, services will need a stable artifact for shared RPC contracts.

## Decision

Polaris keeps protobuf definitions in a dedicated Maven module named `proto-contracts`. The module owns `.proto` files and generated Java/gRPC classes. Services consume `io.polaris:proto-contracts` as a normal Maven dependency.

While Polaris remains a monorepo, `proto-contracts` inherits the parent version. It is still designed so it can be published and independently versioned if the contracts move to a separate repository.

## Consequences

Contract ownership is explicit, and services can depend on generated stubs without copying source files. The tradeoff is that contract changes must be treated like API changes and reviewed for compatibility.
