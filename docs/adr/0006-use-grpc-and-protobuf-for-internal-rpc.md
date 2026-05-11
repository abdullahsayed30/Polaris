# 0006 - Use gRPC and Protobuf for Internal RPC

Date: 2026-05-11

## Status

Accepted

## Context

Some internal service calls need an immediate response. Order placement needs to know whether inventory is available and whether stock was reserved before it can confirm or cancel an order. Modeling that interaction as an asynchronous event would make the initial order API more complex and less direct.

## Decision

Polaris will use gRPC with Protobuf for synchronous internal service-to-service calls. The first internal contract is `InventoryService`, which exposes `CheckStock` and `ReserveStock`. `order-service` consumes the generated blocking stub, and `inventory-service` implements the generated service base.

These APIs are internal only. External clients use REST through the gateway.

## Consequences

Internal RPC contracts are strongly typed, explicit, and efficient. The tradeoff is extra build tooling for generated code and the need to manage protobuf compatibility over time.
