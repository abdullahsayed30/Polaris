#!/usr/bin/env bash

set -euo pipefail

gateway_url="${POLARIS_GATEWAY_URL:-http://localhost:8080}"
identity_url="${POLARIS_IDENTITY_URL:-http://localhost:8089}"
realm="${POLARIS_REALM:-polaris}"
client_id="${POLARIS_CLIENT_ID:-polaris-cli}"
order_payload='{"items":[{"sku":"SKU-COFFEE-001","quantity":2,"unitPrice":19.99},{"sku":"SKU-MUG-002","quantity":1,"unitPrice":8.50}]}'

for command_name in curl jq awk mktemp; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Required command not found: ${command_name}" >&2
    exit 1
  fi
done

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

fail() {
  echo "Demo failed: $1" >&2
  echo "See demo/README.md troubleshooting; response bodies and tokens are omitted." >&2
  exit 1
}

request() {
  local stage="$1" expected="$2" method="$3" url="$4" actual
  shift 4
  if ! actual="$(curl --silent --show-error --connect-timeout 5 --max-time 30 \
    --request "${method}" --output "${work_dir}/response.json" \
    --dump-header "${work_dir}/response.headers" --write-out '%{http_code}' \
    "$@" "${url}")"; then
    fail "${stage}: connection failed or timed out."
  fi
  [[ "${actual}" == "${expected}" ]] || fail "${stage}: expected HTTP ${expected}, received ${actual}."
}

token_for() {
  request "Local authentication" 200 POST \
    "${identity_url}/realms/${realm}/protocol/openid-connect/token" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=${client_id}" \
    --data-urlencode "username=$1" \
    --data-urlencode "password=$2"
  jq --exit-status --raw-output '.access_token | select(type == "string" and length > 0)' \
    "${work_dir}/response.json" 2>/dev/null || fail "Local authentication: missing or invalid access token."
}

place_order() {
  request "$1" 201 POST "${gateway_url}/api/v1/orders" \
    --header "Authorization: Bearer ${alice_token}" \
    --header "Content-Type: application/json" \
    --header "Idempotency-Key: polaris-local-demo" \
    --data "${order_payload}"
}

echo "Obtaining local demo tokens for Alice and Bob..."
alice_token="$(token_for alice alice-demo)"
bob_token="$(token_for bob bob-demo)"

echo "Placing Alice's order through the gateway..."
place_order "Order creation"
cp "${work_dir}/response.json" "${work_dir}/order.json"
order_id="$(jq --exit-status --raw-output '.id | select(type == "string" and length > 0)' \
  "${work_dir}/order.json" 2>/dev/null)" || fail "Order creation: missing order ID."
jq --exit-status --argjson expected "${order_payload}" '
  .status == "CONFIRMED" and
  .customerId == "11111111-1111-4111-8111-111111111111" and
  ((.items | map({sku, quantity, unitPrice}) | sort_by(.sku)) == ($expected.items | sort_by(.sku)))
' "${work_dir}/order.json" >/dev/null 2>&1 || fail "Order creation: status, authenticated owner or items differ from expectations."

echo "Replaying the same idempotent request..."
place_order "Order replay"
jq --exit-status --arg id "${order_id}" '.id == $id' \
  "${work_dir}/response.json" >/dev/null 2>&1 || fail "Order replay: order ID changed."
awk 'tolower($1) == "idempotency-replayed:" {gsub(/\r/, "", $2); value = tolower($2)}
  END {exit(value != "true")}' "${work_dir}/response.headers" \
  || fail "Order replay: missing Idempotency-Replayed: true header."

echo "Reading Alice's order as Alice..."
request "Order readback" 200 GET "${gateway_url}/api/v1/orders/${order_id}" \
  --header "Authorization: Bearer ${alice_token}"
jq --exit-status --slurpfile original "${work_dir}/order.json" '
  def business_state: {id, customerId, status, items: (.items | sort_by(.id))};
  business_state == ($original[0] | business_state)
' "${work_dir}/response.json" >/dev/null 2>&1 || fail "Order readback: identity, owner, status or items changed."

echo "Checking Bob cannot read Alice's order..."
request "Cross-customer isolation" 404 GET "${gateway_url}/api/v1/orders/${order_id}" \
  --header "Authorization: Bearer ${bob_token}"

echo
echo "Demo passed: order ${order_id} is CONFIRMED; replay kept its ID and header; readback matches; Bob receives 404."
echo "Notifications are simulated. This checks the HTTP journey; database/Kafka guarantees need integration tests."
echo "Observability:"
echo "  Gateway health: ${gateway_url}/actuator/health"
echo "  Gateway metrics: ${gateway_url}/actuator/prometheus"
echo "  Prometheus:     http://localhost:9090"
echo "  Grafana:        http://localhost:3000"
echo "  Tempo:          http://localhost:3200/ready"
