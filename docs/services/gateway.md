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

The gateway runs as an OAuth2 resource server and validates bearer JWTs. The default issuer and JWKS URLs are local placeholders:

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: http://localhost:8089/realms/polaris
spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://localhost:8089/realms/polaris/protocol/openid-connect/certs
```

Health, info, and CORS preflight requests are unauthenticated. `/api/v1/orders` and `/api/v1/orders/**` require an authenticated JWT. Any other route is denied by default.

## CORS

The default CORS policy allows `http://localhost:3000`, common API methods, `Authorization`, `Content-Type`, and `X-Request-Id`. It exposes `X-Request-Id` so clients can correlate gateway logs with responses.

## Request Logging

Every request is logged through a WebFlux filter. If the caller does not send `X-Request-Id`, the gateway uses the WebFlux request ID. The filter returns the request ID in the response, forwards it to upstream services, and places it in MDC as `request.id` while writing the access log.

## Rate Limiting

Rate limiting is enabled globally for routed API requests. Local development uses an in-memory fixed-window limiter. The key is resolved from the authenticated principal, then `X-Forwarded-For`, then the remote address. The default limit is `120` requests per minute.

The `docker` profile switches `polaris.gateway.rate-limit.backend` to `redis`.

## Tests

Gateway tests verify:

- Route definitions for the public order API.
- Unauthenticated order requests are rejected.
- Authenticated order requests are forwarded.
- CORS preflight traffic is permitted.
- The in-memory rate limiter returns `429` with `{"error":"rate_limit_exceeded"}` after the configured limit.
