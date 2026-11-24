"""Render the production PostgreSQL settings from Compose without starting services or reading live env."""
import json
import os
from pathlib import Path
import re
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[3]


def render_postgres(**overrides):
    env = {key: os.environ[key] for key in ('PATH', 'HOME') if key in os.environ}
    source = (ROOT / 'docker-compose.prod.yml').read_text()
    env.update({key: 'synthetic-only' for key in re.findall(r'\$\{([A-Z0-9_]+):\?', source)})
    env.update({key: 'epharm.example.test' for key in ('API_DOMAIN', 'ADMIN_DOMAIN', 'S3_DOMAIN')})
    env['S3_PUBLIC_URL'] = 'https://epharm.example.test/s3'
    env.update(overrides)
    result = subprocess.run(
        ['docker', 'compose', '--project-directory', str(ROOT), '--env-file', os.devnull,
         '-f', str(ROOT / 'docker-compose.prod.yml'), 'config', '--format', 'json'],
        env=env, capture_output=True, text=True, check=True, timeout=30,
    )
    return json.loads(result.stdout)['services']['postgres']


def settings(service):
    command = service['command']
    assert command[0] == 'postgres', command
    pairs = [command[i + 1] for i, arg in enumerate(command) if arg == '-c']
    return dict(pair.split('=', 1) for pair in pairs)


class PostgresComposeTests(unittest.TestCase):
    def test_defaults_fit_the_shared_seven_gigabyte_host(self):
        service = render_postgres()
        values = settings(service)
        self.assertEqual(values['shared_buffers'], '1GB')
        self.assertEqual(values['effective_cache_size'], '4GB')
        self.assertEqual(values['work_mem'], '16MB')
        self.assertEqual(values['maintenance_work_mem'], '256MB')
        self.assertEqual(values['random_page_cost'], '1.1')
        # Parallel workers use /dev/shm; Docker's 64 MB default is too small for 16 MB work_mem.
        self.assertGreaterEqual(int(service['shm_size']), 256 * 1024 * 1024)

    def test_slow_queries_are_logged_and_counted(self):
        values = settings(render_postgres())
        self.assertEqual(values['log_min_duration_statement'], '1000')
        self.assertEqual(values['shared_preload_libraries'], 'pg_stat_statements')
        self.assertEqual(values['pg_stat_statements.track'], 'top')

    def test_memory_settings_can_be_overridden_from_env(self):
        values = settings(render_postgres(POSTGRES_SHARED_BUFFERS='1536MB', POSTGRES_WORK_MEM='8MB'))
        self.assertEqual(values['shared_buffers'], '1536MB')
        self.assertEqual(values['work_mem'], '8MB')


if __name__ == '__main__':
    unittest.main()
