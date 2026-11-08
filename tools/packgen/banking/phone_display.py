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

"""How a generated Sutra lays out on a phone (docs/guides/PACK_DEVELOPER_GUIDE.md): the screen's phone: block (its order and summary
line) and each panel's tier, written into the Sutras make_sutras.py generates, so the generator stays the one source of those files."""
from __future__ import annotations

import re

# file name -> (the screen's phone: block, its comment and lines; {panel id: its phone: value})
SCREENS = {
    "counterparty.v1.sutra.yaml": (
        ("phone:                                     # on a phone (docs/guides/PACK_DEVELOPER_GUIDE.md): who they are, then their netting sets at a glance",
         "  order: [kyc, nettingSets]",
         "  summary: \"${$.NAME} · rated ${$.RATING} · Net MTM ${fmt($.NET_MTM, 'signed0')}\""),
        {"nettingSets": "{ tier: glance, rows: 5, columns: [Netting set, Net MTM], sort: -Net MTM }", "kyc": "must", "hierarchy": "desk",
         "computed": "more", "computedSets": "more"}),
    "netting-set.v1.sutra.yaml": (
        ("phone:                                     # on a phone (docs/guides/PACK_DEVELOPER_GUIDE.md): what the set owes first, the trades at a glance",
         "  order: [exposureEngine, exposure, trades, byAsset]",
         "  summary: \"Net MTM ${fmt($.NET_MTM, 'signed0')} · ${$.TRADE_COUNT} trades · PFE 95 peak ${fmt($.EXPOSURE_ENGINE.PEAK_PFE, 'compact')}\""),
        {"exposure": "{ tier: glance, series: [Expected exposure, PFE 95] }", "exposureEngine": "{ tier: must, figures: 6 }",
         "trades": "{ tier: glance, rows: 5, columns: [Trade, Product, MTM], sort: -MTM }", "computed": "more", "pastMaturity": "more"}),
    "endur-comswap.v1.sutra.yaml": (
        ("phone:                           # on a phone (docs/guides/PACK_DEVELOPER_GUIDE.md): the ticket and the two sides, the first leg, the next flows",
         "  order: [ticket, fixed-float, basis, legs, flows]",
         "  summary: \"${$.CURRENCY} ${fmt(first($.LEGS).NOTIONAL, 'amount0')} · matures ${$.MATURITY_DATE}\""),
        {}),
}
_PANEL = re.compile(r"^  - id: (\S+)$")


def apply(name: str, text: str) -> str:
    """The Sutra's text with its phone: block before panels: and each listed panel's phone: after its kind: line."""
    if name not in SCREENS:
        return text
    screen, panels = SCREENS[name]
    out, current = [], None
    for ln in text.split("\n"):
        if ln == "panels:":
            out += list(screen)
        out.append(ln)
        m = _PANEL.match(ln)
        if m:
            current = m.group(1)
        elif current and ln.startswith("    kind: "):
            if current in panels:
                out.append(f"    phone: {panels[current]}")
            current = None
    return "\n".join(out)
