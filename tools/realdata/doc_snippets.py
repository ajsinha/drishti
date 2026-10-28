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

"""The generated parts of docs/guides/PUBLIC_DATA.md (its world-economy section, which was REAL_DATA.md): the sources table, the two indicator tables and the provenance example. Each sits
between `<!-- realdata:begin NAME -->` and `<!-- realdata:end NAME -->` markers and is rewritten from sources.yaml and the dataset, so
the guide's numbers are the data's numbers.

    render(data)            {name: markdown}
    apply(text, data)       the guide text with every marked section replaced
"""
from __future__ import annotations

import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
GUIDE = ROOT / "docs" / "guides" / "PUBLIC_DATA.md"
LABEL = {"wdi": "World Bank WDI", "wgi": "World Bank WGI", "imf": "IMF WEO", "undp": "UNDP HDR"}
SECTIONS = {"indicators-economics": ("economy", "labour", "fiscal"), "indicators-society": ("social", "governance")}


def sources_table(d) -> str:
    vint = d.meta.get("vintage", {})
    rows = ["| Source | Dataset and vintage | Licence | Terms |", "|---|---|---|---|"]
    for key, s in d.cfg["sources"].items():
        rows.append(f"| {s['name']} | {s['dataset']} ({vint.get(key, '') or 'current'}) | {s['licence']} | {s['licence_url']} |")
    return "\n".join(rows)


def indicator_table(d, groups: tuple[str, ...]) -> str:
    rows = ["| Key | Indicator | Unit | Source and code | Countries with data |", "|---|---|---|---|---|"]
    for i in d.cfg["indicators"]:
        if i["group"] in groups:
            n = sum(1 for c in d.S.get(i["key"], {}) if c in d.C and d.S[i["key"]][c])
            rows.append(f"| `{i['key']}` | {i['name']} | {i['unit']} | {LABEL[i['kind']]} `{i['code']}` | {n} |")
    return "\n".join(rows)


def provenance_example(d) -> str:
    p = d.prov("imf")
    q = lambda k: json.dumps(p[k], ensure_ascii=False)       # noqa: E731
    return ("```json\n\"provenance\": {\"source\": " + q("source") + ", \"dataset\": " + q("dataset") + ",\n"
            "  \"retrievedAt\": " + q("retrievedAt") + ", \"licence\": " + q("licence") + ",\n"
            "  \"attribution\": " + q("attribution") + ",\n"
            "  \"url\": " + q("url") + "}\n```")


def render(d) -> dict[str, str]:
    out = {"sources": sources_table(d), "provenance": provenance_example(d)}
    out.update({name: indicator_table(d, groups) for name, groups in SECTIONS.items()})
    return out


def apply(text: str, d) -> str:
    for name, body in render(d).items():
        pat = re.compile(rf"(<!-- realdata:begin {name} -->\n).*?(\n<!-- realdata:end {name} -->)", re.S)
        if not pat.search(text):
            raise SystemExit(f"{GUIDE}: no realdata:{name} markers")
        text = pat.sub(lambda m: m.group(1) + body + m.group(2), text, count=1)
    return text


def write(d) -> bool:
    old = GUIDE.read_text(encoding="utf-8")
    new = apply(old, d)
    if new != old:
        GUIDE.write_text(new, encoding="utf-8")
    return new != old


def check(d) -> bool:
    return apply(GUIDE.read_text(encoding="utf-8"), d) == GUIDE.read_text(encoding="utf-8")
