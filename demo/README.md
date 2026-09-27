# Local authenticated demo

The Compose stack includes a development-only Keycloak realm, two customer users, OAuth scopes, and seeded inventory. From the repository root:

```bash
docker compose up --build --wait
./demo/polaris-demo.sh
```

The script obtains tokens for Alice and Bob, places an idempotent order as Alice, verifies that inventory confirms it, reads it back, and proves that Bob receives `404 Not Found` for Alice's order. The request intentionally contains no `customerId`: the order service uses the validated JWT `sub` claim as the owner. Its stable `Idempotency-Key` also makes repeat runs return the same order rather than reserving stock twice.

The imported local identities are deterministic:

| User | Password | JWT subject |
| --- | --- | --- |
| `alice` | `alice-demo` | `11111111-1111-4111-8111-111111111111` |
| `bob` | `bob-demo` | `22222222-2222-4222-8222-222222222222` |

The public development client is `polaris-cli`. Its tokens carry `orders:read` and `orders:write`; the gateway and order service independently enforce those scopes. Password grant, fixed credentials, `start-dev`, HTTP, and the bootstrap admin account are local-demo conveniences only. Use an authorization-code flow with PKCE or another appropriate production flow, TLS, secret management, a persistent external identity database, and restricted administration in a real environment.

Useful local endpoints:

| Endpoint | URL |
| --- | --- |
| Gateway | `http://localhost:8080` |
| Keycloak realm | `http://localhost:8089/realms/polaris` |
| Gateway health | `http://localhost:8080/actuator/health` |
| Gateway Prometheus metrics | `http://localhost:8080/actuator/prometheus` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` (`admin` / `admin`) |
| Tempo readiness | `http://localhost:3200/ready` |

Compose publishes application, database, broker, identity, and observability ports for local debugging. Those host bindings are not a production network design. In production, expose only the gateway through TLS ingress; keep service, database, broker, JWKS backchannel, and actuator ports on private networks and apply authentication/network policy to operational endpoints.

To reset imported identities and seeded data, remove the local Compose volumes and start again:

```bash
docker compose down --volumes
docker compose up --build --wait
```
