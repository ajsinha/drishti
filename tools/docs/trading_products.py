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

"""The trading products catalogue of docs/guides/BANK_DATA_GUIDE.md, rendered from the product definitions (i7l).

    python3 tools/docs/trading_products.py            write the block
    python3 tools/docs/trading_products.py --check    fail if the guide differs from what would be written

The block between ``<!-- trading-products:begin -->`` and ``<!-- trading-products:end -->`` (the asset-class counts, one table per class and one
line per product) comes from ``tools/packgen/banking/products_*.py``. A market-data kind is linked to its example and its contract the first
time the block names it, as the guide's other generated sections do. Text outside the markers is never touched.
"""
from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
for sub in ("tools", "tools/docs", "tools/packgen/banking"):
    if str(ROOT / sub) not in sys.path:
        sys.path.insert(0, str(ROOT / sub))
import guide_data as GD  # noqa: E402
import taxonomy as T  # noqa: E402
import trade_shape as S  # noqa: E402

GUIDE = ROOT / "docs" / "guides" / "BANK_DATA_GUIDE.md"
BEGIN = "<!-- trading-products:begin -->"
END = "<!-- trading-products:end -->"
REL = "../"


def table(head: list[str], rows: list[list]) -> list[str]:
    out = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    out += ["| " + " | ".join(str(c).replace("|", "\\|").replace("\n", " ") for c in r) + " |" for r in rows]
    return out + [""]


def example_link(kind: str) -> str | None:
    """The kind's example on a contract page (docs/contracts/packs/<pack>.md#example-<domain>-<kind>), relative to the guide."""
    tag = f'<a id="example-'
    for page in sorted(GD.PACK_PAGES.glob("*.md")):
        for line in page.read_text(encoding="utf-8").splitlines():
            if line.startswith(tag) and line.rstrip().endswith(f'-{kind}"></a>'):
                return f"{REL}contracts/packs/{page.name}#" + line[len('<a id="'):line.index('"></a>')]
    return None


def block() -> str:
    cons = GD.contracts()
    seen: set[str] = set()

    def kind_text(kind: str) -> str:
        if kind in seen:
            return f"`{kind}`"
        seen.add(kind)
        ex, con = example_link(kind), cons.get(kind)
        cl = GD.contract_link(con, REL) if con else None
        if not ex:
            return f"`{kind}`"
        return f"[`{kind}`]({ex})" + (f" ([contract]({cl}))" if cl else "")

    lines = [f"The trading pack covers **{len(T.PRODUCTS)} products** in ten asset classes. Each has its own Sutra, matched on the trade's "
             "`PRODUCT_TYPE`, laid out as a deal screen (ticket, legs side by side with their flows and resets, events, payments); Calypso "
             "and Murex book them. The table says what each product is in the model the samples were generated from: its terms, schedule, "
             "the market data it is valued with and its risk measures (the valuation is market-risk's trade valuation, `TVAL`). "
             "`TRD PRODUCT_TYPE=<product type> <GO>` lists the trades in one product.", ""]
    by = defaultdict(list)
    for p in T.PRODUCTS:
        by[p.asset].append(p)
    lines += table(["Asset class", "Products"], [[a, len(ps)] for a, ps in by.items()])
    for a, ps in by.items():
        lines += [f"### {a}", ""]
        rows = [[p.name, f"`{p.code}`", p.family, p.schedule or "\u2014", ", ".join(kind_text(k) for _, k, _ in p.md) or "\u2014",
                 ", ".join(S.RISK_LABELS[r] for r in p.risk) or "\u2014"] for p in ps]
        lines += table(["Product", "PRODUCT_TYPE", "Family", "Schedule", "Market data", "Risk"], rows)
        for p in ps:
            lines += [f"**{p.name}** (`{p.code}`): {p.desc} Terms: " + ", ".join(f"`{f.name}` ({f.label})" for f in p.terms) + ".", ""]
    return "\n".join(lines)


def splice(text: str, new: str) -> str | None:
    i = text.find(BEGIN)
    j = text.find(END, i + len(BEGIN)) if i >= 0 else -1
    if i < 0 or j < 0:
        return None
    return text[:i] + BEGIN + "\n\n" + new + text[j:]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="fail if the guide differs from what would be written")
    check = ap.parse_args(argv).check
    text = GUIDE.read_text(encoding="utf-8")
    new = splice(text, block())
    if new is None:
        print(f"trading-products: no {BEGIN} ... {END} markers in {GUIDE.relative_to(ROOT)}", file=sys.stderr)
        return 1
    if new != text:
        if check:
            print("trading-products: stale: docs/guides/BANK_DATA_GUIDE.md (run python3 tools/docs/trading_products.py)", file=sys.stderr)
            return 1
        GUIDE.write_text(new, encoding="utf-8")
        print("wrote docs/guides/BANK_DATA_GUIDE.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
