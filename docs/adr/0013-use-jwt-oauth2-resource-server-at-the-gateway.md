# 0013 - Use JWT OAuth2 Resource Server at the Gateway

Date: 2026-05-11

## Status

Accepted

## Context

The project needs to demonstrate production-style API security without adding a full identity provider implementation in the early blueprint stages. The public API should require bearer token authentication, while operational health endpoints and CORS preflight traffic must remain reachable.

## Decision

The gateway runs as a Spring Security OAuth2 resource server and validates JWT bearer tokens. The original placeholder realm is now provided by a development-only Keycloak realm in Docker Compose. Deployed environments supply their own trusted issuer and JWKS endpoint.

Health, info, and CORS preflight requests are public. `/api/v1/orders` and `/api/v1/orders/**` require a JWT with the corresponding order scope. Any other route is denied by default. `order-service` also validates the JWT and derives customer identity from its subject, enforcing ownership on lookup; the gateway is not a substitute for this service-side authorization.

## Consequences

Edge authentication and service-side ownership checks are explicit. Local development has a runnable identity provider; its users and credentials are demo fixtures, not production security configuration.
