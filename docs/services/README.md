# Services

Polaris has four deployable runtime services and two supporting build modules. Runtime services own their inbound contracts, outbound dependencies, configuration, and tests. Supporting modules provide shared event types and generated RPC contracts.

## Runtime Services

| Service | Port | Primary role | Documentation |
| --- | --- | --- | --- |
| Gateway | `8080` | External HTTP edge, JWT validation, CORS, request logging, rate limiting | [Gateway](gateway.md) |
| Order Service | `8081` | Public order API, order lifecycle, inventory orchestration, order event publishing | [Order Service](order-service.md) |
| Inventory Service | `8082` HTTP, `9090` gRPC | Stock checks, stock reservations, inventory persistence, inventory event publishing | [Inventory Service](inventory-service.md) |
| Notification Service | `8083` actuator in Docker | Kafka-driven notification workflow with retry and dead-letter handling | [Notification Service](notification-service.md) |

## Supporting Modules

| Module | Role |
| --- | --- |
| [`proto-contracts`](../proto-contracts.md) | Owns protobuf definitions and generated gRPC Java stubs |
| [`shared`](../shared.md) | Owns shared Java domain event payloads used by Kafka producers and consumers |

The runtime services should communicate through REST, gRPC, and Kafka contracts. They must not read another service's database or use shared Java code to bypass a service boundary.
