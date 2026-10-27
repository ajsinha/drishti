# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""deploy/aws and deploy/docker/ecs: every script parses, answers --help without settings, and with --dry-run on a fake settings file
prints its AWS calls without making one (a fake `aws` on the PATH fails the test if called); every JSON template renders to valid
JSON; the lifecycle tiers by business date and never expires or deep-archives table files; the settings file stays out of git. The
Iceberg scripts (deploy/aws/iceberg, 2.9.5; Delta stays the default) load with lake build --format iceberg and no publish step, create the
Glue database with ICEBERG_CATALOG=glue, tier data/ by business date and never touch metadata/; shellcheck, where installed, finds nothing.

    python3 -m unittest tools/test_deploy_aws.py
"""
import json
import os
import re
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AWS = ROOT / "deploy" / "aws"
SCRIPTS = sorted(p for p in AWS.rglob("*.sh") if p.parent.name != "lib")
ENTRYPOINTS = sorted((ROOT / "deploy" / "docker" / "ecs").glob("*.sh"))
ARGS = {"load-day.sh": ["--date", "2026-10-20"], "backfill.sh": ["--from", "2026-10-16", "--to", "2026-10-20"],
        "setup-lifecycle.sh": [], "register.sh": ["--nodes", "--consoles", "2", "--lake-jobs"]}
FAKE = {"AWS_REGION": "eu-west-2", "AWS_ACCOUNT_ID": "123456789012", "ORG": "acme", "DRISHTI_ENV": "test",
        "LAKE_KMS_KEY_ARN": "arn:aws:kms:eu-west-2:123456789012:key/test", "S3_VPC_ENDPOINT_ID": "vpce-test",
        "EFS_FILE_SYSTEM_ID": "fs-test", "VPC_ID": "vpc-test", "PRIVATE_SUBNETS": "subnet-a,subnet-b",
        "NODE_SECURITY_GROUP": "sg-test", "CONSOLE_TARGET_GROUP_ARN": "arn:aws:elasticloadbalancing:eu-west-2:123456789012:targetgroup/t/1",
        "ALARM_SNS_TOPIC_ARN": "arn:aws:sns:eu-west-2:123456789012:t"}


def fake_env(tmp: Path) -> Path:
    """env.example with each <placeholder> replaced by a fake value; work and lock folders in the temporary folder."""
    text = (AWS / "env.example").read_text(encoding="utf-8")
    for k, v in FAKE.items():
        text = re.sub(rf'^{k}="<[^>]*>"', f'{k}="{v}"', text, flags=re.M)
    assert '"<' not in text, "a placeholder of env.example has no fake value in the test: " + re.findall(r'^(\w+)="<', text, re.M)[0]
    text = text.replace("LAKE_WORK_DIR=/opt/drishti/data/lake-build", f"LAKE_WORK_DIR={tmp}/work")
    text = text.replace("LAKE_LOCK_DIR=/opt/drishti/data/lake-locks", f"LAKE_LOCK_DIR={tmp}/locks")
    f = tmp / "env"
    f.write_text(text, encoding="utf-8")
    return f


class DeployAwsTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.tmpdir = tempfile.TemporaryDirectory()
        cls.tmp = Path(cls.tmpdir.name)
        cls.env_file = fake_env(cls.tmp)
        bin_dir = cls.tmp / "bin"
        bin_dir.mkdir()
        cls.marker = cls.tmp / "aws-was-called"
        fake = bin_dir / "aws"
        fake.write_text(f"#!/bin/sh\necho \"$@\" >> {cls.marker}\nexit 97\n", encoding="utf-8")
        fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
        cls.path = f"{bin_dir}{os.pathsep}{os.environ.get('PATH', '')}"

    @classmethod
    def tearDownClass(cls):
        cls.tmpdir.cleanup()

    def run_script(self, script: Path, *args: str, env_file=None) -> subprocess.CompletedProcess:
        env = {**os.environ, "PATH": self.path, "TMPDIR": str(self.tmp)}
        env.pop("DRISHTI_AWS_ENV", None)
        if env_file:
            env["DRISHTI_AWS_ENV"] = str(env_file)
        return subprocess.run(["bash", str(script), *args], cwd=ROOT, env=env, capture_output=True, text=True, timeout=120)

    def test_every_script_parses_and_is_strict(self):
        self.assertGreaterEqual(len(SCRIPTS), 12)
        for s in SCRIPTS + [AWS / "lib" / "common.sh"]:
            r = subprocess.run(["bash", "-n", str(s)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, f"{s}: {r.stderr}")
        for s in ENTRYPOINTS:
            r = subprocess.run(["sh", "-n", str(s)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, f"{s}: {r.stderr}")
        self.assertIn("set -euo pipefail", (AWS / "lib" / "common.sh").read_text(encoding="utf-8"))
        for s in SCRIPTS:
            text = s.read_text(encoding="utf-8")
            self.assertIn("Copyright (c) 2026 Ashutosh Sinha", text[:600], s)
            self.assertIn('source "$(dirname "$0")/../lib/common.sh"', text, s)
            self.assertTrue(os.access(s, os.X_OK), f"{s} is not executable")

    def test_help_needs_no_settings_and_calls_nothing(self):
        for s in SCRIPTS:
            r = self.run_script(s, "--help", env_file=self.tmp / "missing-env")
            self.assertEqual(r.returncode, 0, f"{s.name} --help: {r.stderr}")
            self.assertTrue(r.stdout.startswith(s.name), f"{s.name} --help starts with its name: {r.stdout[:80]!r}")
            self.assertIn("--dry-run", r.stdout, s.name)
        self.assertFalse(self.marker.exists(), "--help called aws")

    def test_dry_run_prints_the_calls_and_makes_none(self):
        for s in SCRIPTS:
            with self.subTest(script=s.name):
                r = self.run_script(s, "--dry-run", *ARGS.get(s.name, []), env_file=self.env_file)
                self.assertEqual(r.returncode, 0, f"{s.name} --dry-run failed:\n{r.stdout[-2000:]}\n{r.stderr[-2000:]}")
                self.assertRegex(r.stdout + r.stderr, r"(?m)^[+?] (aws|\S*python\S*|cp) ", s.name)
                self.assertFalse(self.marker.exists(), f"{s.name} --dry-run called aws: {self.marker.read_text() if self.marker.exists() else ''}")
        self.assertEqual(list((self.tmp / "work").glob("*")) if (self.tmp / "work").exists() else [], [], "a dry run left scratch folders")

    def test_load_day_runs_lake_build_then_sync_then_publish(self):
        r = self.run_script(AWS / "delta-lake" / "load-day.sh", "--dry-run", "--date", "2026-10-20", "--tables", "trading/trade_endur",
                            env_file=self.env_file)
        self.assertEqual(r.returncode, 0, r.stderr)
        lines = [ln for ln in r.stderr.splitlines() if ln.startswith("+ ")]
        build = next(ln for ln in lines if " lake build " in ln)
        for part in ("--as-of 2026-10-20", "--partition-by PRODUCT_TYPE", "--table trade_endur", "--domain trading", "--kind trade",
                     "--connector trade-endur", "--batch-rows 250000", "--no-stamp"):
            self.assertIn(part, build)
        order = [k for k, ln in enumerate(lines) for word in (" lake build ", "aws s3 sync /", " lake publish ") if word in ln]
        self.assertEqual(order, sorted(order))
        self.assertIn("--exclude _delta_log/\\*", next(ln for ln in lines if "aws s3 sync /" in ln))
        publish = next(ln for ln in lines if " lake publish " in ln)
        self.assertIn("--to s3://acme-drishti-test-lake/lake/trading/trade_endur --dates 2026-10-20", publish)

    def test_lifecycle_tiers_by_business_date_and_never_expires_table_files(self):
        r = self.run_script(AWS / "delta-lake" / "setup-lifecycle.sh", "--print", env_file=self.env_file)
        self.assertEqual(r.returncode, 0, r.stderr)
        rules = json.loads(r.stdout)["Rules"]
        tiers = [x for x in rules if x["ID"].startswith("tier-")]
        self.assertTrue(tiers)
        classes = {t["StorageClass"] for x in tiers for t in x["Transitions"]}
        self.assertLessEqual(classes, {"STANDARD_IA", "GLACIER_IR"})
        for x in tiers:
            self.assertRegex(x["Filter"]["And"]["Prefix"], r"^lake/[a-z_]+/[a-z_]+/business_date=\d{4}(-\d{2})?")
            self.assertNotIn("_delta_log", json.dumps(x))
            self.assertNotIn("Expiration", x)
        for x in rules:
            if "Expiration" in x:
                self.assertEqual(x["Expiration"], {"ExpiredObjectDeleteMarker": True}, "a lifecycle rule expires current lake objects")
        self.assertLessEqual(len(rules), 1000)

    def test_templates_render_to_json(self):
        values = {**FAKE, "DRISHTI_ENV": "test", "NODE": "drishti-a", "JOB": "loader", "TASK_ROLE": "loader", "AP_DATA": "fsap-1",
                  "AP_CONFIG": "fsap-2", "AP_SUTRAS": "fsap-3", "REGISTRY_ARN": "arn:aws:servicediscovery:x:1:service/srv-1",
                  "SUBNETS_JSON": '["subnet-a","subnet-b"]', "COMMAND_JSON": '["deploy/aws/delta-lake/load-day.sh"]',
                  "LAUNCH_TYPE": "FARGATE", "CACHE_VOLUME": ', { "name": "nvme", "host": { "sourcePath": "/mnt/nvme/drishti/drishti-a" } }',
                  "CACHE_MOUNT": ', { "sourceVolume": "nvme", "containerPath": "/opt/drishti/nvme" }', "NET_SEEDS": "http://drishti-a.drishti.local:18480",
                  "CONSOLE_BACKENDS": "http://drishti-a.drishti.local:18480", "CONSOLE_COUNT": "2"}
        for line in self.env_file.read_text(encoding="utf-8").splitlines():
            m = re.match(r'^([A-Z_0-9]+)=("?)([^"#]*)\2', line)
            if m and m.group(1) not in values:
                values[m.group(1)] = m.group(3).strip()
        files = sorted((AWS / "ecs" / "templates").glob("*.json")) + sorted((AWS / "delta-lake" / "iam").glob("*.json")) \
            + sorted((AWS / "iceberg" / "iam").glob("*.json"))
        self.assertGreaterEqual(len(files), 14)
        for f in files:
            text = f.read_text(encoding="utf-8")
            for name in set(re.findall(r"\$\{([A-Z_][A-Z0-9_]*)\}", text)):
                self.assertIn(name, values, f"{f.name} uses ${{{name}}}, set neither by env.example nor by register.sh")
                text = text.replace("${" + name + "}", values[name])
            json.loads(text)                                     # raises on invalid JSON, naming nothing: the subTest name says which
        task = json.loads(re.sub(r"\$\{([A-Z_0-9]+)\}", lambda m: values[m.group(1)], (AWS / "ecs" / "templates" / "node-task.json").read_text()))
        mounts = {m["containerPath"] for c in task["containerDefinitions"] for m in c["mountPoints"]}
        self.assertTrue({"/opt/drishti/data", "/opt/drishti/config", "/opt/drishti/sutras"} <= mounts)
        self.assertTrue(all(c["readonlyRootFilesystem"] for c in task["containerDefinitions"]))

    def test_iceberg_load_day_builds_iceberg_in_s3_with_no_publish(self):
        r = self.run_script(AWS / "iceberg" / "load-day.sh", "--dry-run", "--date", "2026-10-20", "--tables", "risk/pnl-vector",
                            env_file=self.env_file)
        self.assertEqual(r.returncode, 0, r.stderr)
        lines = [ln for ln in r.stderr.splitlines() if ln.startswith("+ ")]
        build = next(ln for ln in lines if " lake build " in ln)
        for part in ("--format iceberg", "--lake s3://acme-drishti-test-lake/iceberg", "--domain risk", "--table pnl-vector",
                     "--date BUSINESS_DATE", "--iceberg-set s3.region=eu-west-2", "--connector risk-store"):
            self.assertIn(part, build)
        self.assertFalse(any(" lake publish " in ln for ln in lines), "Iceberg commits itself: no publish step")
        self.assertNotIn("glue", r.stderr)                                       # ICEBERG_CATALOG=hadoop: path-based tables

    def test_iceberg_with_glue_creates_the_database_and_signs_for_glue(self):
        glue = self.tmp / "env-glue"
        glue.write_text(self.env_file.read_text(encoding="utf-8").replace("ICEBERG_CATALOG=hadoop", "ICEBERG_CATALOG=glue"), encoding="utf-8")
        r = self.run_script(AWS / "iceberg" / "load-day.sh", "--dry-run", "--date", "2026-10-20", "--tables", "risk/pnl-vector", env_file=glue)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("? aws glue get-database", r.stderr)
        create = next(ln for ln in r.stderr.splitlines() if ln.startswith("+ aws glue create-database"))
        self.assertIn("s3://acme-drishti-test-lake/iceberg/risk", create)
        build = next(ln for ln in r.stderr.splitlines() if " lake build " in ln)
        for part in ("--iceberg-set catalog=rest", "--iceberg-set uri=https://glue.eu-west-2.amazonaws.com/iceberg",
                     "--iceberg-set catalog.rest.signing-name=glue", "--iceberg-set warehouse=123456789012"):
            self.assertIn(part, build)
        m = self.run_script(AWS / "iceberg" / "maintain.sh", "--dry-run", env_file=glue)
        self.assertEqual(m.returncode, 0, m.stderr)
        self.assertIn("--force-foreign --tables risk/pnl-vector", m.stderr)
        self.assertFalse(self.marker.exists())

    def test_iceberg_lifecycle_tiers_data_by_business_date_and_never_metadata(self):
        r = self.run_script(AWS / "iceberg" / "setup-lifecycle.sh", "--print", env_file=self.env_file)
        self.assertEqual(r.returncode, 0, r.stderr)
        rules = json.loads(r.stdout)["Rules"]
        tiers = [x for x in rules if x["ID"].startswith("iceberg-tier-")]
        self.assertTrue(tiers)
        for x in tiers:
            self.assertRegex(x["Filter"]["And"]["Prefix"], r"^iceberg/[a-z_-]+/[a-z_-]+/data/business_date=\d{4}(-\d{2})?")
            self.assertNotIn("metadata", json.dumps(x))
            self.assertNotIn("Expiration", x)
        for x in rules:
            if "Expiration" in x:
                self.assertEqual(x["Expiration"], {"ExpiredObjectDeleteMarker": True}, "a lifecycle rule expires current table objects")
        self.assertLessEqual(len(rules), 1000)

    def test_shellcheck_finds_nothing_where_installed(self):
        import shutil
        if not shutil.which("shellcheck"):
            self.skipTest("shellcheck is not installed here")
        r = subprocess.run(["shellcheck", "-x", "-S", "warning", *map(str, SCRIPTS), str(AWS / "lib" / "common.sh")], cwd=ROOT,
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout[-3000:])

    def test_settings_file_is_ignored_and_holds_no_secret(self):
        r = subprocess.run(["git", "check-ignore", "-q", "--no-index", "deploy/aws/env"], cwd=ROOT)
        self.assertEqual(r.returncode, 0, "deploy/aws/env is not git-ignored")
        text = (AWS / "env.example").read_text(encoding="utf-8")
        self.assertNotRegex(text, r"(?i)(secret_access_key|password|AKIA[0-9A-Z]{16})\s*=")


if __name__ == "__main__":
    unittest.main()
