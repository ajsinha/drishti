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

"""The structured part of a Sutra's doc: block, written in one place for every non-risk Sutra.

The doc: block of a Sutra says what the screen is (summary, description, params, examples, since); the structured fields a component
browser and an AI agent read (the Rachana doc definition) are written here from the Sutra's own text and its pack's committed
sample: category, tags, use-when, avoid-when, data (in-words, sample-row), example, see-also and keywords. The generators call
enrich() on every Sutra they write; the Sutras written by hand pass through the same function once (python sutradoc.py PATH...).
A doc that already has use-when is left as it is, so the function is safe to run again.
"""
from __future__ import annotations

import io
import json
import re
import sys
from pathlib import Path

import yaml

SKIP_PACKS = {"market-risk", "counterparty-risk", "climate-risk", "liquidity-risk", "operational-risk"}   # risk screens move with R2
SCALAR_ROW_FIELDS = 8
VALUE_MAX = 40
PAGE_FIELDS = 12
FIELD = re.compile(r"\$\.([A-Za-z_][A-Za-z0-9_]*)")
LINK = re.compile(r"link\([^,()]+(?:\([^)]*\))?[^,()]*,\s*'([a-z0-9-]+)'")
WHERE = re.compile(r"\$\.(\w+)\s*==\s*'([^']+)'")
PANEL_WORDS = {"table": "table", "kv": "record", "line": "chart", "hbar": "chart", "area": "chart", "surface": "chart", "candlestick": "chart",
               "scatter": "chart", "graph": "network", "map": "map", "ladder": "schedule", "waterfall": "chart", "metric": "figures",
               "tabs": "tabs", "timeline": "timeline", "status": "status", "pivot": "pivot"}


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


# What a generator is about to write (path -> text), so a Sutra's sample-row comes from the samples of THIS build, also in a folder where
# nothing was written yet (a fresh build folder: the first pass and the --check pass must agree). Filled by packbuild.files().
PENDING: dict[Path, str] = {}
PENDING_PACKS: set[Path] = set()


def pack_of(path: Path) -> Path | None:
    for p in path.resolve().parents:
        if (p / "pack.yaml").exists() or p in PENDING_PACKS:
            return p
    return None


def skipped(path: Path) -> bool:
    p = pack_of(path)
    return p is not None and p.name in SKIP_PACKS


def sample_row(pack: Path | None, kind: str, where: str) -> dict | None:
    """The first committed sample of the kind (for a trade, the first of the Sutra's product): its short scalar fields."""
    if pack is None:
        return None
    f = pack / "samples" / f"{kind}.jsonl"
    pending = PENDING.get(f)
    if pending is None and not f.exists():
        return None
    need = dict(WHERE.findall(where or ""))
    with (io.StringIO(pending) if pending is not None else f.open(encoding="utf-8")) as fh:
        for line in fh:
            if not line.strip():
                continue
            d = json.loads(line)
            if any(str(d.get(k)) != v for k, v in need.items()):
                continue
            out = {}
            for k, v in d.items():
                if isinstance(v, (str, int, float)) and not isinstance(v, bool) and len(str(v)) <= VALUE_MAX and str(v) != "":
                    out[k] = v
                if len(out) >= SCALAR_ROW_FIELDS:
                    break
            return out or None
    return None


def _flow(v) -> str:
    return json.dumps(v, ensure_ascii=False)


def _lower(s: str) -> str:
    return s[0].lower() + s[1:] if s[1:2].islower() else s


def fields_of(doc: dict) -> list[str]:
    """The record fields the page reads ($.NAME), in the order they first appear, not counting links and parents' lists."""
    seen: dict[str, None] = {}
    body = {k: v for k, v in doc.items() if k not in ("doc", "match")}
    for m in FIELD.finditer(yaml.safe_dump(body, width=10_000, sort_keys=False)):
        seen.setdefault(m.group(1))
    return list(seen)[:PAGE_FIELDS]


def extra_lines(path: Path, y: dict) -> list[str]:
    d = y.get("doc") or {}
    match = y.get("match") or {}
    kind = match.get("kind") or y.get("sutra")
    pill = ((y.get("title") or {}).get("pill") or "")
    cat, _, what = pill.partition(" · ")
    cat, what = cat.strip() or kind, (what.strip() or kind.replace("-", " "))
    summary = (d.get("summary") or y.get("description") or "").strip().rstrip(".")
    panels = y.get("panels") or []
    words = sorted({PANEL_WORDS[p["kind"]] for p in panels if isinstance(p, dict) and p.get("kind") in PANEL_WORDS})
    tags = list(dict.fromkeys([kind, slug(cat)] + words))
    examples = d.get("examples") or []
    command = next((e for e in examples if isinstance(e, str) and not e.startswith("/")), None) or (examples[0] if examples else None)
    own = y.get("sutra")
    see = [k for k in dict.fromkeys(LINK.findall(yaml.safe_dump(y, width=10_000))) if k != kind and k != own][:6]
    where = match.get("where") or ""
    sample = sample_row(pack_of(path), kind, where)
    narrowed = "".join(f", {k} = {v}" for k, v in WHERE.findall(where))
    in_words = f"One {_lower(what)} record (kind {kind}{narrowed}); the page reads " + ", ".join(fields_of(y)) + "."
    out = ["  category: " + _flow(cat), "  tags: " + _flow(tags),
           "  use-when: " + _flow(f"You have one {_lower(what)} (its id, or a link from another screen) and want to read it in full: {_lower(summary)}."),
           "  avoid-when: " + _flow("You want many records of this kind side by side: use the pack's list or the BI pivot; this page shows one."),
           "  data:", "    in-words: " + _flow(in_words)]
    if sample:
        out.append("    sample-row: " + _flow(sample))
    if command:
        out.append("  example: " + _flow(command))
    if see:
        out.append("  see-also: " + _flow(see))
    out.append("  keywords: " + _flow(list(dict.fromkeys(w for w in [kind.replace("-", " "), what.lower(), cat.lower()] if w))))
    return out


def enrich(text: str, path: Path) -> str:
    """The Sutra's text with the structured doc fields written before `since:`; the text unchanged when it has none to add."""
    if skipped(path) or re.search(r"(?m)^  use-when:", text) or not re.search(r"(?m)^doc:", text):
        return text
    y = yaml.safe_load(text)
    if not isinstance(y, dict) or "doc" not in y or "match" not in y:
        return text
    lines = text.split("\n")
    at = next((i for i, ln in enumerate(lines) if re.match(r"^  since:", ln)), None)
    if at is None:
        return text
    return "\n".join(lines[:at] + extra_lines(path, y) + lines[at:])


def main(paths: list[str]) -> None:
    n = 0
    for a in paths:
        f = Path(a)
        text = f.read_text(encoding="utf-8")
        new = enrich(text, f)
        if new != text:
            f.write_text(new, encoding="utf-8")
            n += 1
    print(f"{n} Sutras given the structured doc fields")


if __name__ == "__main__":
    main(sys.argv[1:])
