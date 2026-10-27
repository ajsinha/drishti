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

"""QA2-05: tools/load-iceberg.sh and tools/load-cassandra.sh hand the samples and the trade book to their loader as ONE input, so
each business day of a table is written once and holds both (two loads of the same days replaced the samples with the book). Each
script runs in a copy of its folder with stand-ins for mvnw, uv and java that record what the loader was given. The PowerShell twins
(no PowerShell here) are read: one loader call, the book added to the samples' file first. Stdlib only.

    python3 -m unittest -q tools/test_load_scripts.py
"""
from __future__ import annotations

import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

UV = """#!/usr/bin/env bash
# stands in for uv: make_data.py --jsonl FILE writes two sample lines; bulk_trades.py --jsonl - prints three book lines
args="$*"
if [[ "$args" == *make_data.py* ]]; then
  out="${args##*--jsonl }"; out="${out%% *}"
  printf '{"kind":"trade","id":"S1"}\\n{"kind":"trade","id":"S2"}\\n' > "$out"
elif [[ "$args" == *bulk_trades.py* ]]; then
  printf '{"kind":"trade","id":"B1"}\\n{"kind":"trade","id":"B2"}\\n{"kind":"trade","id":"B3"}\\n'
fi
"""
MVNW = """#!/usr/bin/env bash
# stands in for the Maven wrapper: nothing to build, an empty class path
exit 0
"""
JAVA = """#!/usr/bin/env bash
# stands in for java: records each loader call and the lines it read (from its file argument, or stdin for -)
input=""
for a in "$@"; do case "$a" in *Loader) seen=1 ;; *) if [[ -n "$seen" && -z "$input" ]]; then input="$a"; fi ;; esac; done
if [[ "$input" == "-" ]]; then n=$(wc -l); else n=$(wc -l < "$input"); fi
echo "loader $n" >> "$CALLS"
if [[ -n "$LOADER_FAILS" ]]; then echo "iceberg: the bucket 'warehouse' does not exist at http://localhost:9000" >&2; exit 1; fi
"""


def _exe(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


class LoadScripts(unittest.TestCase):
    def run_script(self, script: str, args: list[str]) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "tools").mkdir()
            shutil.copy(ROOT / "tools" / script, tmp / "tools" / script)
            _exe(tmp / "mvnw", MVNW)
            _exe(tmp / "bin" / "uv", UV)
            _exe(tmp / "jdk" / "bin" / "java", JAVA)
            calls = tmp / "calls.txt"
            env = {**os.environ, "PATH": f"{tmp / 'bin'}:{os.environ['PATH']}", "JAVA_HOME": str(tmp / "jdk"), "CALLS": str(calls),
                   "DRISHTI_DATA_DIR": str(tmp / "data")}
            env.pop("DRISHTI_TMP_DIR", None)
            r = subprocess.run(["bash", str(tmp / "tools" / script), *args], cwd=tmp, env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertFalse((tmp / "data" / "banking.jsonl").exists(), "the input file is removed after the load")
            return calls.read_text().split("\n")[:-1]

    def test_a_failed_iceberg_load_ends_with_the_loaders_line_and_removes_its_input(self):
        """QA3B-12: the loader's one line, then the script's, status 1, the input removed; no stack trace, no PowerShell error record."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "tools").mkdir()
            shutil.copy(ROOT / "tools" / "load-iceberg.sh", tmp / "tools" / "load-iceberg.sh")
            _exe(tmp / "mvnw", MVNW)
            _exe(tmp / "bin" / "uv", UV)
            _exe(tmp / "jdk" / "bin" / "java", JAVA)
            env = {**os.environ, "PATH": f"{tmp / 'bin'}:{os.environ['PATH']}", "JAVA_HOME": str(tmp / "jdk"), "CALLS": str(tmp / "calls.txt"),
                   "DRISHTI_DATA_DIR": str(tmp / "data"), "LOADER_FAILS": "1"}
            env.pop("DRISHTI_TMP_DIR", None)
            r = subprocess.run(["bash", str(tmp / "tools" / "load-iceberg.sh")], cwd=tmp, env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(r.returncode, 1, r.stderr)
            self.assertEqual(r.stderr.strip().splitlines(), ["iceberg: the bucket 'warehouse' does not exist at http://localhost:9000",
                                                              "load-iceberg: the load did not finish: see the line above"])
            self.assertFalse((tmp / "data" / "banking.jsonl").exists())
        ps1 = (ROOT / "tools" / "windows" / "load-iceberg.ps1").read_text()
        after = ps1[ps1.index("& $java @jvm"):]
        self.assertNotIn("throw", after.split("Write-Host")[0], "a failed load is the loader's line, not a PowerShell error record")
        self.assertIn("exit $code", after)

    def test_iceberg_loads_the_samples_and_the_book_once(self):
        self.assertEqual(self.run_script("load-iceberg.sh", ["--trades", "3", "--days", "1"]), ["loader 5"])

    def test_iceberg_without_a_book_loads_the_samples(self):
        self.assertEqual(self.run_script("load-iceberg.sh", []), ["loader 2"])

    def test_cassandra_loads_the_samples_and_the_book_once(self):
        self.assertEqual(self.run_script("load-cassandra.sh", ["localhost:9042", "dc1", "--trades", "3", "--days", "1"]), ["loader 5"])

    def test_the_powershell_twins_call_their_loader_once_after_adding_the_book(self):
        for script, loader in (("load-iceberg.ps1", "IcebergLoader"), ("load-cassandra.ps1", "CassandraLoader")):
            text = (ROOT / "tools" / "windows" / script).read_text()
            code = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))
            self.assertEqual(len(re.findall(r"^\s*& \$java\b", code, re.M)), 1, f"{script}: one loader call")
            self.assertIn(loader, code)
            self.assertLess(code.index("Add-Content"), code.index("& $java"), f"{script}: the book joins the samples' file before the load")

    def test_every_loader_heap_is_drishti_loader_heap_and_its_input_is_under_the_data_folder(self):
        """QA2-21: no loader has a fixed -Xmx (or none at all: a quarter of the machine); none writes data/banking.jsonl in the
        repository when DRISHTI_DATA_DIR is set; each removes its input after the load."""
        for script in sorted((ROOT / "tools").glob("load-*.sh")):
            text = script.read_text()
            if "banking.jsonl" not in text:
                continue
            with self.subTest(script.name):
                self.assertIsNone(re.search(r"-Xmx\d", text), "a fixed heap")
                for call in re.findall(r'"\$JAVA_HOME/bin/java"[^\n]*', text):
                    self.assertIn('-Xmx"${DRISHTI_LOADER_HEAP:-4g}"', call)
                self.assertNotIn(" data/banking.jsonl", text)
                self.assertIn('DATA="${DRISHTI_DATA_DIR:-data}"', text)
                self.assertIn('rm -f "$DATA/banking.jsonl"', text)

    def test_make_data_writes_the_feeds_under_drishti_data_dir(self):
        """QA2-21: make_data.py wrote data/feeds in the repository even with DRISHTI_DATA_DIR set."""
        with tempfile.TemporaryDirectory() as tmp:
            code = ("import sys; sys.path.insert(0, 'tools/packgen/banking'); import make_data as m; "
                    "print(m.data_dir()); print(m.write_feeds(m.data_dir() / 'feeds'))")
            r = subprocess.run(["python3", "-c", code], cwd=ROOT, env={**os.environ, "DRISHTI_DATA_DIR": tmp},
                               capture_output=True, text=True, timeout=120)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.split("\n")[0], tmp)
            self.assertTrue((Path(tmp) / "feeds" / "fixing.jsonl").is_file())
        text = (ROOT / "tools" / "packgen" / "banking" / "make_data.py").read_text()
        self.assertNotIn("ROOT / 'data' / 'feeds'", text)

    def test_the_feed_is_jsonl_and_replaces_the_old_csv(self):
        """2.9.4: the sample feed is fixing.jsonl (one series a line, FIX SOFR-HISTORY); the unchanged CSV of earlier releases goes, and
        a loader's run (rewrite=False) leaves an existing feed alone."""
        sys.path.insert(0, str(ROOT / "tools" / "packgen" / "banking"))
        import make_data as m
        with tempfile.TemporaryDirectory() as tmp:
            old = Path(tmp) / "fixing" / "SOFR-HISTORY.csv"
            old.parent.mkdir()
            old.write_text(m.OLD_FEED_CSV, encoding="utf-8")
            f = m.write_feeds(Path(tmp))
            line = json.loads(f.read_text(encoding="utf-8"))
            self.assertEqual((line["id"], len(line["rows"]), line["rows"][0]), ("SOFR-HISTORY", 22, {"date": "2026-09-01", "rate": 3.95, "volume_bn": 1910}))
            self.assertFalse(old.parent.exists())
            f.write_text("{}\n", encoding="utf-8")
            m.write_feeds(Path(tmp), rewrite=False)
            self.assertEqual(f.read_text(encoding="utf-8"), "{}\n")

    def test_rows_runs_never_rewrite_the_samples_tests_or_feed(self):
        """make_data.py --jsonl/--lake only write rows: the generator's own outputs come from a plain run (generation and loading split)."""
        samples = sorted((ROOT / "config" / "packs").glob("*/samples/*.jsonl")) + sorted((ROOT / "config" / "packs" / "trading" / "tests").rglob("*.json*"))
        before = {f: f.stat().st_mtime_ns for f in samples}
        with tempfile.TemporaryDirectory() as tmp:
            r = subprocess.run([sys.executable, "tools/packgen/banking/make_data.py", "--lake", str(Path(tmp) / "delta"), "--no-load", "--days", "2"],
                               cwd=ROOT, env={**os.environ, "DRISHTI_DATA_DIR": tmp}, capture_output=True, text=True, timeout=300)
            self.assertEqual(r.returncode, 0, r.stderr)
            rows = Path(tmp) / "build" / "lake-rows"
            self.assertEqual(sorted(f.name for f in rows.iterdir()), ["banking-history.jsonl", "banking-restated.jsonl"])
            with rows.joinpath("banking-history.jsonl").open(encoding="utf-8") as f:
                first = json.loads(f.readline())
            self.assertEqual(sorted(first), ["date", "doc", "domain", "id", "kind", "table"])
            self.assertTrue((Path(tmp) / "feeds" / "fixing.jsonl").is_file())       # created, as the data folder had none
            self.assertFalse((Path(tmp) / "delta").exists())                         # --no-load: nothing loaded
        self.assertEqual({f: f.stat().st_mtime_ns for f in samples}, before)


if __name__ == "__main__":
    unittest.main()
