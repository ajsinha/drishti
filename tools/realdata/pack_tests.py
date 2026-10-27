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

"""The Sutra tests of the world-economy pack (config/packs/<pack>/tests/): a few real documents per kind, copied
from the generated samples, an expect.yaml per kind and the help-coverage ratchet. Which documents and what they expect is in
pack_tests.yaml; the documents come from build_docs, so a data refresh updates the tests with the packs.

    files(pack, docs, base)   {path: text} of everything under <base>/tests
    write(...) / check(...)   write it (removing tests that no longer belong) / list what differs
"""
from __future__ import annotations

from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
import sys  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))
import sutracases as SC  # noqa: E402  (tests/<sutra>/cases.jsonl)
PACKS = ROOT / "config" / "packs"
COVERAGE_NOTE = "# Lowest share of shown fields with a glossary entry, per Sutra (a ratchet: raise it when about.yaml grows, never lower it).\n"


def spec() -> dict:
    return yaml.safe_load((HERE / "pack_tests.yaml").read_text(encoding="utf-8"))


def header() -> str:
    return "\n".join((HERE / "sources.yaml").read_text(encoding="utf-8").split("\n")[:13])


def files(pack: str, docs: dict, base: Path | None = None) -> dict[Path, str]:
    """Everything under <base>/tests for the pack (base defaults to config/packs/<pack>); kinds without documents (--no-imf) are skipped."""
    tests = (base or PACKS / pack) / "tests"
    cfg, out = spec()[pack], {}
    for kind, t in cfg["kinds"].items():
        if not docs.get(kind):
            continue
        out[tests / kind / SC.FILE] = SC.text([(sid, docs[kind][sid]) for sid in t["samples"]])            # one case a line
        out[tests / kind / "expect.yaml"] = f"{header()}\n\n\nnoErrors: true\nnonEmpty: [{', '.join(t['nonEmpty'])}]\nhelp: {{ about: true }}\n"
    out[tests / "help-coverage.txt"] = COVERAGE_NOTE + "".join(f"{k}={v}\n" for k, v in sorted(cfg["helpCoverage"].items()) if docs.get(k))
    return out


def write(pack: str, docs: dict, base: Path | None = None) -> int:
    want = files(pack, docs, base)
    tests = (base or PACKS / pack) / "tests"
    for stale in {f for f in tests.rglob("*") if f.is_file()} - set(want) if tests.exists() else ():
        stale.unlink()
    for f, text in want.items():
        f.parent.mkdir(parents=True, exist_ok=True)
        if not f.exists() or f.read_text(encoding="utf-8") != text:
            f.write_text(text, encoding="utf-8")
    return len(want)


def check(pack: str, docs: dict, base: Path | None = None) -> list[str]:
    want = files(pack, docs, base)
    tests = (base or PACKS / pack) / "tests"
    have = {f for f in tests.rglob("*") if f.is_file()} if tests.exists() else set()
    return [str(f) for f, t in want.items() if not f.exists() or f.read_text(encoding="utf-8") != t] + [str(f) for f in have - set(want)]
