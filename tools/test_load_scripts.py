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

import os
import re
import shutil
import stat
import subprocess
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
            self.assertTrue((Path(tmp) / "feeds" / "fixing" / "SOFR-HISTORY.csv").is_file())
        text = (ROOT / "tools" / "packgen" / "banking" / "make_data.py").read_text()
        self.assertNotIn("ROOT / 'data' / 'feeds'", text)


if __name__ == "__main__":
    unittest.main()
