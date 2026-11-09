import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("reward_fixture", Path(__file__).with_name("build-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class FixtureTest(unittest.TestCase):
    def test_distinct_1500_authorized_actors(self):
        f = fixture.generate("unit", 1500)
        self.assertEqual(1500, len(f["actors"]))
        self.assertEqual(500, len({a["pharmacyId"] for a in f["actors"]}))
        for key in ("deviceKey", "deviceId", "pharmacistId", "iin", "phone", "saleId", "sessionId", "sourceDocumentId"):
            self.assertEqual(1500, len({a[key] for a in f["actors"]}))
        self.assertEqual(576000, sum(a["expectedBonus"] for a in f["actors"]))
        for key in ("triggerBarcode", "recommendBarcode"):
            code = f[key]
            self.assertEqual(int(code[-1]), -sum(int(d) * (1 if i % 2 == 0 else 3) for i, d in enumerate(code[:12])) % 10)

    def test_fixture_never_seeds_money_or_paid_evidence(self):
        f = fixture.generate("unit", 8)
        sql = fixture.seed_sql(f)
        for table in ("pharmacist_wallet_entries", "pending_bonuses", "receipts", "pos_sales", "recommendation_sale_rewards", "recommendation_reward_receipts", "recommendation_reward_shows"):
            self.assertNotIn("INSERT INTO " + table, sql)
        self.assertEqual(8, sql.count("INSERT INTO posm_pharmacist_mappings("))
        self.assertEqual(8, sql.count("INSERT INTO fulfillment_devices("))
        self.assertIn("current_database() <> 'epharm_reward_load_unit'", sql)
        self.assertIn("fresh, migrated, empty isolated database", sql)
        self.assertNotIn("TRUNCATE", sql)
        self.assertNotIn("DELETE FROM", sql)

    def test_each_exported_expectation_is_specific_not_generic_200_success(self):
        sql = fixture.verification_sql(fixture.generate("unit", 8))
        for required in ("w.source='receipt_bonus'", "c.eligible_packages<>2", "r.bonus_credited<>400", "pb.bonus<>200",
                         "c.state<>'credited'", "p.balance<>e.expected_bonus", "p.earned_30d<>e.expected_bonus",
                         "physical_key IS NULL", "fiscal_key IS NULL", "spent=1600", "e.scenario='late121'",
                         "interval '120 seconds'", "s.pharmacist_source='standardn_explicit_mapping'"):
            self.assertIn(required, sql)

    def test_reject_unsafe_database_names_or_unbounded_count(self):
        for name, count in (("../prod", 8), ("PROD", 8), ("x" * 17, 8), ("unit", 7), ("unit", 1501)):
            with self.assertRaises(ValueError):
                fixture.generate(name, count)

    def test_oracle_accepts_both_jackson_instant_serializations(self):
        sql = fixture.verification_sql(fixture.generate("unit", 8))
        self.assertIn("jsonb_typeof(s.reward_evidence->'changes'->0->'observedAt')='number'", sql)
        self.assertIn("THEN to_timestamp(", sql)
        self.assertIn("ELSE (s.reward_evidence->'changes'->0->>'observedAt')::timestamptz END", sql)


if __name__ == "__main__":
    unittest.main()
