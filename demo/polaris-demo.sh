#!/usr/bin/env bash

set -euo pipefail

gateway_url="${POLARIS_GATEWAY_URL:-http://localhost:8080}"
identity_url="${POLARIS_IDENTITY_URL:-http://localhost:8089}"
realm="${POLARIS_REALM:-polaris}"
client_id="${POLARIS_CLIENT_ID:-polaris-cli}"

for command_name in curl jq; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Required command not found: ${command_name}" >&2
    exit 1
  fi
done

token_for() {
  local username="$1"
  local password="$2"

  curl --silent --show-error --fail-with-body \
    --request POST \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=${client_id}" \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${password}" \
    "${identity_url}/realms/${realm}/protocol/openid-connect/token" | jq --exit-status --raw-output '.access_token'
}

echo "Obtaining local demo tokens for Alice and Bob..."
alice_token="$(token_for alice alice-demo)"
bob_token="$(token_for bob bob-demo)"

echo "Placing Alice's order through the gateway..."
order_json="$(curl --silent --show-error --fail-with-body \
  --request POST \
  --header "Authorization: Bearer ${alice_token}" \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: polaris-local-demo" \
  --data '{"items":[{"sku":"SKU-COFFEE-001","quantity":2,"unitPrice":19.99},{"sku":"SKU-MUG-002","quantity":1,"unitPrice":8.50}]}' \
  "${gateway_url}/api/v1/orders")"

order_id="$(jq --exit-status --raw-output '.id' <<<"${order_json}")"
order_status="$(jq --exit-status --raw-output '.status' <<<"${order_json}")"
customer_id="$(jq --exit-status --raw-output '.customerId' <<<"${order_json}")"

if [[ "${order_status}" != "CONFIRMED" ]]; then
  echo "Expected a CONFIRMED order, received: ${order_status}" >&2
  jq . <<<"${order_json}" >&2
  exit 1
fi

if [[ "${customer_id}" != "11111111-1111-4111-8111-111111111111" ]]; then
  echo "Order ownership did not come from Alice's authenticated subject." >&2
  exit 1
fi

echo "Reading Alice's order as Alice..."
read_json="$(curl --silent --show-error --fail-with-body \
  --header "Authorization: Bearer ${alice_token}" \
  "${gateway_url}/api/v1/orders/${order_id}")"
jq . <<<"${read_json}"

denied_body="$(mktemp)"
trap 'rm -f "${denied_body}"' EXIT
denied_status="$(curl --silent --show-error \
  --output "${denied_body}" \
  --write-out '%{http_code}' \
  --header "Authorization: Bearer ${bob_token}" \
  "${gateway_url}/api/v1/orders/${order_id}")"

if [[ "${denied_status}" != "404" ]]; then
  echo "Expected Bob's cross-customer read to return 404, received ${denied_status}." >&2
  cat "${denied_body}" >&2
  exit 1
fi

echo
echo "Demo passed: order ${order_id} is CONFIRMED, owned by Alice, and hidden from Bob."
echo "Observability:"
echo "  Gateway health: ${gateway_url}/actuator/health"
echo "  Gateway metrics: ${gateway_url}/actuator/prometheus"
echo "  Prometheus:     http://localhost:9090"
echo "  Grafana:        http://localhost:3000 (admin/admin)"
echo "  Tempo:          http://localhost:3200/ready"
