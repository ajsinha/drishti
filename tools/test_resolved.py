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

"""The resolver's cache key follows the modules the boot takes (i7a I5, KERNEL_AND_MODULES.md section 4.2.1): ``modules_fingerprint`` is
``ModuleHomes.fingerprint()`` worked out without the jar (the same vector as ``ModuleHomesTest``), and a rebuilt module changes it, so a
cached answer of an older module is never served."""
from __future__ import annotations

import hashlib
import os
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import resolved  # noqa: E402


def _dmod(path: Path, manifest: str, hashes: str) -> None:
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("module.yaml", manifest)
        z.writestr("META-INF/DMOD.MF", hashes)


def test_the_fingerprint_is_the_java_formula(tmp_path):
    banking_mf = "# DMOD.MF format 1\n"
    _dmod(tmp_path / "banking-1.0.0.dmod", "id: banking\nversion: 1.0.0\n", banking_mf)
    content = hashlib.sha256(banking_mf.encode()).hexdigest()
    want = hashlib.sha256(f"banking 1.0.0 {content}\n".encode()).hexdigest()
    assert resolved.modules_fingerprint(tmp_path) == want
    # the vector ModuleHomesTest.theFingerprintIsTheSha256OfTheIdentityLines holds: one package, one development folder
    lines = "banking 1.0.0 " + "a" * 64 + "\ngenomics 0.1.0 development\n"
    assert hashlib.sha256(lines.encode()).hexdigest() == "f7339103d32cab19ed228794976e07e9be7eb28dc4f104c10dceaba7d2cf2f29"


def test_source_folders_count_when_there_is_no_package(tmp_path):
    (tmp_path / "capital-markets").mkdir()
    (tmp_path / "capital-markets" / "module.yaml").write_text("id: CapitalMarkets\nversion: 1.2.0\n")
    (tmp_path / "dist").mkdir()                                       # no module.yaml: not a module
    want = hashlib.sha256(b"capitalmarkets 1.2.0 development\n").hexdigest()
    assert resolved.modules_fingerprint(tmp_path) == want
    assert resolved.modules_fingerprint(tmp_path / "absent") == hashlib.sha256(b"").hexdigest()


def test_a_rebuilt_module_changes_the_key(tmp_path):
    f = tmp_path / "trading-1.0.0.dmod"
    _dmod(f, "id: trading\nversion: 1.0.0\n", "x  module.yaml\n")
    before = resolved.modules_fingerprint(tmp_path)
    _dmod(f, "id: trading\nversion: 1.0.0\n", "y  module.yaml\n")    # same name and version, new content
    os.utime(f, ns=(f.stat().st_atime_ns, f.stat().st_mtime_ns + 1_000_000))
    after = resolved.modules_fingerprint(tmp_path)
    assert after != before
    (tmp_path / "trading").mkdir()                                     # a source folder beside packages is not read
    (tmp_path / "trading" / "module.yaml").write_text("id: other\n")
    assert resolved.modules_fingerprint(tmp_path) == after


def test_the_cache_key_carries_the_fingerprint():
    src = Path(resolved.__file__).read_text(encoding="utf-8")
    assert "_fingerprints(args)" in src.split("def _ask", 1)[1].split("def _resolution", 1)[0]
