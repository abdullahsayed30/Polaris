# Proto Contracts

Polaris keeps protobuf definitions in a dedicated Maven module named `proto-contracts`. Service modules consume generated Java and gRPC classes from that artifact instead of copying `.proto` files or generating stubs from another service's source tree.

## Target Model

The module owns protobuf definitions and generated gRPC Java stubs.

```text
proto-contracts/
|-- pom.xml
`-- src/main/proto/
    `-- polaris/
        `-- inventory/
            `-- v1/
                `-- inventory.proto
```

The module should publish a versioned artifact:

```xml
<dependency>
    <groupId>io.polaris</groupId>
    <artifactId>proto-contracts</artifactId>
    <version>0.3.0</version>
</dependency>
```

Services such as `order-service` and `inventory-service` should depend on that artifact instead of copying `.proto` files or reading from another service's source tree. In a future split-repository setup, `proto-contracts` can be published to GitHub Packages, Nexus, Artifactory, or another Maven registry.

## Versioning Strategy

While Polaris remains a single Maven multi-module repository, `proto-contracts` can inherit the parent project version for simple local development and release tagging. For example, the `v0.3.0` implementation can publish `io.polaris:proto-contracts:0.3.0` from the same reactor build as the services.

The artifact is still designed to become independently versioned later. If services are split into separate repositories, `proto-contracts` should be released as its own API package and consumed with pinned versions by each service. Contract versions should then follow semantic versioning:

- Patch versions for build metadata, comments, generated-code fixes, or non-contract changes.
- Minor versions for backward-compatible additions such as new fields, messages, or RPCs.
- Major versions for breaking changes, usually with a new protobuf package such as `polaris.inventory.v2`.

Service repositories should upgrade `proto-contracts` deliberately rather than automatically floating to the newest version.

## Rules

- `proto-contracts` owns protobuf files and generated gRPC Java stubs.
- Service modules consume the versioned Maven artifact.
- Field numbers must never be reused.
- Compatible additions should add new optional fields or new RPCs.
- Breaking changes require a new package version such as `polaris.inventory.v2`.
- Event payloads and RPC contracts should be reviewed like public APIs.
- `shared` must not become a replacement for `proto-contracts`; keep common Java helpers separate from wire contracts.
