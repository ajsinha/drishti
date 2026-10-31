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

"""Guard: Drishti creates no hidden (leading-dot) files or folders. The product owner's rule is "make everything visible".

Main code (Java under src/main, Python of the console and tools) may not name a file or folder with a leading dot, and the repository
may not track one, except the few names an external tool requires by name (ALLOWED). A legacy name that code only *reads* to migrate
an earlier build's data is listed in LEGACY, by file.
"""
import pathlib
import re
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Names an external tool requires; also listed in docs/guides/PACK_DEVELOPER_GUIDE.md ("Why these few names start with a dot").
ALLOWED = {".gitignore", ".dockerignore", ".editorconfig", ".java-version", ".mvn", ".github", ".idea", ".venv", ".claude",
           ".git", ".pytest_cache", "__pycache__", ".DS_Store", ".well-known", ".gitattributes", ".vscode", ".gitmodules"}

# Hidden names only READ so an earlier build's data is migrated once to the visible name (never written).
LEGACY = {".history", ".generated", ".migrated", ".previous", ".uploads", ".drishti-tmp", ".drishti-console-tmp", ".drishti-lake",
          ".drishti-pyodide", ".drishti-duckdb-wasm"}

# a literal that is a leading-dot file or folder NAME (not an extension such as ".yaml" or a property path such as ".fields")
HIDDEN_WORDS = re.compile(r"(?:history|generated|migrated|previous|uploads|staging|discarded|cache|lock|state|partial|done|stage|loading|"
                          r"samples-|drishti|part)\b")
LITERAL = re.compile(r"""["'](\.[A-Za-z][A-Za-z0-9_-]*)(?:["'/]|-["'])""")
CONCAT = re.compile(r"""(?:resolveSibling\(|resolve\(|Path\.of\(|with_name\(|joinpath\(|createFile\([^,()]*,|createDir\([^,()]*,)\s*["']\.["']\s*\+""")


def sources():
    out = []
    for pattern in ("drishti-*/src/main/**/*.java", "plugins/*/src/main/**/*.java", "drishti-console/core/**/*.py",
                    "drishti-console/routes/**/*.py", "tools/**/*.py", "tools/*.sh"):
        out += [p for p in ROOT.glob(pattern) if p.is_file() and not p.name.startswith("test_") and "/node_modules/" not in p.as_posix()
                and "/vendor/" not in p.as_posix() and p.name != "test_no_hidden_names.py"]
    return sorted(set(out))


class NoHiddenNames(unittest.TestCase):
    def test_main_code_names_no_new_hidden_file_or_folder(self):
        bad = []
        for p in sources():
            rel = p.relative_to(ROOT).as_posix()
            for n, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if line.lstrip().startswith(("//", "#", "*", "/*")) or "sibling(" in line or "Path.home()" in line:   # a suffix of a file name; the user's own ~/.cache (outside the repo)
                    continue
                for m in LITERAL.finditer(line):
                    name = m.group(1)
                    if name in ALLOWED or name in LEGACY or not HIDDEN_WORDS.match(name[1:]):
                        continue
                    if name.startswith((".tmp-", ".part")):                       # a suffix of a temp name, never a leading dot
                        continue
                    bad.append(f"{rel}:{n}: {name}")
                if CONCAT.search(line):
                    bad.append(f"{rel}:{n}: a name built with a leading dot")
        self.assertEqual(bad, [], "hidden names are not allowed (use a visible name; see PACK_DEVELOPER_GUIDE Part VIII, 'Why these few names start with a dot')")

    def test_repository_tracks_no_hidden_item_beyond_the_required_few(self):
        files = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.splitlines()
        bad = sorted({part for f in files for part in f.split("/") if part.startswith(".") and part not in ALLOWED})
        self.assertEqual(bad, [])


class NoLitterInTheRepoRoot(unittest.TestCase):
    def test_pack_make_with_a_data_dir_writes_only_under_it(self):
        import json
        import os
        import tempfile

        def status():
            return subprocess.run(["git", "status", "--porcelain", "--ignored"], cwd=ROOT, capture_output=True, text=True, check=True).stdout

        if not os.environ.get("DRISHTI_JAR") and not list((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar")):
            self.skipTest("pack make needs the server jar (DRISHTI_JAR or ./mvnw -q -DskipTests package)")
        before = status()
        with tempfile.TemporaryDirectory(prefix="drishti-nohidden-") as t:
            data = pathlib.Path(t) / "data"
            src = pathlib.Path(t) / "trades.jsonl"
            src.write_text("".join(json.dumps({"tradeId": f"T{i}", "type": ["IRS", "FRA"][i % 2], "notional": i}) + "\n" for i in range(6)))
            r = subprocess.run(["python3", str(ROOT / "tools" / "drishti.py"), "pack", "make", str(src), "--kind", "trade", "--match", "type",
                                "--name", "tidy"], cwd=ROOT, capture_output=True, text=True, env={**os.environ, "DRISHTI_DATA_DIR": str(data)})
            self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
            self.assertTrue((data / "build" / "tidy-1.0.0" / "pack" / "tidy" / "pack.yaml").is_file())
            self.assertTrue((data / "build" / "tidy-1.0.0" / "pack" / "tidy" / "generated").is_dir())       # the baseline is visible
            self.assertFalse((data / "build" / "tidy-1.0.0" / "pack" / "tidy" / ".generated").exists())
        self.assertEqual(status(), before)                                               # nothing new in the repository


class NoSecretTracked(unittest.TestCase):
    """The repository is public: the local cluster's secrets (drishti-stack/secrets.env, a cluster key file net-key) and server jars are never
    tracked; .gitignore keeps them out and this fails if one is added anyway (git add -f)."""

    def test_no_secret_file_or_jar_is_tracked(self):
        files = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.splitlines()
        bad = [f for f in files if f == "drishti-stack/secrets.env" or f.split("/")[-1] == "net-key" or f.startswith("drishti-stack/run/")
               or f.endswith(".jar")]
        self.assertEqual(bad, [], "a secret or a jar is tracked: git rm --cached it (the repository is public)")

    def test_gitignore_keeps_the_stack_secrets_and_run_folder_out(self):
        for path in ("drishti-stack/secrets.env", "drishti-stack/run/server.jar", "drishti-stack/run/pids.txt", "drishti-stack/nodes/x/net/net-key",
                     "data/stack/lakes/shared/x"):
            r = subprocess.run(["git", "check-ignore", "-q", "--no-index", path], cwd=ROOT)
            self.assertEqual(r.returncode, 0, path + " is not git-ignored")


if __name__ == "__main__":
    unittest.main()
