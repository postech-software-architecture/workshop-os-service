"""Run the existing OS demo profile once and restore the deployment configuration."""
import argparse
import base64
import copy
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time

NAMESPACE = 'workshop'
DEPLOYMENT = 'os-service'
CONTAINER = 'app'
DEMO_SECRET = 'fase4-demo-credentials'
FIELDS = ('SPRING_PROFILES_ACTIVE', 'DEMO_PASSWORD')
INSTALLED = [
    {'name': 'SPRING_PROFILES_ACTIVE', 'value': 'demo'},
    {'name': 'DEMO_PASSWORD', 'valueFrom': {'secretKeyRef': {'name': DEMO_SECRET, 'key': 'DEMO_PASSWORD'}}},
]


def kubectl(arguments):
    result = subprocess.run(['kubectl', '-n', NAMESPACE, *arguments], capture_output=True,
                            text=True, encoding='utf-8', timeout=360)
    if result.returncode:
        reason = next((name for name in ('Conflict', 'Forbidden', 'NotFound', 'ProgressDeadlineExceeded')
                       if name in result.stderr), 'CommandFailed')
        raise RuntimeError('Kubernetes ' + arguments[0] + ' failed: ' + reason + '; private output suppressed.')
    return result.stdout


def app(deployment):
    containers = deployment['spec']['template']['spec']['containers']
    matches = [container for container in containers if container['name'] == CONTAINER]
    if len(matches) != 1:
        raise ValueError('Expected exactly one app container in os-service.')
    return matches[0]


def selected_environment(deployment):
    return {entry['name']: copy.deepcopy(entry) for entry in app(deployment).get('env', [])
            if entry['name'] in FIELDS}


def patch(deployment, entries):
    # Resource version prevents silently overwriting concurrent manual edits.
    return {'metadata': {'resourceVersion': deployment['metadata']['resourceVersion']},
            'spec': {'template': {'spec': {'containers': [{'name': CONTAINER, 'env': entries}]}}}}


def restoration(deployment, original):
    current = selected_environment(deployment)
    installed = {entry['name']: entry for entry in INSTALLED}
    if current == original:
        return None
    # Kubernetes defaults EnvVar.value to an empty string even when valueFrom is
    # set. Compare that semantic default, without accepting a different source.
    for entry in current.values():
        if entry.get('value') == '' and entry.get('valueFrom'):
            entry.pop('value')
        reference = entry.get('valueFrom', {}).get('secretKeyRef', {})
        if reference.get('optional') is False:
            reference.pop('optional')
    if current != installed:
        raise ValueError('Demo environment changed concurrently; refusing to overwrite another configuration.')
    entries = [original.get(name, {'name': name, '$patch': 'delete'}) for name in FIELDS]
    return patch(deployment, entries)


def restore_environment(directory, original):
    for attempt in range(3):
        current = json.loads(kubectl(['get', 'deployment', DEPLOYMENT, '-o', 'json']))
        restore = restoration(current, original)
        if not restore:
            break
        try:
            apply_patch(directory, 'restore-' + str(attempt) + '.json', restore)
            break
        except RuntimeError as error:
            if 'Conflict' not in str(error) or attempt == 2:
                raise
            time.sleep(1)
    kubectl(['rollout', 'status', 'deployment/' + DEPLOYMENT, '--timeout=240s'])
    print('Original OS deployment environment restored and rollout verified.')


def restore_only():
    # Recovery for the first installation, whose versioned deployment has neither
    # of these explicit fields. Refuse to remove any non-demo configuration.
    with tempfile.TemporaryDirectory() as temporary:
        restore_environment(Path(temporary), {})


def private_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as handle:
        json.dump(value, handle)


def ensure_demo_secret(directory):
    existing = kubectl(['get', 'secret', DEMO_SECRET, '--ignore-not-found', '-o', 'json']).strip()
    if existing:
        data = json.loads(existing).get('data', {})
        if len(base64.b64decode(data.get('DEMO_PASSWORD', ''), validate=True)) < 16:
            raise ValueError('Existing demo credential is missing/invalid; it was not rotated.')
        print('Existing demo credential preserved.')
        return
    value = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': DEMO_SECRET, 'namespace': NAMESPACE},
             'type': 'Opaque', 'stringData': {'DEMO_PASSWORD': secrets.token_urlsafe(36)}}
    path = directory / 'demo-secret.json'
    private_json(path, value)
    try:
        # Create, never apply: a concurrent secret cannot be overwritten.
        kubectl(['create', '-f', str(path)])
    finally:
        path.unlink(missing_ok=True)
    print('Demo credential generated in a dedicated cluster Secret; no value printed.')


def apply_patch(directory, name, value):
    path = directory / name
    private_json(path, value)
    try:
        kubectl(['patch', 'deployment', DEPLOYMENT, '--type=strategic', '--patch-file', str(path)])
    finally:
        path.unlink(missing_ok=True)


def run():
    original_deployment = json.loads(kubectl(['get', 'deployment', DEPLOYMENT, '-o', 'json']))
    if original_deployment['metadata']['name'] != DEPLOYMENT:
        raise ValueError('Unexpected deployment.')
    original = selected_environment(original_deployment)
    if original.get('SPRING_PROFILES_ACTIVE', {}).get('valueFrom'):
        raise ValueError('Profile is configured indirectly; review it before temporarily enabling demo.')
    # This workflow is a one-time seed operation, not a demo password rotation.
    if 'DEMO_PASSWORD' in original:
        raise ValueError('Demo password already configured on the deployment; preserve and inspect its existing seed state.')
    with tempfile.TemporaryDirectory() as temporary:
        directory = Path(temporary)
        ensure_demo_secret(directory)
        try:
            # Mark the operation for restoration even if a response is lost after
            # the API applied the patch; finally re-reads the current object.
            apply_patch(directory, 'enable-demo.json', patch(original_deployment, INSTALLED))
            kubectl(['rollout', 'status', 'deployment/' + DEPLOYMENT, '--timeout=240s'])
            print('OS demo profile rollout completed; existing DemoData seeds were executed.')
        finally:
            restore_environment(directory, original)
    print('Demo seeds available. No existing passwords, financial records or application credentials were rotated.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--operation', choices=('seed', 'restore'), default='seed')
    args = parser.parse_args()
    try:
        restore_only() if args.operation == 'restore' else run()
    except (ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, KeyError, subprocess.SubprocessError):
        print('Demo seed operation failed; private values suppressed. Check rollout and restoration status.', file=sys.stderr)
        sys.exit(1)
