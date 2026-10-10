#!/usr/bin/env python3
"""Create, interrupt, recover and remove only a unique disposable alert fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / 'deploy/alert-demo/compose.yml'
SERVICES = ('gateway', 'order-service', 'inventory-service', 'notification-service')


def command(*args, stdin=None):
    return subprocess.run(args, input=stdin, text=True, check=True, capture_output=True).stdout.strip()


def now():
    return datetime.now(timezone.utc).isoformat()


def request(url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def wait_for(description, predicate, timeout=360):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            print(now(), description, flush=True)
            return
        time.sleep(5)
    raise RuntimeError('Timed out: ' + description)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir', type=Path, help='New directory for timestamped evidence; defaults under target/')
    args = parser.parse_args()
    project = 'polaris-alert-demo-' + uuid.uuid4().hex[:12]
    output = args.evidence_dir or ROOT / 'target/alert-demo' / project
    output.mkdir(parents=True, exist_ok=False)
    compose = ['docker', 'compose', '-p', project, '-f', str(COMPOSE)]
    events = []

    def save(name, value):
        (output / name).write_text(json.dumps(value, indent=2) + '\n')

    def event(action):
        events.append(dict(at=now(), action=action))
        save('timeline.json', events)
        print(now(), action, flush=True)

    def dc(*args, stdin=None):
        return command(*compose, *args, stdin=stdin)

    def endpoint(service, port):
        address = dc('port', service, str(port)).splitlines()[0]
        if not address.startswith('127.0.0.1:'):
            raise RuntimeError('Fixture host port must be loopback-only: ' + address)
        return 'http://' + address

    provenance = {'started_at': now(), 'project': project, 'source_commit': command('git', '-C', str(ROOT), 'rev-parse', 'HEAD'), 'jars': {}}
    for service in SERVICES:
        jar = ROOT / service / 'target' / (service + '-exec.jar')
        if not jar.is_file():
            raise RuntimeError('Package the current source first: ' + str(jar))
        # Reject application source edits that would invalidate HEAD provenance.
        if command('git', '-C', str(ROOT), 'status', '--porcelain', '--', service, 'shared', 'proto-contracts', 'pom.xml'):
            raise RuntimeError('Application source differs from HEAD; build provenance needs review')
        provenance['jars'][service] = {'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
    provenance['rules_sha256'] = hashlib.sha256((ROOT / 'deploy/prometheus/rules/polaris-alerts.yml').read_bytes()).hexdigest()
    if command('docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project):
        raise RuntimeError('Generated fixture project already exists')
    save('provenance.json', provenance)

    try:
        event('Creating isolated fixture; normal rule thresholds and durations remain unchanged')
        dc('up', '-d', '--wait', '--wait-timeout', '300')
        prom = endpoint('prometheus', 9090)
        gateway = endpoint('gateway', 8080)
        keycloak = endpoint('keycloak', 8080)
        event('Fixture ready; local alerts UI: ' + prom + '/alerts')

        def api(path):
            status, body = request(prom + path)
            if status != 200:
                raise RuntimeError('Prometheus API failed: ' + str(status))
            result = json.loads(body)
            if result.get('status') != 'success':
                raise RuntimeError('Prometheus query failed')
            return result

        def query(expr):
            return api('/api/v1/query?' + urllib.parse.urlencode({'query': expr}))

        def active(name, state='firing'):
            return any(a['labels']['alertname'] == name and a['state'] == state for a in api('/api/v1/alerts')['data']['alerts'])

        def capture(phase):
            save(phase + '-alerts.json', {'at': now(), 'result': api('/api/v1/alerts')})
            save(phase + '-rules.json', {'at': now(), 'result': api('/api/v1/rules')})
            queries = ['up', 'polaris:business_snapshot_valid', 'polaris_pending_orders', 'polaris_pending_order_oldest_age_seconds', 'polaris_outbox_events', 'polaris_outbox_oldest_age_seconds', 'polaris_notification_inbox_events', 'polaris_notification_completions_total', 'polaris_notification_dlq_publications_total', 'polaris_notification_freshness_seconds_bucket{le="3600.0"}', 'polaris_notification_freshness_overflow_total', 'grpc_client_processing_duration_seconds_count{service="polaris.inventory.v1.InventoryService",method="ReserveStock",methodType="UNARY"}', 'http_server_requests_seconds_count{job="order-service",uri="/api/v1/orders"}', 'spring_cloud_gateway_requests_seconds_count{routeId="order-create"}']
            save(phase + '-queries.json', {'at': now(), 'queries': {expr: query(expr) for expr in queries}})

        def token():
            # Reuse the declared disposable realm fixture; never duplicate account credentials here.
            realm = json.loads((ROOT / 'deploy/keycloak/polaris-realm.json').read_text())
            demo_user = next(user for user in realm['users'] if user['username'] == 'alice')
            credential = next(item for item in demo_user['credentials'] if item['type'] == 'password')
            form = urllib.parse.urlencode({'grant_type': 'password', 'client_id': 'polaris-cli',
                                         'username': demo_user['username'], 'password': credential['value']}).encode()
            status, body = request(keycloak + '/realms/polaris/protocol/openid-connect/token', form, {'Content-Type': 'application/x-www-form-urlencoded'})
            if status != 200:
                raise RuntimeError('Local demo token request failed: ' + str(status))
            return json.loads(body)['access_token']

        payload = json.dumps({'items': [{'sku': 'SKU-COFFEE-001', 'quantity': 1, 'unitPrice': '10.00'}]}).encode()

        def place(key):
            return request(gateway + '/api/v1/orders', payload, {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token(), 'Idempotency-Key': key})

        status, body = place('alert-warm-' + project)
        if status != 201:
            raise RuntimeError('Warm authenticated order failed: ' + str(status) + ' ' + body)
        save('warm-order.json', json.loads(body))
        wait_for('All four scrapes and three complete fresh business snapshots are valid', lambda: len(query('up{job=~"gateway|order-service|inventory-service|notification-service"} == 1')['data']['result']) == 4 and len(query('polaris:business_snapshot_valid')['data']['result']) == 3)
        wait_for('Simulated notification commit observed', lambda: any(float(x['value'][1]) > 0 for x in query('polaris_notification_completions_total{outcome="processed"}')['data']['result']))
        # A scrape can recover before the next rule evaluation clears its startup pending state.
        wait_for('Startup pending states have cleared at the evaluator', lambda: not api('/api/v1/alerts')['data']['alerts'], timeout=90)
        capture('healthy')
        if api('/api/v1/alerts')['data']['alerts']:
            raise RuntimeError('Healthy baseline contains pending or firing alerts')
        # Assert each query family/label used by alert expressions is actually emitted.
        healthy = json.loads((output / 'healthy-queries.json').read_text())
        for expr, value in healthy['queries'].items():
            if not value['data']['result']:
                raise RuntimeError('Required live metric/label signature absent: ' + expr)

        event('Stopping only this fixture inventory service')
        dc('stop', '-t', '5', 'inventory-service')
        pending_key = 'alert-pending-' + project
        status, body = place(pending_key)
        save('failed-create-response.json', {'at': now(), 'status': status, 'body': json.loads(body)})
        if status != 503:
            raise RuntimeError('Expected uncertain/pending create to return 503')

        def sql(statement):
            return dc('exec', '-T', 'order-postgres', 'psql', '-U', 'polaris_order', '-d', 'polaris_orders', '-Atc', statement)

        order_id = sql("SELECT order_id FROM order_requests WHERE idempotency_key='" + pending_key + "'")
        uuid.UUID(order_id)
        save('pending-order.json', {'at': now(), 'order_id': order_id, 'status': sql("SELECT status FROM orders WHERE id='" + order_id + "'")})
        wait_for('Scrape loss is visibly pending', lambda: active('PolarisServiceScrapeUnavailable', 'pending'))
        capture('pending')
        wait_for('Scrape loss and durable pending-order alerts are firing', lambda: active('PolarisServiceScrapeUnavailable') and active('PolarisPendingOrdersStalled'), timeout=420)
        capture('firing')
        event('Restarting only this fixture inventory service; recovery reuses persisted identity')
        dc('start', 'inventory-service')
        wait_for('Original pending order is committed CONFIRMED', lambda: sql("SELECT status FROM orders WHERE id='" + order_id + "'") == 'CONFIRMED', timeout=180)
        wait_for('Both exercised alerts resolve and telemetry is valid', lambda: not active('PolarisServiceScrapeUnavailable') and not active('PolarisServiceScrapeUnavailable', 'pending') and not active('PolarisPendingOrdersStalled') and not active('PolarisPendingOrdersStalled', 'pending') and len(query('polaris:business_snapshot_valid')['data']['result']) == 3)
        status, body = place(pending_key)
        replay = json.loads(body)
        if status != 201 or replay['id'] != order_id or replay['status'] != 'CONFIRMED':
            raise RuntimeError('Idempotent replay did not return the recovered order')
        save('recovered-order.json', {'at': now(), 'response': replay, 'committed_status': sql("SELECT status FROM orders WHERE id='" + order_id + "'")})
        capture('recovered')
        if api('/api/v1/alerts')['data']['alerts']:
            raise RuntimeError('Recovery left unexpected pending/firing alerts')
        event('Live fire/recover proof passed; other alert paths have rule-test coverage only')
        save('result.json', {'at': now(), 'passed': True, 'exercised': ['PolarisServiceScrapeUnavailable', 'PolarisPendingOrdersStalled'], 'prometheus_alerts_url': prom + '/alerts'})
    finally:
        # Unique project generated in this run; never operate on another stack.
        logs = subprocess.run([*compose, 'logs', '--no-color', '--no-log-prefix', *SERVICES], text=True, capture_output=True)
        (output / 'application.log').write_text(logs.stdout)
        event('Removing only the disposable fixture containers, network and volumes')
        dc('down', '--volumes', '--remove-orphans', '--timeout', '5')
        containers = command('docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project)
        networks = command('docker', 'network', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project)
        volumes = command('docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project)
        save('cleanup.json', {'at': now(), 'project': project, 'containers': containers.splitlines(), 'networks': networks.splitlines(), 'volumes': volumes.splitlines()})
        if containers or networks or volumes:
            raise RuntimeError('Disposable fixture cleanup incomplete')
        print('Evidence:', output, flush=True)


if __name__ == '__main__':
    main()
