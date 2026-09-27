# Gateway

The Polaris gateway is a Spring Cloud Gateway edge service. It is intentionally limited to public order traffic for the current blueprint stage; internal gRPC and Kafka traffic remain service-to-service and do not pass through the gateway.

## Responsibilities

- Route public order API traffic to `order-service`.
- Validate bearer JWTs as an OAuth2 resource server.
- Apply the public CORS policy.
- Add and propagate `X-Request-Id`.
- Log request method, path, status, duration, request ID, remote address, and error type.
- Apply a global fixed-window rate limit.

## Routes

| Route ID | Method | Public path | Default upstream |
| --- | --- | --- | --- |
| `order-create` | `POST` | `/api/v1/orders` | `http://localhost:8081` |
| `order-read` | `GET` | `/api/v1/orders/{orderId}` | `http://localhost:8081` |
| `order-public-api` | Any | `/api/v1/orders/**` | `http://localhost:8081` |

The upstream is controlled by `polaris.gateway.routes.order-service-uri` outside Docker. The `docker` profile routes to `http://order-service:8081`.

## Security

The gateway runs as an OAuth2 resource server and validates bearer JWTs. Creating an order requires `orders:write`; reading an order requires `orders:read`. The order service validates the bearer token again and uses its UUID-shaped `sub` claim as the customer ID. It never accepts an order owner from the request body, and ownership-aware lookup returns `404` when a different customer requests the order.

The default issuer is the browser-reachable local Keycloak URL. The JWKS URL is independently configurable so containers can fetch keys over the Compose network while still validating the token's external issuer:

```yaml
POLARIS_OAUTH_ISSUER_URI: http://localhost:8089/realms/polaris
POLARIS_OAUTH_JWK_SET_URI: http://keycloak:8080/realms/polaris/protocol/openid-connect/certs
```

Health, info, metrics, and CORS preflight requests are unauthenticated. Any route or method not explicitly granted is denied. See the [local authenticated demo](../../demo/README.md) for the reproducible realm, users, command, and production caveats.

## CORS

The default CORS policy allows `http://localhost:3000`, common API methods, `Authorization`, `Content-Type`, `Idempotency-Key`, and `X-Request-Id`. It exposes `Idempotency-Replayed` and `X-Request-Id` so browser clients can detect safe order replays and correlate gateway logs with responses.

## Request Logging

Every request is logged through a WebFlux filter. If the caller does not send `X-Request-Id`, the gateway uses the WebFlux request ID. The filter returns the request ID in the response, forwards it to upstream services, and places it in MDC as `request.id` while writing the access log.

## Rate Limiting

Rate limiting is enabled globally for routed API requests. Local development uses an in-memory fixed-window limiter. The key is resolved from the authenticated principal, then `X-Forwarded-For`, then the remote address. The default limit is `120` requests per minute.

The `docker` profile switches `polaris.gateway.rate-limit.backend` to `redis`.

## Tests

Gateway tests verify:

- Route definitions for the public order API.
- Unauthenticated order requests are rejected.
- Tokens without the method-specific order scope are rejected.
- Authenticated order requests are forwarded.
- CORS preflight traffic is permitted.
- The in-memory rate limiter returns `429` with `{"error":"rate_limit_exceeded"}` after the configured limit.
