#!/usr/bin/env python3
"""Generate isolated synthetic data and guarded SQL; never opens a socket or database."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import uuid

helper_path = Path(__file__).resolve().parents[3] / "load-tests/build-rps-fixture.py"
spec = importlib.util.spec_from_file_location("rps_fixture_helpers", helper_path)
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
quote, guard, exclusive = helpers.sql_literal, helpers.guard, helpers.exclusive


def scenario(index):
    cases = ["duplicate_race", "offline_reorder", "foreign_device", "removed", "preexisting", "late121"]
    return cases[index % 100] if index % 100 < len(cases) else "positive"


def generate(run_id, count=1500):
    if not re.fullmatch(r"[a-z0-9_]{1,16}", run_id) or not 8 <= count <= 1500:
        raise ValueError("run-id: 1..16 lowercase letters/digits/underscores; actors: 8..1500")
    prefix = "reward-" + run_id
    actors = []
    for i, iin in enumerate(helpers.synthetic_iins(count)):
        pharmacy = f"{prefix}-ph-{i // 3:04d}"
        kind = scenario(i)
        actors.append({"pharmacyId": pharmacy, "deviceId": f"REWARD-{i:04d}", "deviceKey": secrets.token_urlsafe(32),
                       "pharmacistId": f"{prefix}-person-{i:04d}", "iin": iin, "phone": f"+7700{i:07d}",
                       "name": f"Reward Synthetic {i:04d}", "rawUserId": f"STD-USER-{i % 3 + 1}",
                       "sourceNamespace": hashlib.sha256((pharmacy + ":synthetic-full-package-adapter").encode()).hexdigest(),
                       "nativeSessionId": 10000 + i, "sourceDocumentId": 1000000 + i,
                       "fiscalNumber": 100000 + i, "fiscalShift": 99,
                       "sessionId": f"{prefix}-checkout-{i:04d}", "saleId": f"{prefix}-sale-{i:04d}",
                       "scenario": kind, "expectedBonus": 400 if kind in ("positive", "duplicate_race", "offline_reorder") else 0})
    # Synthetic product IDs are intentionally local, with no storefront/Medusa links.
    return {"kind": "epharm-isolated-reward-load-v1", "runId": run_id, "expectedDatabase": f"epharm_reward_load_{run_id}",
            "triggerSku": prefix + "-trigger", "triggerBarcode": "4870000000012", "recommendSku": prefix + "-offer",
            "recommendBarcode": "4870000000029", "promoId": prefix + "-promo", "ruleId": prefix + "-rule",
            "playlistId": prefix + "-playlist", "slideId": prefix + "-slide", "bonus": 200, "packages": 2,
            "price": 2000, "unitSemantics": "full_package", "actors": actors}


def seed_sql(f):
    lines = ["\\set ON_ERROR_STOP on", "BEGIN;", guard(f["expectedDatabase"]), """
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pharmacists) OR EXISTS (SELECT 1 FROM pharmacist_wallet_entries)
    OR EXISTS (SELECT 1 FROM recommendation_sale_rewards) OR EXISTS (SELECT 1 FROM receipts)
    OR EXISTS (SELECT 1 FROM pending_bonuses) OR EXISTS (SELECT 1 FROM pos_sales)
  THEN RAISE EXCEPTION 'Fixture requires a fresh, migrated, empty isolated database'; END IF;
END $$;
CREATE TABLE reward_load_expected (
  pharmacist_id text PRIMARY KEY, pharmacy_id text NOT NULL, raw_user_id text NOT NULL,
  session_id text UNIQUE NOT NULL, sale_id text UNIQUE NOT NULL, source_document_id bigint UNIQUE NOT NULL,
  scenario text NOT NULL, expected_bonus bigint NOT NULL
);
"""
    ]
    chain = "reward-" + f["runId"]
    lines.append(f"INSERT INTO chains(id,name,\"group\") VALUES ({quote(chain)},'Isolated reward load','pilot');")
    for sku, barcode, name, price in [(f["triggerSku"], f["triggerBarcode"], "Synthetic trigger", 1500),
                                    (f["recommendSku"], f["recommendBarcode"], "Synthetic recommendation", f["price"])]:
        lines.append("INSERT INTO products(id,name,brand,vendor,mnn,price,barcode) VALUES (" +
                     ",".join(map(quote, [sku, name, "Synthetic", "Synthetic", ""])) + f",{price},{quote(barcode)});")
    lines.append("INSERT INTO promos(id,title,status,brand,budget,spent,created_by,product_name,tiers,date_start,date_end) VALUES (" +
                 f"{quote(f['promoId'])},'Synthetic monetary load','active','Synthetic',{len(f['actors']) * 400 + 40000},0,'isolated-load'," +
                 "'Synthetic recommendation','[{\"minQty\":1,\"price\":2000,\"bonus\":200}]'::jsonb,current_date-1,current_date+1);")
    trigger = json.dumps({"kind": "product", "value": f["triggerSku"]})
    lines.append(f"INSERT INTO rules(id,type,status,trigger,recommend,bonus,script,created_by,promo_id) VALUES ({quote(f['ruleId'])}," +
                 f"'substitution','active',{quote(trigger)}::jsonb,{quote(f['recommendSku'])},200,'Synthetic load','isolated-load',{quote(f['promoId'])});")
    lines.append(f"INSERT INTO playlists(id,name,status,slides_count,duration_sec) VALUES ({quote(f['playlistId'])},'Synthetic playlist','active',1,15);")
    lines.append(f"INSERT INTO slides(id,title,kind,duration_sec,media_url,playlist_id,position) VALUES ({quote(f['slideId'])},'Synthetic slide','video',15," +
                 f"'http://127.0.0.1:19081/synthetic-not-playable.mp4',{quote(f['playlistId'])},0);")
    pharmacies = sorted(set(a["pharmacyId"] for a in f["actors"]))
    for pharmacy in pharmacies:
        lines.append("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,addr,\"group\") VALUES (" +
                     ",".join(map(quote, [pharmacy, pharmacy, chain, "Isolated reward load", "Synthetic", "Synthetic", "pilot"])) + ");")
        lines.append(f"INSERT INTO playlist_pharmacy_assignments(pharmacy_id,playlist_id) VALUES ({quote(pharmacy)},{quote(f['playlistId'])});")
    for a in f["actors"]:
        lines.append("INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,tier,status,phone_verification_status) VALUES (" +
                     ",".join(map(quote, [a["pharmacistId"], a["name"], a["iin"], a["phone"], a["pharmacyId"], a["pharmacyId"], "Silver", "active", "self_declared"])) + ");")
        digest = hashlib.sha256(a["deviceKey"].encode()).hexdigest()
        lines.append("INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES (" +
                     ",".join(map(quote, [str(uuid.uuid4()), a["deviceId"], a["pharmacyId"], digest, "load-only"])) + ");")
        # This is an explicit mapping fixture, not runtime name inference or self-declared auto-authorization.
        lines.append("INSERT INTO posm_pharmacist_mappings(id,pharmacy_id,external_user_id,external_user_name,pharmacist_id) VALUES (" +
                     ",".join(map(quote, [str(uuid.uuid4()), a["pharmacyId"], a["rawUserId"], a["name"], a["pharmacistId"]])) + ");")
        lines.append("INSERT INTO reward_load_expected VALUES (" + ",".join(map(quote, [a["pharmacistId"], a["pharmacyId"], a["rawUserId"], a["sessionId"], a["saleId"]])) +
                     f",{a['sourceDocumentId']},{quote(a['scenario'])},{a['expectedBonus']});")
    lines.append("COMMIT;")
    return "\n".join(lines) + "\n"


def verification_sql(f):
    count = len(f["actors"])
    credited = sum(a["expectedBonus"] > 0 for a in f["actors"])
    total = sum(a["expectedBonus"] for a in f["actors"])
    # Hibernate's JSONB mapper stores Instant as epoch seconds; HTTP uses ISO strings.
    instant = """(CASE WHEN jsonb_typeof(s.reward_evidence->'changes'->0->'observedAt')='number'
        THEN to_timestamp((s.reward_evidence->'changes'->0->>'observedAt')::double precision)
        ELSE (s.reward_evidence->'changes'->0->>'observedAt')::timestamptz END)"""
    return "\\set ON_ERROR_STOP on\nBEGIN READ ONLY;\n" + guard(f["expectedDatabase"]) + f"""
DO $$ BEGIN
  IF (SELECT count(*) FROM reward_load_expected) <> {count}
    OR (SELECT count(*) FROM pharmacists) <> {count}
    THEN RAISE EXCEPTION 'Fixture cardinality changed'; END IF;
  IF EXISTS (
    SELECT 1 FROM reward_load_expected e JOIN pharmacists p ON p.id=e.pharmacist_id
    WHERE p.pharmacy_id<>e.pharmacy_id OR p.status<>'active' OR p.balance<>e.expected_bonus OR p.earned_30d<>e.expected_bonus
  ) THEN RAISE EXCEPTION 'Wallet balance/earned delta/beneficiary/pharmacy mismatch'; END IF;
  IF (SELECT count(*) FROM pharmacist_wallet_entries) <> {credited}
    OR (SELECT coalesce(sum(amount_kzt),0) FROM pharmacist_wallet_entries) <> {total}
    OR (SELECT coalesce(sum(earned_delta_kzt),0) FROM pharmacist_wallet_entries) <> {total}
    THEN RAISE EXCEPTION 'Wallet cardinality/totals mismatch, including duplicates or negative-control credits'; END IF;
  IF EXISTS (
    SELECT 1 FROM reward_load_expected e LEFT JOIN pharmacist_wallet_entries w ON w.pharmacist_id=e.pharmacist_id
    GROUP BY e.pharmacist_id,e.expected_bonus
    HAVING coalesce(sum(w.amount_kzt),0)<>e.expected_bonus OR count(w.id)<>CASE WHEN e.expected_bonus>0 THEN 1 ELSE 0 END
  ) THEN RAISE EXCEPTION 'Per-employee duplicate or missing credit'; END IF;
  IF EXISTS (
    SELECT 1 FROM pharmacist_wallet_entries w
    LEFT JOIN pending_bonuses pb ON w.source='receipt_bonus' AND w.source_id=pb.id
    LEFT JOIN receipts r ON r.pending_bonus_id=pb.id AND r.status='approved'
    LEFT JOIN recommendation_sale_rewards c ON c.pending_bonus_id=pb.id AND c.receipt_id=r.id
    LEFT JOIN reward_load_expected e ON e.pharmacist_id=w.pharmacist_id
    WHERE pb.id IS NULL OR r.id IS NULL OR c.id IS NULL OR e.pharmacist_id IS NULL
      OR w.amount_kzt<>400 OR w.earned_delta_kzt<>400 OR pb.bonus<>200
      OR r.bonus_credited<>400 OR c.state<>'credited' OR c.total_bonus<>400 OR c.unit_bonus<>200 OR c.eligible_packages<>2
      OR NOT c.budget_reserved OR c.pharmacy_id<>e.pharmacy_id OR pb.pharmacy_id<>e.pharmacy_id OR r.pharmacy_id<>e.pharmacy_id
      OR c.pharmacist_id<>e.pharmacist_id OR pb.pharmacist_id<>e.pharmacist_id OR r.pharmacist_id<>e.pharmacist_id
      OR c.source_sale_id<>e.sale_id OR pb.source_sale_id<>e.sale_id OR c.product_id<>{quote(f['recommendSku'])}
      OR c.promo_id<>{quote(f['promoId'])} OR e.expected_bonus=0
  ) THEN RAISE EXCEPTION 'Invalid monetary source chain or source/recipient/amount mismatch'; END IF;
  IF (SELECT count(*) FROM recommendation_sale_rewards WHERE state='credited') <> {credited}
    OR EXISTS (SELECT canonical_receipt_id,product_id FROM recommendation_sale_rewards GROUP BY 1,2 HAVING count(*)>1)
    OR EXISTS (SELECT source,source_id FROM pharmacist_wallet_entries GROUP BY 1,2 HAVING count(*)>1)
    THEN RAISE EXCEPTION 'Canonical case or wallet source duplicate'; END IF;
  IF (SELECT count(*) FROM pos_sales s JOIN reward_load_expected e ON s.id=e.sale_id
      WHERE s.pharmacy_id=e.pharmacy_id AND s.pharmacist_id=e.pharmacist_id
        AND s.pharmacist_source='standardn_explicit_mapping' AND s.reward_evidence IS NOT NULL) <> {count}
    THEN RAISE EXCEPTION 'Not all actual HTTP sales were persisted with correct trusted identity'; END IF;
  IF (SELECT count(*) FROM recommendation_events v JOIN reward_load_expected e ON v.session_id=e.session_id
      WHERE v.rule_id={quote(f['ruleId'])} AND v.pharmacy_id=e.pharmacy_id AND v.recommend_sku={quote(f['recommendSku'])}) <> {count}
    THEN RAISE EXCEPTION 'Not all real positive recommendation events persisted'; END IF;
  IF EXISTS (SELECT 1 FROM recommendation_sale_rewards c JOIN reward_load_expected e ON c.source_sale_id=e.sale_id
      WHERE e.expected_bonus=0 AND (c.state IN ('ready','credited') OR c.budget_reserved))
    THEN RAISE EXCEPTION 'Negative control became payable'; END IF;
  IF EXISTS (SELECT 1 FROM recommendation_reward_shows s JOIN recommendation_events v ON v.id=s.event_id
      JOIN reward_load_expected e ON e.session_id=v.session_id WHERE e.scenario='foreign_device' AND s.auto_eligible)
    THEN RAISE EXCEPTION 'Foreign device made an eligible show'; END IF;
  IF NOT EXISTS (SELECT 1 FROM promos WHERE id={quote(f['promoId'])} AND spent={total} AND spent<=budget)
    THEN RAISE EXCEPTION 'Atomic campaign spent does not match credited source total'; END IF;
  IF EXISTS (SELECT 1 FROM recommendation_sale_rewards c
      JOIN recommendation_reward_receipts cr ON cr.id=c.canonical_receipt_id
      JOIN pos_sales s ON s.id=c.source_sale_id JOIN reward_load_expected e ON e.sale_id=s.id
      JOIN recommendation_events v ON v.id=c.event_id
      WHERE c.state='credited' AND (v.displayed_at IS NULL OR s.printed_at-v.displayed_at<=interval '120 seconds'
        OR cr.pharmacy_id<>e.pharmacy_id OR cr.physical_key IS NULL OR cr.fiscal_key IS NULL))
    THEN RAISE EXCEPTION 'Late payment or physical/fiscal receipt fence was not actually exercised'; END IF;
  IF EXISTS (SELECT 1 FROM pos_sales s JOIN reward_load_expected e ON e.sale_id=s.id
      JOIN recommendation_events v ON v.session_id=e.session_id
      WHERE e.expected_bonus>0 AND (
        jsonb_array_length(s.reward_evidence->'changes')<>1
        OR ({instant}-v.displayed_at)>interval '120 seconds'
        OR ({instant}-v.displayed_at)<interval '0 seconds'))
    THEN RAISE EXCEPTION 'Positive cart addition was not really within the display window'; END IF;
  IF EXISTS (SELECT 1 FROM pos_sales s JOIN reward_load_expected e ON e.sale_id=s.id
      JOIN recommendation_events v ON v.session_id=e.session_id WHERE e.scenario='late121'
      AND ({instant}-v.displayed_at)<=interval '120 seconds')
    THEN RAISE EXCEPTION '121-second control did not exercise a real late addition'; END IF;
END $$;
SELECT {count} AS actual_paid_actors, {credited} AS credited_employees, {total} AS credited_kzt,
       (SELECT count(*) FROM recommendation_sale_rewards WHERE state<>'credited') AS noncredited_cases,
       true AS per_person_wallet_verified, true AS physical_fiscal_idempotency_verified,
       true AS no_negative_control_money, true AS campaign_budget_verified;
SELECT e.scenario,count(*) AS actors,sum(e.expected_bonus) AS expected_kzt,
       (SELECT count(*) FROM recommendation_sale_rewards c JOIN reward_load_expected x ON x.sale_id=c.source_sale_id
        WHERE x.scenario=e.scenario AND c.state='credited') AS actual_credited_cases
FROM reward_load_expected e GROUP BY e.scenario ORDER BY e.scenario;
ROLLBACK;
"""


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-id", required=True)
    p.add_argument("--actors", type=int, default=1500)
    p.add_argument("--output-dir", type=Path, required=True)
    args = p.parse_args()
    f = generate(args.run_id, args.actors)
    args.output_dir.mkdir(parents=True, mode=0o700, exist_ok=False)
    exclusive(args.output_dir / "fixture.private.json", json.dumps(f, indent=2) + "\n")
    exclusive(args.output_dir / "seed.private.sql", seed_sql(f))
    exclusive(args.output_dir / "verify.sql", verification_sql(f))
    exclusive(args.output_dir / "compose.private.env", "DATABASE=" + f["expectedDatabase"] + "\nPG_PASSWORD=" + secrets.token_urlsafe(32) +
              "\nJWT_SECRET=" + secrets.token_urlsafe(48) + "\n")
    print(json.dumps({"state": "generated-only-no-external-actions", "actors": args.actors,
                      "pharmacies": len(set(a["pharmacyId"] for a in f["actors"])), "database": f["expectedDatabase"],
                      "expectedCreditKzt": sum(a["expectedBonus"] for a in f["actors"])}))


if __name__ == "__main__":
    main()
