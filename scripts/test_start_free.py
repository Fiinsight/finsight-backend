import hashlib
import json
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

    def test_archive_and_plain_jar_launches_preserve_runtime_limits_and_profile(self):
        for archive in (False, True):
            with self.subTest(archive=archive), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                app = root / 'app'
                (app / 'application').mkdir(parents=True)
                if archive:
                    (app / 'application/application.jsa').touch()
                script = root / 'start.sh'
                script.write_text((Path(__file__).resolve().parents[1] / 'deploy/start-free.sh').read_text().replace('/app/', str(app) + '/'))
                wrapper = f'''#!{sys.executable}
import json, os, pathlib, signal, sys, time
root = pathlib.Path(os.environ['FIXTURE_DIR'])
if pathlib.Path(sys.argv[0]).name == 'java':
    (root/'args.json').write_text(json.dumps(sys.argv[1:]))
    for _ in range(100):
        if (root/'python-ready').exists(): break
        time.sleep(.01)
else:
    (root/'python-ready').touch()
    signal.pause()
'''
                for name in ('java', 'python'):
                    executable = root / name
                    executable.write_text(wrapper)
                    executable.chmod(0o755)
                env = {**os.environ, 'PATH':str(root)+os.pathsep+os.environ['PATH'], 'FIXTURE_DIR':str(root), 'AI_INTERNAL_TOKEN':'fixture-token'}
                result = subprocess.run(['sh',str(script)],env=env,capture_output=True,text=True,timeout=10)
                self.assertEqual(1,result.returncode)
                args = json.loads((root/'args.json').read_text())
                self.assertIn('-Xmx192m',args)
                self.assertIn('-XX:ActiveProcessorCount=1',args)
                self.assertIn('--spring.profiles.include=render-free',args)
                self.assertEqual(str(app/('application/app.jar' if archive else 'app.jar')),args[args.index('-jar')+1])
                self.assertEqual(archive,any(arg.startswith('-XX:SharedArchiveFile=') for arg in args))


if __name__ == '__main__':
    unittest.main()
