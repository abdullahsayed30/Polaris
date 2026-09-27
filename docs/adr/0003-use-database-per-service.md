# 0003 - Use Database Per Service

Date: 2026-05-11

## Status

Accepted

## Context

Polaris is a microservices blueprint, so service ownership needs to be visible in both code and data. Sharing tables across services would make the sample simpler, but it would hide the boundaries that the project is meant to demonstrate.

## Decision

Each stateful service owns its own PostgreSQL database and schema. `order-service` owns `polaris_orders` and the order tables. `inventory-service` owns `polaris_inventory` and the inventory tables. `gateway` does not own domain data.

Originally, notification was a database-free consumer. [ADR 0019](0019-use-transactional-outbox-and-consumer-inbox.md) extends this decision: `notification-service` now owns `polaris_notifications` and its durable inbox, with its own Liquibase migrations. It never accesses another service's tables.

Services must not read or write another service's database. Cross-service reads and state changes happen through public service contracts: REST at the edge, gRPC for synchronous internal calls, and Kafka for asynchronous domain events.

## Consequences

Service ownership is explicit, and database migrations can be deployed with the owning service. The tradeoff is that cross-service workflows require contracts, events, and eventual consistency instead of direct joins or shared transactions.
