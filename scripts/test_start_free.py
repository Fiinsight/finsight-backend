import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest


class FreeEntrypointTest(unittest.TestCase):
    def run_entrypoint(self, supplied_token=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = shlex.quote(sys.executable)
            wrapper = f'''#!/bin/sh
if [ "$1" = "-c" ]; then exec {executable} "$@"; fi
exec {executable} - "$0" <<'PY'
import hashlib, os, pathlib, signal, sys, time
token = os.environ['AI_INTERNAL_TOKEN']
name = pathlib.Path(sys.argv[1]).name
pathlib.Path(os.environ['FIXTURE_DIR'], name).with_suffix('.hash').write_text(hashlib.sha256(token.encode()).hexdigest())
if name == 'java':
    for _ in range(100):
        if pathlib.Path(os.environ['FIXTURE_DIR'], 'python.hash').exists(): break
        time.sleep(.01)
else:
    signal.pause()
PY
'''
            for name in ('python', 'java'):
                file = root / name
                file.write_text(wrapper)
                file.chmod(0o755)
            env = {**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH'], 'FIXTURE_DIR': str(root)}
            env.pop('AI_INTERNAL_TOKEN', None)
            if supplied_token is not None:
                env['AI_INTERNAL_TOKEN'] = supplied_token
            result = subprocess.run(['sh', str(Path(__file__).resolve().parents[1] / 'deploy/start-free.sh')],
                                    env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(1, result.returncode)  # The child exits; the supervisor must stop its peer.
            self.assertNotIn('fixture-token', result.stdout + result.stderr)
            first = (root / 'python.hash').read_text()
            self.assertEqual(first, (root / 'java.hash').read_text())
            self.assertNotEqual(first, hashlib.sha256(b'').hexdigest())
            return first

    def test_both_processes_inherit_a_fresh_token_when_unconfigured(self):
        self.assertNotEqual(self.run_entrypoint(), self.run_entrypoint())

    def test_explicit_operator_token_is_preserved_for_both_processes(self):
        self.assertEqual(hashlib.sha256(b'fixture-token').hexdigest(), self.run_entrypoint('fixture-token'))


if __name__ == '__main__':
    unittest.main()
