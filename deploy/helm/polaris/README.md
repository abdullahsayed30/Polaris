# Polaris Helm chart

This chart deploys the four Polaris application workloads: `gateway`, `order-service`, `inventory-service`, and `notification-service`. It intentionally does **not** install PostgreSQL, Kafka, Redis, an identity provider, or an observability backend. Platform operators provide those endpoints and their lifecycle separately.

The chart defaults are a renderable contract, not a runnable environment. Values under `example.invalid` and the three referenced database Secrets must be replaced before installation.

## Included resources

- One Deployment, ClusterIP Service, ConfigMap, PodDisruptionBudget, and NetworkPolicy per enabled application service.
- A least-privilege ServiceAccount with API token automount disabled.
- HTTP startup, readiness, and liveness probes against Spring Boot actuator health groups.
- CPU and memory requests/limits, non-root/read-only security contexts, and a bounded writable `/tmp` volume.
- Optional `autoscaling/v2` HPAs and an optional gateway Ingress.

## Render and validate

From the repository root:

```bash
./deploy/scripts/validate-helm.sh
```

The script runs `helm lint --strict` and `helm template` for both the default values and the production example. It then uses `kubeconform` when installed, otherwise it attempts a `kubectl` client dry-run (which may still require cluster API discovery). See [`docs/deployment.md`](../../../docs/deployment.md) for installation prerequisites and configuration.

## Secret contract

The chart references existing Secrets and never renders secret values. By default it expects:

| Secret | Keys | Consumer |
| --- | --- | --- |
| `polaris-order-database` | `username`, `password` | `order-service` |
| `polaris-inventory-database` | `username`, `password` | `inventory-service` |
| `polaris-notification-database` | `username`, `password` | `notification-service` |

Additional secret-backed Spring properties can be mapped with each service's `secretEnv` list. For example:

```yaml
services:
  gateway:
    secretEnv:
      - name: SPRING_DATA_REDIS_PASSWORD
        secretName: polaris-redis
        secretKey: password
```

## Network policy

Ingress is role-scoped: gateway to order HTTP, order to inventory gRPC, and a configurable monitoring source to actuator ports. Gateway ingress defaults to all namespaces so the chart does not assume a specific ingress controller.

Egress defaults to open because Kubernetes NetworkPolicy cannot portably allow managed services by DNS name. To enforce egress, set `networkPolicy.egress.allowAll=false` and add `additionalRules` containing the stable infrastructure CIDRs and ports. The chart retains DNS plus required in-release service flows in restricted mode.
