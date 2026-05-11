# 0013 - Use JWT OAuth2 Resource Server at the Gateway

Date: 2026-05-11

## Status

Accepted

## Context

The project needs to demonstrate production-style API security without adding a full identity provider implementation in the early blueprint stages. The public API should require bearer token authentication, while operational health endpoints and CORS preflight traffic must remain reachable.

## Decision

The gateway runs as a Spring Security OAuth2 resource server and validates JWT bearer tokens. The default issuer and JWKS URLs point to a local placeholder realm so the identity provider can be added later without changing the application security model.

Health, info, and CORS preflight requests are public. `/api/v1/orders` and `/api/v1/orders/**` require an authenticated JWT. Any other route is denied by default.

## Consequences

Authentication policy is explicit and centralized at the edge. The tradeoff is that local development needs either test security support or a local identity provider once end-to-end manual testing is required.
