#!/usr/bin/env python3
"""Check that promtool exercises the exact provisioned freshness expressions."""

import json
from pathlib import Path


tests = Path(__file__).resolve().parent
dashboard = tests.parents[1] / "grafana/dashboards/polaris-business-flow.json"
panels = json.loads(dashboard.read_text())["panels"]
expected = {
    panel["targets"][0]["expr"]
    for panel in panels
    if panel["id"] in (7, 14)
}
expressions = [
    json.loads(line.strip().removeprefix("- expr: "))
    for line in (tests / "notification-freshness.test.yml").read_text().splitlines()
    if line.strip().startswith("- expr: ")
]
if len(expected) != 2 or set(expressions) != expected:
    raise SystemExit("Freshness promtool cases have drifted from the dashboard")
print(f"Exact dashboard query parity: {len(expressions)} assertions")
