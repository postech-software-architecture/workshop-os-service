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
        raise RuntimeError('Kubernetes operation failed; command output suppressed to protect private data.')
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
    if current != installed:
        raise ValueError('Demo environment changed concurrently; refusing to overwrite another configuration.')
    entries = [original.get(name, {'name': name, '$patch': 'delete'}) for name in FIELDS]
    return patch(deployment, entries)


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
            current = json.loads(kubectl(['get', 'deployment', DEPLOYMENT, '-o', 'json']))
            restore = restoration(current, original)
            if restore:
                apply_patch(directory, 'restore-normal.json', restore)
            kubectl(['rollout', 'status', 'deployment/' + DEPLOYMENT, '--timeout=240s'])
            print('Original OS deployment environment restored and rollout verified.')
    print('Demo seeds available. No existing passwords, financial records or application credentials were rotated.')


if __name__ == '__main__':
    try:
        run()
    except (ValueError, RuntimeError, OSError, KeyError, subprocess.SubprocessError):
        print('Demo seed operation failed; private values suppressed. Check rollout and restoration status.', file=sys.stderr)
        sys.exit(1)
