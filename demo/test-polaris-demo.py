#!/usr/bin/env python3
"""Exercise demo assertions over local HTTP; this is not service runtime proof."""

import copy
import json
import os
from pathlib import Path
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

SCRIPT = Path(__file__).with_name("polaris-demo.sh")
OWNER = "11111111-1111-4111-8111-111111111111"
SENSITIVE = "do-not-print-token-or-provider-diagnostics"
ORDER = {
    "id": "33333333-3333-4333-8333-333333333333",
    "customerId": OWNER,
    "status": "CONFIRMED",
    "items": [
        {"id": "44444444-4444-4444-8444-444444444444",
         "sku": "SKU-COFFEE-001", "quantity": 2, "unitPrice": 19.99},
        {"id": "55555555-5555-4555-8555-555555555555",
         "sku": "SKU-MUG-002", "quantity": 1, "unitPrice": 8.50},
    ],
}


class DemoAssertionsTest(unittest.TestCase):
    def run_demo(self, scenario):
        calls = []
        failures = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def reply(self, status, body, header=None):
                encoded = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                if header is not None:
                    # Lower-case field name exercises case-insensitive header handling.
                    self.send_header("idempotency-replayed", header)
                self.end_headers()
                self.wfile.write(encoded)

            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                if self.path.endswith("/protocol/openid-connect/token"):
                    username = parse_qs(body.decode())["username"][0]
                    if scenario == "authentication-fails":
                        self.reply(401, {"error": SENSITIVE})
                    elif scenario == "token-missing":
                        self.reply(200, {"unexpected": SENSITIVE})
                    else:
                        self.reply(200, {"access_token": username + "-" + SENSITIVE})
                    return
                payload = json.loads(body)
                if self.path != "/api/v1/orders" or "customerId" in payload:
                    failures.append("Unexpected order path or client-supplied owner")
                if self.headers.get("Authorization") != "Bearer alice-" + SENSITIVE:
                    failures.append("Wrong create/replay identity")
                key = self.headers.get("Idempotency-Key")
                if not key or (calls and (key, payload) != calls[0]):
                    failures.append("Replay did not reuse key and request")
                calls.append((key, payload))
                response = copy.deepcopy(ORDER)
                replay = len(calls) > 1
                if replay and scenario == "replay-id-changes":
                    response["id"] = "66666666-6666-4666-8666-666666666666"
                if scenario == "wrong-owner":
                    response["customerId"] = "22222222-2222-4222-8222-222222222222"
                if scenario == "wrong-created-items":
                    response["items"][0]["quantity"] = 99
                header = "true" if replay else "false"
                if replay and scenario == "replay-header-missing":
                    header = None
                if replay and scenario == "replay-header-false":
                    header = "false"
                self.reply(201, response, header)

            def do_GET(self):
                if self.path != "/api/v1/orders/" + ORDER["id"]:
                    failures.append("Read used another order ID")
                token = self.headers.get("Authorization")
                if token == "Bearer bob-" + SENSITIVE:
                    self.reply(200 if scenario == "isolation-fails" else 404,
                               {"private": SENSITIVE})
                    return
                if token != "Bearer alice-" + SENSITIVE:
                    failures.append("Wrong read identity")
                response = copy.deepcopy(ORDER)
                response["items"].reverse()
                if scenario == "readback-changes":
                    response["items"][0]["unitPrice"] = 99
                self.reply(200, response)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = "http://127.0.0.1:" + str(server.server_port)
        environment = dict(os.environ, POLARIS_GATEWAY_URL=url, POLARIS_IDENTITY_URL=url,
                           POLARIS_REALM="polaris", POLARIS_CLIENT_ID="polaris-cli")
        try:
            result = subprocess.run(["bash", str(SCRIPT)], env=environment,
                                    text=True, capture_output=True, timeout=20)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
        self.assertEqual(failures, [])
        output = result.stdout + result.stderr
        self.assertNotIn(SENSITIVE, output)
        self.assertNotIn("alice-demo", output)
        self.assertNotIn("bob-demo", output)
        return result, calls

    def test_success_includes_replay_and_readback_with_reordered_items(self):
        result, calls = self.run_demo("success")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertIn("Demo passed:", result.stdout)
        self.assertIn("Notifications are simulated.", result.stdout)

    def test_contract_failures_exit_nonzero_without_sensitive_response_bodies(self):
        for scenario, diagnostic in [
            ("authentication-fails", "expected HTTP 200, received 401"),
            ("token-missing", "missing or invalid access token"),
            ("wrong-owner", "authenticated owner or items"),
            ("wrong-created-items", "authenticated owner or items"),
            ("replay-id-changes", "order ID changed"),
            ("replay-header-missing", "Idempotency-Replayed: true"),
            ("replay-header-false", "Idempotency-Replayed: true"),
            ("readback-changes", "Order readback:"),
            ("isolation-fails", "expected HTTP 404, received 200"),
        ]:
            with self.subTest(scenario=scenario):
                result, _calls = self.run_demo(scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(diagnostic, result.stderr)
                self.assertNotIn("Demo passed:", result.stdout)


if __name__ == "__main__":
    unittest.main()
