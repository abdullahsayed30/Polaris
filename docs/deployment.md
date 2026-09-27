# Kubernetes deployment

Polaris ships a Helm chart for the application layer under [`deploy/helm/polaris`](../deploy/helm/polaris). The chart is an environment-neutral deployment contract: it deploys the gateway and three application services while leaving stateful and security-sensitive infrastructure under platform ownership.

## Responsibility boundary

| Chart owns | Platform/operator owns |
| --- | --- |
| Four application Deployments and Services | PostgreSQL for order, inventory, and notification inbox data |
| ConfigMaps and references to existing Secrets | Kafka brokers and topic provisioning/policy |
| Startup, readiness, and liveness probes | Redis for distributed gateway rate limiting |
| Resource requests/limits and pod security contexts | OIDC issuer and JWKS availability |
| PodDisruptionBudgets and optional HPAs | OpenTelemetry/metrics backends and retention |
| NetworkPolicies and optional gateway Ingress | DNS, certificates, backups, upgrades, and disaster recovery |

The chart deliberately does not install PostgreSQL or Kafka. A demo-grade StatefulSet would imply backup, replication, upgrade, encryption, and recovery guarantees that this repository does not provide.

## Prerequisites

- Kubernetes 1.27 or newer.
- Helm 3.
- Four images built from the repository's Dockerfiles using the `production` target and pushed to a registry the cluster can pull from.
- Reachable PostgreSQL databases for `order-service`, `inventory-service`, and `notification-service`.
- Reachable Kafka, Redis, and OIDC/JWKS endpoints.
- Three existing Kubernetes Secrets containing database credentials.
- Metrics Server only when an HPA is enabled.

No image publishing workflow is included in this repository. Build and push images explicitly, for example:

```bash
docker build --target production -f gateway/Dockerfile -t REGISTRY/polaris/gateway:0.8.0 .
docker build --target production -f order-service/Dockerfile -t REGISTRY/polaris/order-service:0.8.0 .
docker build --target production -f inventory-service/Dockerfile -t REGISTRY/polaris/inventory-service:0.8.0 .
docker build --target production -f notification-service/Dockerfile -t REGISTRY/polaris/notification-service:0.8.0 .
```

## Configure an environment

Copy [`examples/production-values.yaml`](../deploy/helm/polaris/examples/production-values.yaml) outside the chart or create a separate environment values file. Replace every `example.invalid` value and image repository.

Create database credentials before installing. Prefer an external secret controller or sealed-secret workflow in a real environment. This imperative example is suitable only for an isolated test namespace and may leave values in shell history:

```bash
kubectl create namespace polaris
kubectl -n polaris create secret generic polaris-order-database \
  --from-literal=username='REPLACE_ME' \
  --from-literal=password='REPLACE_ME'
kubectl -n polaris create secret generic polaris-inventory-database \
  --from-literal=username='REPLACE_ME' \
  --from-literal=password='REPLACE_ME'
kubectl -n polaris create secret generic polaris-notification-database \
  --from-literal=username='REPLACE_ME' \
  --from-literal=password='REPLACE_ME'
```

The chart maps those keys to Spring's datasource variables. Extra secret-backed settings, such as Redis or Kafka credentials, belong in the relevant service's `secretEnv` list. Non-secret Spring overrides belong in `extraEnv`.

If the optional Ingress enables TLS, its referenced certificate Secret must also exist in the release namespace (or be created by the cluster's certificate controller).

## Validate before installation

Run the repository helper:

```bash
./deploy/scripts/validate-helm.sh
```

It runs:

```bash
helm lint --strict deploy/helm/polaris
helm lint --strict deploy/helm/polaris \
  --values deploy/helm/polaris/examples/production-values.yaml
helm template polaris-validation deploy/helm/polaris --namespace polaris-validation
helm template polaris-example deploy/helm/polaris \
  --namespace polaris-example \
  --values deploy/helm/polaris/examples/production-values.yaml
```

When `kubeconform` is available, the helper performs strict offline Kubernetes 1.27 schema validation on both renders. Otherwise it attempts `kubectl apply --dry-run=client`; that fallback still needs API discovery from a reachable cluster and reports when discovery is unavailable. The helper is intentionally separate from the main CI workflow; it can be adopted by a release pipeline when image and chart publishing are introduced.

Before promotion, exercise the target cluster's API versions, admission policies, and webhooks with a server-side dry-run:

```bash
helm template polaris deploy/helm/polaris \
  --namespace polaris \
  --values /path/to/polaris-production.yaml \
  | kubectl apply --dry-run=server -f -
```

Render the environment-specific values as a review artifact before applying:

```bash
helm template polaris deploy/helm/polaris \
  --namespace polaris \
  --values /path/to/polaris-production.yaml \
  > /tmp/polaris-rendered.yaml
```

## Install and verify

```bash
helm upgrade --install polaris deploy/helm/polaris \
  --namespace polaris \
  --create-namespace \
  --values /path/to/polaris-production.yaml \
  --atomic \
  --timeout 10m

kubectl -n polaris get deploy,pod,svc,pdb,hpa,networkpolicy
kubectl -n polaris rollout status deploy/polaris-gateway --timeout=5m
kubectl -n polaris rollout status deploy/polaris-order-service --timeout=5m
kubectl -n polaris rollout status deploy/polaris-inventory-service --timeout=5m
kubectl -n polaris rollout status deploy/polaris-notification-service --timeout=5m
```

The `notification-service` application defaults to a non-web process in source configuration. The chart sets `SPRING_MAIN_WEB_APPLICATION_TYPE=servlet` so its existing actuator dependency exposes HTTP health and metrics endpoints for Kubernetes.

## Health and rollout behavior

- Startup probes allow database migrations and client initialization to complete before liveness enforcement begins.
- Readiness removes a pod from Service endpoints while it is unable to serve traffic.
- Liveness is an application-process signal and should not be expanded to restart pods for a shared database or broker outage.
- PDBs assume the default two replicas. Revisit disruption budgets when running a single replica or changing topology.
- HPAs are disabled by default. Their CPU and memory targets are starting values, not measured capacity limits.

Liquibase runs with application startup for the order, inventory, and notification schemas. Test forward and rollback compatibility before deployment; a Helm rollback does not reverse a database migration.

### Event and recovery rollout compatibility

Deploy the notification consumer that accepts both metadata-bearing and legacy metadata-less events before upgrading outbox producers. Retained legacy messages remain supported; do not purge topics or reset consumer offsets as a substitute for compatibility. Legacy deduplication is tied to original topic/partition/offset; preserve explicit event IDs when copying records to new locations. See [contract compatibility](../contracts/README.md).

Order migrations add a pending-reservation retry index and permit idempotency requests to bind to pending orders. Deploy the recovery-capable order service with these migrations and monitor pending orders and `polaris.reservation.recovery` outcomes. Do not roll back to code that assumes every bound idempotency request is complete without first checking pending state. Do not remove the retry column or restore the older request constraint while pending intents remain. A failed HTTP response is not evidence that inventory rolled back; investigate/recover using the original order identity, never blindly release stock.

## Network policy

Ingress is restricted by workload role. The default monitoring source is a namespace named `monitoring`. Gateway ingress permits all namespaces so the chart does not guess the labels used by an ingress controller; tighten `networkPolicy.gatewayIngressFrom` for each cluster.

Egress defaults to open because standard Kubernetes NetworkPolicy is IP/CIDR based and cannot safely track managed-service FQDNs. Where stable network ranges exist, set `networkPolicy.egress.allowAll=false` and supply rules under `networkPolicy.egress.additionalRules` for PostgreSQL, Kafka, Redis, OIDC, and OTLP. The chart retains DNS and application-internal flows in restricted mode.

## Rollback and removal

```bash
helm history polaris -n polaris
helm rollback polaris REVISION -n polaris --wait --timeout 10m
```

Rollback the application only after checking schema compatibility and event compatibility. `helm uninstall` removes chart-owned Kubernetes objects; it does not remove external databases, topics, secrets created outside the chart, or observability data.

Operational response and suggested service objectives are in [Operations](operations.md).
