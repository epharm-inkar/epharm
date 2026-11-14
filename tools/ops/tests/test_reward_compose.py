"""Render the production Compose contract without starting services or reading live env."""
import json
import os
from pathlib import Path
import re
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[3]


class RewardComposeTests(unittest.TestCase):
    def render(self, **overrides):
        # Supply only synthetic required interpolation inputs; never inherit production flags/secrets.
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
        return json.loads(result.stdout)['services']['backend']['environment']

    def test_automatic_credit_requires_its_own_explicit_opt_in(self):
        for enabled in ('false', 'true'):
            with self.subTest(evidence_enabled=enabled):
                env = self.render(POSM_SALE_BONUS_ENABLED=enabled)
                self.assertEqual(env['POSM_SALE_BONUS_ENABLED'], enabled)
                self.assertEqual(env['POSM_SALE_BONUS_AUTO_CREDIT_ENABLED'], 'false')
                self.assertEqual(env['POSM_SALE_BONUS_DEVICE_ALLOWLIST'], '')
                self.assertEqual(env['POSM_SALE_BONUS_ELIGIBLE_AFTER'], '')

    def test_explicit_credit_gate_and_exact_cutoff_reach_the_backend(self):
        values = dict(POSM_SALE_BONUS_ENABLED='true', POSM_SALE_BONUS_AUTO_CREDIT_ENABLED='true',
                      POSM_SALE_BONUS_DEVICE_ALLOWLIST='REWARD-PILOT-001,REWARD-PILOT-002',
                      POSM_SALE_BONUS_ELIGIBLE_AFTER='2026-11-14T14:00:00Z')
        env = self.render(**values)
        for key, value in values.items():
            self.assertEqual(env[key], value)

    def test_fleet_activation_requires_an_explicit_wildcard(self):
        env = self.render(POSM_SALE_BONUS_DEVICE_ALLOWLIST='*')
        self.assertEqual(env['POSM_SALE_BONUS_DEVICE_ALLOWLIST'], '*')
        self.assertEqual(env['POSM_SALE_BONUS_AUTO_CREDIT_ENABLED'], 'false')

    def test_example_keeps_both_money_gates_disabled(self):
        lines = (ROOT / '.env.prod.example').read_text().splitlines()
        for key in ('POSM_SALE_BONUS_ENABLED', 'POSM_SALE_BONUS_AUTO_CREDIT_ENABLED'):
            self.assertIn(f'{key}=false', lines)
        self.assertIn('POSM_SALE_BONUS_DEVICE_ALLOWLIST=', lines)


if __name__ == '__main__':
    unittest.main()
