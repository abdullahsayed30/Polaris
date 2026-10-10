# Required service cannot be scraped

Alert: `PolarisServiceScrapeUnavailable`. Owner: the alerted service owner, with platform support for networking and monitoring. The checked-in `*-owner` labels are responsibility placeholders, not an established on-call roster.

## Meaning and diagnosis

All configured targets for the service have failed to scrape, or the required job has disappeared, for two minutes. One failed replica with another scrapeable replica does not trigger this alert. This is monitoring reachability, not proof of customer unavailability or ready Kubernetes replicas.

1. Open the alert's Polaris Overview dashboard and Prometheus **Status → Targets**. Capture target labels, last successful scrape, scrape error and timestamps.
2. Check the service's actuator listener, process/container health, recent logs and rollout events. Separate DNS, firewall, actuator security and scrape-configuration errors from application failure.
3. Confirm customer impact using the authenticated order journey and existing traces/logs. A successful metrics request does not prove an order can be created.

## Mitigation and resolution

Restore the service or its scrape path according to the diagnosed cause. Use a compatible rollback only after checking migration compatibility. Preserve pending orders, reservation identities, inbox and outbox rows; never delete business state to clear an alert.

Resolution requires restored scrapes, an inactive alert and a separately verified affected customer workflow. Record duration, cause and any remaining failures. A disappeared alert while Prometheus is stopped is not recovery.

## Local demonstration

Use the isolated alert demo described in [operations](../operations.md). Only interrupt a disposable fixture created by that script; never stop the shared development stack or another task's containers. Local dashboard links assume port 3000; deployments must replace them with the real Grafana URL.
