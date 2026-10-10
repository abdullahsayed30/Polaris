#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# Match the Compose evaluator. No network, host ports or persistent Docker resources.
prometheus_image=prom/prometheus:v2.55.1
python3 "$repo_root/deploy/prometheus/tests/validate-notification-freshness.py"
docker run --rm --network none --entrypoint /bin/promtool \
  -v "$repo_root/deploy/prometheus:/etc/prometheus:ro" \
  "$prometheus_image" check config /etc/prometheus/prometheus.yml
docker run --rm --network none --entrypoint /bin/promtool \
  -v "$repo_root/deploy/prometheus:/work:ro" -w /work \
  "$prometheus_image" check rules rules/polaris-alerts.yml
docker run --rm --network none --entrypoint /bin/promtool \
  -v "$repo_root/deploy/prometheus:/work:ro" -w /work/rules/tests \
  "$prometheus_image" test rules reachability.test.yml operational.test.yml
docker run --rm --network none --entrypoint /bin/promtool \
  -v "$repo_root/deploy/prometheus:/work:ro" -w /work \
  "$prometheus_image" test rules tests/notification-freshness.test.yml
