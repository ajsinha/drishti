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

"""The licence guard for what the console vendors (owner rule, 2026-11-16): every folder under static/vendor, and every engine inside the
vendored Drishti Elements (its own vendor/ and VENDOR.md), has a row with a licence from the allow list (MIT, Apache-2.0, BSD-2/3, ISC, SIL
OFL 1.1, MPL-2.0 unmodified, public domain or CC0, PSF), its licence file beside it, and Apache-2.0's NOTICE where upstream ships one.
Anything else (GPL, AGPL, LGPL, SSPL, CC-BY-NC/ND, the Commons Clause, BUSL, an unknown licence) fails the build before it ships. The one
proprietary row is Drishti Elements itself: the same owner and LICENSE as Drishti."""
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it

from core import elements_pin  # noqa: E402

VENDOR = Path(__file__).resolve().parent.parent / "web" / "static" / "vendor"
ELEMENTS_VENDOR = elements_pin.ROOT / "vendor"                 # the engines inside the vendored Drishti Elements
SAME_OWNER = "drishti-elements"                                 # the one proprietary row: the same owner and LICENSE as Drishti
ALLOWED = [r"\bMIT\b", r"Apache-2\.0", r"BSD-[23]", r"\bISC\b", r"SIL OFL 1\.1", r"MPL-2\.0", r"[Pp]ublic domain", r"\bCC0\b", r"\bPSF\b"]
DENIED = [r"\bA?GPL", r"\bLGPL", r"\bSSPL", r"CC-BY-N[CD]", r"Commons Clause", r"\bBUSL", r"Business Source", r"[Uu]nknown", r"[Pp]roprietary"]
NOTICE_FOLDERS = {"echarts", "apache-arrow"}          # Apache-2.0 upstreams that ship a NOTICE: kept beside the licence (inside Drishti Elements)
LICENCE_FILE = re.compile(r"^(LICENSE|LICENCE|COPYING|OFL)", re.I)


def rows(vendor=VENDOR):
    out = []
    for line in (vendor / "VENDOR.md").read_text(encoding="utf-8").splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if line.startswith("|") and len(cells) >= 6 and not set(cells[0]) <= set("-: "):
            out.append(cells)
    return out


def _folders(where: str) -> set:
    return {m.group(1) for m in re.finditer(r"`([\w.-]+)/", where)}


@pytest.mark.parametrize("vendor", [VENDOR, ELEMENTS_VENDOR], ids=["console", "drishti-elements"])
def test_every_licence_is_on_the_allow_list_and_none_is_denied(vendor):
    bad = []
    for cells in rows(vendor)[1:]:
        if _folders(cells[3]) == {SAME_OWNER}:
            assert re.search(r"[Pp]roprietary, the same owner", cells[2]) and (VENDOR / SAME_OWNER / elements_pin.VERSION / "LICENSE").is_file()
            continue
        lic = re.sub(r"\([^)]*\)", " ", cells[2])
        bad += [f"{cells[0][:50]}: {d}" for d in DENIED if re.search(d, lic)]
        if not any(re.search(a, lic) for a in ALLOWED):
            bad.append(f"{cells[0][:50]}: no allowed licence in {cells[2][:60]!r}")
    assert not bad, "vendored licences outside the allow list:\n" + "\n".join(bad)


@pytest.mark.parametrize("vendor", [VENDOR, ELEMENTS_VENDOR], ids=["console", "drishti-elements"])
def test_every_vendor_folder_has_a_row_and_its_licence_file_beside_it(vendor):
    listed = {}
    for cells in rows(vendor)[1:]:
        for f in _folders(cells[3]):
            listed[f] = cells
    folders = {p.name for p in vendor.iterdir() if p.is_dir()}
    assert folders <= set(listed), f"folders with no VENDOR.md row: {sorted(folders - set(listed))}"
    missing = []
    for f in sorted(folders):
        depth = 3 if f == SAME_OWNER else 2              # the release tree's LICENSE is one folder down, under its version
        files = [p.name for p in (vendor / f).rglob("*") if p.is_file() and len(p.relative_to(vendor / f).parts) <= depth]
        if not any(LICENCE_FILE.match(n) for n in files):
            missing.append(f)
    assert not missing, f"vendor folders with no licence file beside them: {missing}"


def test_apache_notice_files_are_kept():
    for f in NOTICE_FOLDERS:
        assert any(p.name.startswith("NOTICE") for p in (ELEMENTS_VENDOR / f).rglob("*") if p.is_file()), f + ": the Apache-2.0 NOTICE must stay beside the licence"
