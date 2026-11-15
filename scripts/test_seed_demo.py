import base64
from contextlib import redirect_stdout
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('seed', Path(__file__).with_name('seed-demo.py'))
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


def deployment(entries=None, version='7'):
    return {'metadata': {'name': 'os-service', 'resourceVersion': version},
            'spec': {'template': {'spec': {'containers': [{'name': 'app', 'env': entries or []}]}}}}


class SeedTest(unittest.TestCase):
    def test_patch_only_changes_demo_environment_and_uses_resource_version(self):
        value = seed.patch(deployment(), seed.INSTALLED)
        self.assertEqual('7', value['metadata']['resourceVersion'])
        self.assertEqual({'SPRING_PROFILES_ACTIVE', 'DEMO_PASSWORD'},
                         {entry['name'] for entry in value['spec']['template']['spec']['containers'][0]['env']})
        self.assertNotIn('replicas', value['spec'])
        self.assertNotIn('image', value['spec']['template']['spec']['containers'][0])

    def test_restore_deletes_only_temporary_fields_and_preserves_original_profile(self):
        old = {'SPRING_PROFILES_ACTIVE': {'name': 'SPRING_PROFILES_ACTIVE', 'value': 'prod'}}
        value = seed.restoration(deployment(seed.INSTALLED, '9'), old)
        entries = value['spec']['template']['spec']['containers'][0]['env']
        self.assertIn(old['SPRING_PROFILES_ACTIVE'], entries)
        self.assertIn({'name': 'DEMO_PASSWORD', '$patch': 'delete'}, entries)
        self.assertEqual('9', value['metadata']['resourceVersion'])

    def test_concurrent_environment_change_is_not_overwritten(self):
        with self.assertRaises(ValueError):
            seed.restoration(deployment([{'name': 'SPRING_PROFILES_ACTIVE', 'value': 'other'}]), {})
        self.assertIsNone(seed.restoration(deployment(), {}))

    def test_kubernetes_empty_value_and_false_optional_defaults_are_accepted(self):
        entries = copy.deepcopy(seed.INSTALLED)
        entries[1]['value'] = ''
        entries[1]['valueFrom']['secretKeyRef']['optional'] = False
        self.assertIsNotNone(seed.restoration(deployment(entries), {}))
        entries[1]['valueFrom']['secretKeyRef']['optional'] = True
        with self.assertRaises(ValueError):
            seed.restoration(deployment(entries), {})

    def test_restore_retries_resource_version_conflict_without_overwriting_other_fields(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(seed, 'kubectl', return_value=json.dumps(deployment(seed.INSTALLED, '9'))), \
                patch.object(seed, 'apply_patch', side_effect=[RuntimeError('Conflict'), None]) as write, \
                patch.object(seed.time, 'sleep'), redirect_stdout(io.StringIO()):
            seed.restore_environment(Path(temporary), {})
        self.assertEqual(2, write.call_count)

    def test_existing_demo_secret_is_preserved(self):
        value = base64.b64encode(b'private-demo-password-long-enough').decode()
        with tempfile.TemporaryDirectory() as temporary, patch.object(seed, 'kubectl', return_value=json.dumps({'data': {'DEMO_PASSWORD': value}})) as kube, redirect_stdout(io.StringIO()) as output:
            seed.ensure_demo_secret(Path(temporary))
        self.assertEqual(1, kube.call_count)
        self.assertNotIn(value, output.getvalue())

    def test_generated_secret_uses_create_and_private_file_then_removes_it(self):
        seen = []
        def kube(args):
            if args[0] == 'get':
                return ''
            self.assertEqual('create', args[0])
            path = Path(args[-1])
            value = json.loads(path.read_text(encoding='utf-8'))
            self.assertGreaterEqual(len(value['stringData']['DEMO_PASSWORD']), 16)
            seen.append(path)
            return ''
        with tempfile.TemporaryDirectory() as temporary, patch.object(seed, 'kubectl', side_effect=kube), redirect_stdout(io.StringIO()):
            seed.ensure_demo_secret(Path(temporary))
            self.assertTrue(all(not path.exists() for path in seen))

    def test_rollout_failure_still_restores_original_environment(self):
        reads = iter([deployment(), deployment(seed.INSTALLED, '9')])
        rollout_calls = []
        patches = []
        def kube(args):
            if args[0] == 'get':
                return json.dumps(next(reads))
            if args[0] == 'rollout':
                rollout_calls.append(args)
                if len(rollout_calls) == 1:
                    raise RuntimeError('rollout failed')
            return ''
        with patch.object(seed, 'kubectl', side_effect=kube), patch.object(seed, 'ensure_demo_secret'), \
                patch.object(seed, 'apply_patch', side_effect=lambda directory, name, value: patches.append(value)), \
                redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
            seed.run()
        self.assertEqual(2, len(patches))
        self.assertEqual(2, len(rollout_calls))
        self.assertTrue(all(entry.get('$patch') == 'delete' for entry in patches[-1]['spec']['template']['spec']['containers'][0]['env']))


if __name__ == '__main__':
    unittest.main()
