# Polaris Documentation

This folder documents the architecture, service boundaries, and significant decisions behind Polaris. Keep these docs concise, production-oriented, and aligned with the implementation.

## Index

| Document | Purpose |
| --- | --- |
| [Architecture](architecture.md) | System overview, communication patterns, data ownership, and major conventions |
| [Services](services/README.md) | Runtime service documentation for gateway, order, inventory, and notification services |
| [Service Architecture Standard](service-architecture-standard.md) | Package structure and coding rules for service modules |
| [Proto Contracts](proto-contracts.md) | Protobuf ownership, generated stubs, and contract versioning strategy |
| [Shared Module](shared.md) | Shared Java event payloads and module boundary rules |
| [Observability](observability.md) | Metrics, tracing, logs, request correlation, and dashboards |
| [CI/CD](ci-cd.md) | GitHub Actions, quality gates, security scanning, and deferred deployment automation |
| [Architecture Decision Records](adr/README.md) | Accepted architecture decisions and tradeoffs |

## Structure

```text
docs/
|-- adr/
|   |-- README.md
|   `-- 0001-*.md
|-- services/
|   |-- README.md
|   |-- gateway.md
|   |-- order-service.md
|   |-- inventory-service.md
|   `-- notification-service.md
|-- architecture.md
|-- gateway.md
|-- observability.md
|-- proto-contracts.md
|-- shared.md
|-- ci-cd.md
`-- service-architecture-standard.md
```

Service-specific documentation belongs in `docs/services`. Cross-cutting decisions belong in `docs/adr`. Implementation standards that apply to multiple modules belong at the top level.

`gateway.md` remains as a compatibility pointer to `docs/services/gateway.md`.
