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
        self.assertIn("datname=current_database() AND deadlocks<>0", sql)

    def test_source_bound_fixture_seeds_only_distinct_synthetic_identity_proofs(self):
        f = fixture.generate("source_unit", 1500, "source-bound")
        self.assertEqual("source-bound", f["identityMode"])
        self.assertEqual(750, sum(a["proofMethod"] == "IIN" for a in f["actors"]))
        self.assertEqual(750, sum(a["proofMethod"] == "NAME" for a in f["actors"]))
        for pharmacy in {a["pharmacyId"] for a in f["actors"]}:
            names = [a["name"] for a in f["actors"] if a["pharmacyId"] == pharmacy]
            self.assertEqual(len(names), len(set(names)))
            self.assertTrue(all(len(name.split()) == 2 and all(word.isalpha() for word in name.split()) for name in names))
        sql = fixture.seed_sql(f)
        self.assertNotIn("INSERT INTO posm_pharmacist_mappings(", sql)
        self.assertEqual(1500, sql.count("INSERT INTO posm_source_staff_bindings("))
        self.assertEqual(1500, sql.count("INSERT INTO posm_device_staff_sessions("))
        self.assertEqual(1500, sql.count("INSERT INTO reward_load_source_expected"))
        self.assertIn("source_verified_at,source_proof_method,source_proof_hash,expires_at", sql)
        for table in ("pharmacist_wallet_entries", "pending_bonuses", "receipts", "pos_sales", "recommendation_sale_rewards", "recommendation_reward_receipts", "recommendation_reward_shows"):
            self.assertNotIn("INSERT INTO " + table, sql)

    def test_source_bound_oracle_checks_no_hq_fallback_and_live_exact_proof_at_payment(self):
        sql = fixture.verification_sql(fixture.generate("source_unit", 8, "source-bound"))
        for required in ("s.pharmacist_source='standardn_rps_binding'", "EXISTS (SELECT 1 FROM posm_pharmacist_mappings)",
                         "g.source_verified_at>s.printed_at", "g.created_at>s.printed_at", "g.expires_at<=s.printed_at",
                         "g.revoked_at<=s.printed_at", "g.source_namespace=e.source_namespace", "g.standard_n_session_id=e.native_session_id",
                         "b.proof_hash<>e.proof_hash", "g.source_proof_hash<>e.proof_hash"):
            self.assertIn(required, sql)
        self.assertEqual("hq", fixture.generate("source_unit", 8)["identityMode"])
        with self.assertRaises(ValueError):
            fixture.generate("source_unit", 8, "unverified")

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
