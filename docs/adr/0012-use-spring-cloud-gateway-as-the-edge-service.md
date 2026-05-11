# 0012 - Use Spring Cloud Gateway as the Edge Service

Date: 2026-05-11

## Status

Accepted

## Context

External traffic needs a single entry point for routing and edge concerns. Pushing authentication, CORS, rate limiting, and request correlation into every service would duplicate code and weaken the boundary between public APIs and internal service APIs.

## Decision

Polaris will use Spring Cloud Gateway as the edge service. The gateway exposes the public order API route surface under `/api/v1/orders/**` and forwards to `order-service`. Internal gRPC calls and Kafka traffic remain service-to-service and do not pass through the gateway.

The gateway owns request logging, request ID response propagation, CORS policy, authentication enforcement, and global rate limiting.

## Consequences

Public API policy is centralized, and domain services stay focused on business behavior. The tradeoff is that the gateway becomes part of the critical request path and needs its own tests, observability, and deployment scaling policy.
