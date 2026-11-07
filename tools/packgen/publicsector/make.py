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

"""Writes the public-sector packs' samples and READMEs (docs/guides/PUBLIC_SECTOR_GUIDE.md): each feed is run on its recorded
fixtures (tools/feeds/publicsector/tests/fixtures, never the network) with the tests' narrower selection, a few documents of each
kind are kept as the pack's samples (samples/<kind>.jsonl and catalog.jsonl), and the README is rendered from the pack, the samples
and its intro (tools/packgen/publicsector/readme/<pack>.intro.md).

    python3 tools/packgen/publicsector/make.py            write the six packs' samples and READMEs
    python3 tools/packgen/publicsector/make.py --check    fail when what is committed is not what it would write
"""
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
FEEDS = ROOT / "tools" / "feeds" / "publicsector"
FIXTURES = FEEDS / "tests" / "fixtures"
AS_OF = "2026-11-06"                       # the business date the fixtures were recorded for
sys.path.insert(0, str(FEEDS))
import base  # noqa: E402
import run as feedrun  # noqa: E402


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


SAMPLES = _load("drishti_packsamples", ROOT / "tools" / "packgen" / "common" / "packsamples.py")
README = _load("drishti_pack_readme", ROOT / "tools" / "packgen" / "common" / "readme.py")
GENERATOR = "tools/packgen/publicsector/make.py"

# feed -> its pack; the documents kept as samples: {kind: (ids wanted first, at most n)}
PACKS = {
    "world-development": ("world-development", {"development-profile": ([], 12),
                                                "development-series": (["WDIS-CAN-", "WDIS-IND-NY_GDP_MKTP_KD_ZG", "WDIS-BRA-SI_POV_DDAY",
                                                                        "WDIS-USA-NY_GDP_PCAP_CD", "WDIS-NGA-SP_POP_TOTL"], 16)}),
    "central-banks": ("central-banks", {"central-bank": ([], 2), "rate-series": ([], 13)}),
    "national-statistics": ("national-statistics", {"statistics-series": ([], 7)}),
    "oecd": ("oecd", {"oecd-country": ([], 10), "oecd-series": (["OECDS-USA-", "OECDS-CAN-"], 10)}),
    "official-news": ("official-news", {"release-source": ([], 9), "press-release": (["PREL-FED-", "PREL-ECB-", "PREL-BOC-", "PREL-SEC-",
                                                                                      "PREL-FEDREG-", "PREL-BOE-"], 18)}),
    "gdelt-events": ("gdelt-events", {"event-country": ([], 13)}),
}
TITLE_FIELDS = {"development-profile": ("WDI", "INCOME_GROUP"), "development-series": ("WDIS", "INDICATOR"),
                "central-bank": ("CBANK", "COUNTRY_NAME"), "rate-series": ("CBSER", "SOURCE"), "statistics-series": ("NSTAT", "TABLE_ID"),
                "oecd-country": ("OECD", "COUNTRY_NAME"), "oecd-series": ("OECDS", "TITLE"), "release-source": ("PRESS", "PUBLISHER_TYPE"),
                "press-release": ("PREL", "PUBLISHER_NAME"), "event-country": ("GDELT", "DATE")}


def pull(feed: str) -> dict[str, list[dict]]:
    """The feed's documents from its recorded fixtures, with the tests' selection, in a scratch state folder."""
    spec = base.load_spec(feed)
    narrow = yaml.safe_load((FEEDS / "tests" / "selection.yaml").read_text(encoding="utf-8")) or {}
    spec["selection"] = {**(spec.get("selection") or {}), **(narrow.get(feed) or {})}
    with tempfile.TemporaryDirectory(prefix="publicsector-") as tmp:
        f = feedrun.feed_class(feed)(spec, base.Fetch(opener=base.Fixtures(FIXTURES / feed)), AS_OF, log=lambda *_: None)
        return f.pull({"dir": tmp})


def key_of(pack_dir: Path, kind: str) -> str:
    for c in (yaml.safe_load((pack_dir / "contracts" / f"{kind}.contract.yaml").read_text(encoding="utf-8")) or {}).get("key") or [] \
            if (pack_dir / "contracts" / f"{kind}.contract.yaml").is_file() else []:
        return c
    return {"development-profile": "PROFILE_ID", "development-series": "SERIES_ID", "central-bank": "BANK_ID", "rate-series": "SERIES_ID",
            "statistics-series": "SERIES_ID", "oecd-country": "COUNTRY_ID", "oecd-series": "SERIES_ID", "release-source": "SOURCE_ID",
            "press-release": "RELEASE_ID", "event-country": "EVENT_COUNTRY_ID"}[kind]


def pick(rows: list[dict], key: str, wanted: list[str], n: int) -> list[dict]:
    rows = sorted(rows, key=lambda d: str(d[key]))
    out, seen = [], set()
    for w in wanted:
        for d in rows:
            if str(d[key]).startswith(w) and d[key] not in seen and len(out) < n:
                out.append(d)
                seen.add(d[key])
    for d in rows:
        if len(out) >= n:
            break
        if d[key] not in seen:
            out.append(d)
            seen.add(d[key])
    return out


def build() -> dict[Path, str]:
    """Every file this generator owns: {path: text}."""
    out: dict[Path, str] = {}
    for feed, (pack, kinds) in PACKS.items():
        pack_dir = ROOT / "config" / "packs" / pack
        got = pull(feed)
        docs, catalog = {}, []
        for kind, (wanted, n) in kinds.items():
            key = key_of(pack_dir, kind)
            chosen = pick(got.get(kind) or [], key, wanted, n)
            docs[kind] = {str(d[key]): d for d in chosen}
            word, sub = TITLE_FIELDS[kind]
            catalog += [{"kind": kind, "id": str(d[key]), "title": f"{word} {d[key]}",
                         "subtitle": " · ".join(str(x) for x in (d.get("NAME") or d.get("TITLE"), d.get(sub)) if x)} for d in chosen]
        for name, text in SAMPLES.files(docs, catalog).items():
            out[pack_dir / "samples" / name] = text
        files = {f"samples/{name}": text for name, text in SAMPLES.files(docs, catalog).items()}
        intro = Path(__file__).resolve().parent / "readme" / f"{pack}.intro.md"
        out[pack_dir / "README.md"] = README.render_pack(pack_dir, generator=GENERATOR, files=files, intro=intro)
    return out


def main(argv: list[str]) -> int:
    want = build()
    if "--check" in argv:
        stale = [str(f.relative_to(ROOT)) for f, t in want.items() if not f.is_file() or f.read_text(encoding="utf-8") != t]
        if stale:
            print("the public-sector packs are out of date; run " + GENERATOR + ":\n  " + "\n  ".join(stale))
            return 1
        print(f"{len(want)} public-sector pack files up to date")
        return 0
    for f, t in want.items():
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(t, encoding="utf-8")
    for pack, _ in PACKS.values():                      # samples the packs no longer hold
        sd = ROOT / "config" / "packs" / pack / "samples"
        for f in sd.glob("*.jsonl"):
            if f not in want:
                f.unlink()
    print(f"wrote {len(want)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
