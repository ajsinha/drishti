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

"""Generates the glossary source of the world-economy pack, tools/packgen/world/about.yaml, from two inputs: the indicator
vocabulary in sources.yaml (term = name, means = def, unit) and the hand-written kind and field descriptions in
tools/realdata/about/<pack>.kinds.yaml. The pack generators copy the result into config/packs/<pack>/config/about.yaml.

    python3 tools/realdata/about.py            write the about.yaml
    python3 tools/realdata/about.py --check    fail if it differs from what would be written

With no_imf the glossary has no IMF indicator, no economic-forecast kind and no sentence that cites the IMF.
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
import normalise as N  # noqa: E402

WIDTH = 170                                   # the line width of the committed files
# pack generator directory -> (pack name, indicator groups its vocabulary covers)
PACKS = {"world": ("world-economy", ("economy", "labour", "fiscal", "social", "governance"))}
NO_IMF_FISCAL_ABOUT = "${$.ECONOMY_NAME}'s central government revenue was ${fmt($.REVENUE, 'pc1')} of GDP and its expense ${fmt($.SPENDING, 'pc1')} of GDP (World Bank)."


def header() -> str:
    """The licence header every file carries (the first 13 lines of sources.yaml)."""
    return "\n".join((HERE / "sources.yaml").read_text(encoding="utf-8").split("\n")[:13])


def vocabulary(cfg: dict, groups: tuple[str, ...], no_imf: bool) -> dict:
    return {i["key"]: {"term": i["name"], "means": i["def"], "unit": i["unit"]}
            for i in cfg["indicators"] if i["group"] in groups and not (no_imf and i["kind"] == "imf")}


def kinds_text(pack: str, no_imf: bool) -> str:
    """The hand-written `kinds:` section: verbatim, or (no_imf) parsed, stripped of everything that cites the IMF and dumped again."""
    lines = (HERE / "about" / f"{PACKS[pack][0]}.kinds.yaml").read_text(encoding="utf-8").split("\n")
    while lines and (lines[0].startswith("#") or not lines[0].strip()):
        lines.pop(0)
    text = "\n".join(lines)
    if not no_imf:
        return text
    kinds = yaml.safe_load(text)["kinds"]
    del kinds["economic-forecast"]
    eco, fisc = kinds["economy"], kinds["fiscal-position"]
    eco["about"] = eco["about"].replace("World Bank, IMF and CIA", "World Bank and CIA")
    del eco["glossary"]["DEBT_TO_GDP"]
    fisc["about"] = NO_IMF_FISCAL_ABOUT
    fisc["glossary"] = {f: g for f, g in fisc["glossary"].items() if f not in ("DEBT", "BALANCE") and not f.startswith(("DEBT_PATH.", "BALANCE_PATH."))}
    return yaml.safe_dump({"kinds": kinds}, sort_keys=False, allow_unicode=True, width=WIDTH)


def render(pack: str, no_imf: bool = False) -> str:
    voc = yaml.safe_dump({"vocabulary": vocabulary(N.load_config(), PACKS[pack][1], no_imf)}, sort_keys=False, allow_unicode=True, width=WIDTH)
    return f"{header()}\n\nabout: 1\n{voc}{kinds_text(pack, no_imf)}"


def targets(out: Path | None = None) -> dict[str, Path]:
    """Pack generator directory -> the about.yaml it reads (out/<pack name>/about.yaml for a build somewhere else)."""
    return {p: (out / PACKS[p][0] / "about.yaml") if out else ROOT / "tools" / "packgen" / p / "about.yaml" for p in PACKS}


def write(out: Path | None = None, no_imf: bool = False) -> list[Path]:
    done = []
    for pack, path in targets(out).items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(render(pack, no_imf), encoding="utf-8")
        done.append(path)
    return done


def check(no_imf: bool = False) -> list[str]:
    return [str(p) for pack, p in targets().items() if not p.exists() or p.read_text(encoding="utf-8") != render(pack, no_imf)]


if __name__ == "__main__":
    if "--check" in sys.argv:
        bad = check()
        if bad:
            raise SystemExit(f"about.yaml out of date, run tools/realdata/about.py: {bad}")
        print("about: 2 files up to date")
    else:
        print("about: wrote " + ", ".join(str(p.relative_to(ROOT)) for p in write()))
