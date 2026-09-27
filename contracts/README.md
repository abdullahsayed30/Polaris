# Polaris Contracts

This directory is the reviewable source of truth for externally visible HTTP and Kafka wire contracts. The protobuf source remains in `proto-contracts`; its checked-in compatibility baseline lives here so the generated descriptor can be tested for breaking changes.

## Contract Files

- `openapi/order-api-v1.yaml` documents the gateway-facing order API, bearer authentication, validation constraints, success responses, and stable error shapes.
- `asyncapi/polaris-events-v1.yaml` maps the three Kafka topics to version 1 JSON payload schemas.
- `events/v1/*.schema.json` contains the canonical event payload schemas referenced by AsyncAPI.
- `protobuf/polaris.inventory.v1.json` records the protobuf symbols that must remain wire-compatible within package `polaris.inventory.v1`.

Additive changes may extend a version 1 schema while retaining every existing required wire member and protobuf field number, provided consumers can still read existing messages. A newly required producer field alone does not make old retained messages compatible. Breaking HTTP or event changes require a new versioned contract or an explicit, tested compatibility migration. Breaking protobuf changes require a new protobuf package, such as `polaris.inventory.v2`.

## Legacy Event Compatibility

The schemas describe current producer output, which includes required `metadata`. Before outbox adoption, order/inventory events did not have this member. Notification retains an explicit compatibility decoder for these older wire shapes: missing metadata is enriched using a deterministic UUID derived from Kafka topic/partition/offset, the original `createdAt` or `adjustedAt`, version 1, and the Kafka key as optional correlation. Explicit null, invalid, or unsupported metadata is rejected and follows the poison-event/DLQ path; it is never silently repaired.

Deploy tolerant notification consumers before metadata-emitting producers. Keep the compatibility decoder while old records can be retained, restored, or replayed; do not purge topics or reset offsets to conceal incompatibility. Duplicate reads of the same legacy offset share an identity. Copying a metadata-less event to another offset/topic is a new identity: migrations requiring logical deduplication must assign and preserve explicit event IDs. Current metadata-bearing events retain their producer identity through retries and replays.

## Verification

`ServiceArchitectureTest` also runs during `test`, enforcing key application/domain, messaging/persistence, configuration-record, and shared-contract package rules. Its negative self-tests demonstrate that the guard catches representative violations.

Run fast contract and validation checks without Docker:

```shell
./mvnw -pl contract-tests -am test
```

Run the complete system flow when Docker is available:

```shell
./mvnw -pl contract-tests -am verify
```

`ContractDriftTest` compares the specifications with the Java records, controller annotations, configured topic names, and generated protobuf descriptors. `OrderApiHttpContractTest` executes validation and Problem Details behavior. `OrderLifecycleSystemIntegrationTest` starts the real service applications against Testcontainers PostgreSQL and Kafka, sends an authenticated request through the gateway, and verifies order persistence, inventory reservation, both Kafka events, and notification consumption. The system test is skipped when no Docker engine is available.

The system harness combines service dependencies only on its test classpath. Its per-context configuration disables unrelated gateway/JDBC/gRPC auto-configuration and explicitly uses Netty for the gateway, matching the separate runtime applications. These overrides belong in the harness, never in production configuration or weakened module-boundary rules. Per-service integration tests separately exercise each service's Liquibase startup and persistence behavior.
