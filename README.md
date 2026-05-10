# Polaris

[![Build](https://img.shields.io/badge/build-GitHub_Actions_placeholder-lightgrey)](#)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.x-6DB33F)](#)
[![Java](https://img.shields.io/badge/Java-25-007396)](#)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](#)

Polaris is a production-grade microservices blueprint for a small e-commerce order flow. It is designed as a portfolio anchor: one repository that demonstrates REST at the edge, gRPC between services, Kafka for event choreography, database-per-service persistence, Liquibase migrations, containerized local development, observability, CI, and deployable Kubernetes artifacts.

## Architecture

```mermaid
flowchart LR
    Client[Clients] --> Gateway[API Gateway]

    Gateway -->|REST /api/v1/orders| Order[Order Service]
    Gateway -->|REST internal admin| Inventory[Inventory Service]
    Gateway -->|REST health and ops| Notification[Notification Service]

    Order <-->|gRPC CheckStock / ReserveStock| Inventory

    Order -->|polaris.orders.created| Kafka[(Kafka)]
    Inventory -->|polaris.inventory.adjusted| Kafka
    Notification -->|polaris.notifications.dlq| Kafka

    Kafka -->|order events| Inventory
    Kafka -->|order and inventory events| Notification
```

## Stack

| Layer             | Choice                                       |
|-------------------|----------------------------------------------|
| Build             | Maven multi-module                           |
| Java              | 25                                           |
| Spring Boot       | 3.4.x                                        |
| Spring Cloud      | 2024.0.x                                     |
| Database          | PostgreSQL 18, one database per service      |
| Schema migrations | Liquibase                                    |
| Async messaging   | Apache Kafka 3.7                             |
| Internal RPC      | gRPC + Protobuf                              |
| Auth              | Spring Security + JWT OAuth2 resource server |
| Testing           | JUnit 5, Mockito, Testcontainers             |
| Metrics           | Micrometer, Prometheus, Grafana              |
| Tracing           | OpenTelemetry, Jaeger                        |
| Logs              | SLF4J + Logback, ELK-ready structured output |
| Container runtime | Docker, Docker Compose                       |
| Orchestration     | Kubernetes manifests and Helm chart          |
| CI                | GitHub Actions and CodeQL                    |

## Modules

| Module                 | Responsibility                                                                    |
|------------------------|-----------------------------------------------------------------------------------|
| `proto-contracts`      | Versioned protobuf contracts and generated gRPC Java stubs                        |
| `shared`               | Shared Java events, error models, and cross-service support code                  |
| `gateway`              | Spring Cloud Gateway routes, JWT validation, CORS, rate limiting, request logging |
| `order-service`        | Order REST API, order lifecycle, Postgres persistence, Kafka event publishing     |
| `inventory-service`    | gRPC inventory API, stock reservations, inventory persistence, Kafka consumers    |
| `notification-service` | Kafka-driven notification workflow with retry and dead-letter handling            |

## Quick Start

Prerequisites:

- Java 25
- Maven 3.9.6 or newer
- Docker with Docker Compose

Run the full local stack from the repository root:

```bash
docker-compose up --build
```

Expected local endpoints once the service skeleton and compose stack are in place:

| Service                       | URL                                     |
|-------------------------------|-----------------------------------------|
| Gateway                       | `http://localhost:8080`                 |
| Order Service actuator        | `http://localhost:8081/actuator/health` |
| Inventory Service actuator    | `http://localhost:8082/actuator/health` |
| Notification Service actuator | `http://localhost:8083/actuator/health` |
| Prometheus                    | `http://localhost:9090`                 |
| Grafana                       | `http://localhost:3000`                 |
| Jaeger                        | `http://localhost:16686`                |

Build and verify all modules:

```bash
./mvnw clean verify
```

If the Maven wrapper has not been generated yet:

```bash
mvn clean verify
```

## Domain Flow

1. A client places an order through the gateway.
2. `order-service` persists the order as `PENDING`.
3. `order-service` calls `inventory-service` over gRPC `CheckStock`.
4. If stock is available, `order-service` calls `ReserveStock` over gRPC.
5. `inventory-service` reserves stock in its own database and publishes `polaris.inventory.adjusted`.
6. `order-service` confirms or cancels the order and publishes `polaris.orders.created`.
7. `notification-service` consumes order and inventory events and emits notification logs.

## Production Conventions

- Each service owns its database and schema migrations.
- REST is used for external traffic through the gateway.
- gRPC is used for synchronous internal service contracts.
- Kafka carries durable domain events and supports eventual consistency.
- Services expose health, readiness, metrics, tracing, and structured logs.
- Integration tests use Testcontainers, not shared developer infrastructure.
- Docker Compose is the default local runtime; Helm is the deployment contract.
- ADRs in `docs/adr` document major architectural decisions.

## Roadmap

Before `v1.0.0`, Polaris is intended to move from a clean blueprint skeleton to a runnable, tested, deployable reference system. The project preserves real service boundaries throughout that path, including a dedicated versioned [`proto-contracts`](docs/proto-contracts.md) package so gRPC contracts can be consumed by services without copying `.proto` files.

- `v0.1.0`: repository skeleton, architecture docs, parent build.
- `v0.2.0`: order service with REST, JPA, SQL Liquibase, Kafka publishing, gRPC inventory client, and Testcontainers.
- `v0.3.0`: `proto-contracts` Maven module and inventory service with gRPC server, SQL Liquibase, Kafka consumer/producer, and Testcontainers.
- `v0.4.0`: notification service with Kafka listeners, retry, dead-letter topic, and tests.
- `v0.5.0`: gateway routes, JWT resource server, CORS, request logging, and rate limiting.
- `v0.6.0`: Docker Compose local stack for Postgres, Kafka, services, Prometheus, Grafana, and Jaeger.
- `v0.7.0`: GitHub Actions CI, CodeQL, Spotless, Checkstyle, Lefthook, integration test automation, and README badges.
- `v0.8.0`: migrate manual gRPC server wiring to a Spring gRPC starter, then add gRPC interceptors, health/reflection support, dashboards, tracing conventions, and structured logging.
- `v0.9.0`: Helm chart, Kubernetes manifests, deployment docs, ADR set, and final README polish.
- `v1.0.0`: stable portfolio-ready blueprint with local runtime, CI proof, and deployment artifacts.

## License

Polaris is licensed under the Apache License, Version 2.0. See `LICENSE` for details.
