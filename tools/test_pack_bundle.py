#!/usr/bin/env python3
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

"""Tests for `drishti.py pack bundle|verify|deploy|rollback` (tools/packbundle.py) in temporary folders; no Java and no server needed.

    python3 -m unittest -q tools/test_pack_bundle.py
"""
import contextlib
import io
import json
import pathlib
import shutil
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import drishti as D  # noqa: E402
import packbundle as PB  # noqa: E402


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = D.main([str(a) for a in argv])
    return code, out.getvalue(), err.getvalue()


class Bundles(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="pack-bundle-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.pack = self.tmp / "src" / "demo"
        (self.pack / "sutras").mkdir(parents=True)
        (self.pack / "config").mkdir()
        (self.pack / "sutras" / "a.sutra.yaml").write_text("kind: thing\n")
        (self.pack / "config" / "about.yaml").write_text("thing: {}\n")
        self.write_yaml("1.0.0")
        self.dist = self.tmp / "dist"
        self.to = self.tmp / "prod" / "packs"

    def write_yaml(self, version):
        (self.pack / "pack.yaml").write_text(f"pack: demo\nversion: {version}\nkinds: [thing]\n")

    def bundle(self, version=None):
        if version:
            self.write_yaml(version)
        code, out, err = run("pack", "bundle", self.pack, "--out", self.dist, "--no-check", "--json")
        self.assertEqual(0, code, err)
        return json.loads(out)

    def verify(self, src, *more):
        return run("pack", "verify", src, "--no-sutra", *more)

    def test_bundle_is_self_describing_and_reproducible(self):
        res = self.bundle()
        archive = pathlib.Path(res["archive"])
        self.assertEqual("demo-1.0.0.tar.gz", archive.name)
        self.assertIn(res["sha256"], (self.dist / "demo-1.0.0.tar.gz.sha256").read_text())
        man = json.loads((self.dist / "demo-1.0.0.manifest.json").read_text())
        self.assertEqual(("demo", "1.0.0", ["thing"]), (man["pack"], man["version"], man["kinds"]))
        self.assertEqual({"config/about.yaml", "pack.yaml", "sutras/a.sutra.yaml"}, {f["path"] for f in man["files"]})
        self.assertTrue(man["requiresServer"].startswith(">="))
        with tarfile.open(archive) as t:
            self.assertIn("demo/MANIFEST.json", t.getnames())
        again = self.bundle()
        self.assertEqual(res["sha256"], again["sha256"])

    def test_verify_deploy_and_rollback(self):
        b1 = self.bundle()["archive"]
        code, out, _ = self.verify(b1)
        self.assertEqual(0, code, out)
        self.assertEqual(0, run("pack", "deploy", b1, "--to", self.to, "--no-sutra", "--dry-run")[0])
        self.assertFalse((self.to / "demo").exists(), "a dry run copies nothing")
        code, out, _ = run("pack", "deploy", b1, "--to", self.to, "--no-sutra")
        self.assertEqual(0, code, out)
        self.assertEqual(0, self.verify(self.to / "demo")[0], "a deployed folder carries its manifest")
        b2 = self.bundle("1.0.1")["archive"]
        backups = self.tmp / "bk"
        code, out, _ = run("pack", "deploy", b2, "--to", self.to, "--backup", backups, "--no-sutra")
        self.assertEqual(0, code, out)
        self.assertIn("version: 1.0.1", (self.to / "demo" / "pack.yaml").read_text())
        self.assertEqual(1, len(list(backups.iterdir())))
        self.assertEqual([], [p.name for p in self.to.iterdir() if p.name.startswith(".")], "no temp leftovers")
        code, out, _ = run("pack", "rollback", "demo", "--to", self.to, "--backup", backups)
        self.assertEqual(0, code, out)
        self.assertIn("version: 1.0.0", (self.to / "demo" / "pack.yaml").read_text())
        self.assertEqual(2, len(list(backups.iterdir())), "the replaced version is kept, so a rollback can be undone")
        code, _, _ = run("pack", "rollback", "demo", "--to", self.to, "--backup", backups)       # and again: back to 1.0.1
        self.assertIn("version: 1.0.1", (self.to / "demo" / "pack.yaml").read_text())
        self.assertEqual(1, run("pack", "rollback", "demo", "--to", self.to, "--backup", backups, "--version", "9.9.9")[0])

    def test_default_backup_is_previous_under_target(self):
        run("pack", "deploy", self.bundle()["archive"], "--to", self.to, "--no-sutra")
        run("pack", "deploy", self.bundle("1.0.1")["archive"], "--to", self.to, "--no-sutra")
        self.assertEqual(1, len(list((self.to / ".previous").iterdir())))

    def test_tampered_archive_is_refused_and_nothing_is_deployed(self):
        archive = pathlib.Path(self.bundle()["archive"])
        with open(archive, "ab") as f:
            f.write(b"x")
        code, _, err = self.verify(archive)
        self.assertEqual(1, code)
        self.assertIn("sha256 does not match", err)
        code, _, err = run("pack", "deploy", archive, "--to", self.to, "--no-sutra")
        self.assertEqual(1, code)
        self.assertFalse(self.to.exists())

    def test_tampered_content_is_found_by_the_manifest(self):
        archive = pathlib.Path(self.bundle()["archive"])
        out = self.tmp / "x"
        folder = PB.safe_extract(archive, out)
        (folder / "sutras" / "a.sutra.yaml").write_text("kind: other\n")
        (folder / "sutras" / "extra.sutra.yaml").write_text("kind: sneaky\n")
        code, text, _ = self.verify(folder)
        self.assertEqual(1, code)
        self.assertIn("checksum mismatch: sutras/a.sutra.yaml", text)
        self.assertIn("file not in the manifest: sutras/extra.sutra.yaml", text)
        self.assertEqual(1, run("pack", "deploy", folder, "--to", self.to, "--no-sutra")[0])
        # repacked without the sidecar checksum: the manifest still catches it
        evil = self.tmp / "evil.tar.gz"
        with tarfile.open(evil, "w:gz") as t:
            t.add(folder, arcname="demo")
        self.assertEqual(1, self.verify(evil)[0])

    def test_unsafe_archive_entries_are_refused(self):
        evil = self.tmp / "evil.tar.gz"
        with tarfile.open(evil, "w:gz") as t:
            ti = tarfile.TarInfo("demo/../../escape.txt")
            ti.size = 1
            t.addfile(ti, io.BytesIO(b"x"))
        code, _, err = self.verify(evil)
        self.assertEqual(1, code)
        self.assertIn("unsafe entry", err)
        self.assertFalse((self.tmp.parent / "escape.txt").exists())

    def test_server_version_compatibility(self):
        archive = self.bundle()["archive"]
        code, text, _ = self.verify(archive, "--server-version", "0.1.0")
        self.assertEqual(1, code)
        self.assertIn("needs server >=", text)
        self.assertEqual(0, self.verify(archive, "--server-version", "99.0.0")[0])

    def test_schema_problems(self):
        (self.pack / "pack.yaml").write_text("pack: demo\nversion: 1.0.0\nkinds: thing\n")
        code, text, _ = self.verify(self.pack)
        self.assertEqual(1, code)
        self.assertIn("'kinds' must be a list", text)

    def test_bundle_needs_a_pack(self):
        self.assertEqual(2, run("pack", "bundle", self.tmp, "--out", self.dist, "--no-check")[0])


if __name__ == "__main__":
    unittest.main()
