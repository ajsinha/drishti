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

"""Compares two captures (tools/evidence/capture.py) and says what differs, in the two classes of I7B_PRODUCTS_PLAN 3.3:

  exact     bytes or numbers equal (viewmodels, frames, answers, figures): any difference not listed in the reviewed file fails
  reviewed  a difference is expected: it passes only when a line of the reviewed file names it and gives a reason
  pages     the console pages: the status must be equal; bytes and seconds within the thresholds below (never exact: a clock is involved)

    python3 tools/evidence/compare.py BASELINE_DIR NEW_DIR [--reviewed FILE.json] [--out compare.json]
    exit 0: no difference, or only reviewed ones; 1: a difference nobody listed; 2: usage

The reviewed file is JSON: [{"artefact": "viewmodels/*.json", "path": "regular expression of the difference's path", "reason": "words"}, ...];
a difference is reviewed when its artefact matches the glob (a file path under the capture folder) and its path matches the expression.
A file only in one capture is a difference at path "(file)". Nothing is ever matched by its reason: a line without one is refused."""
from __future__ import annotations

import argparse
import fnmatch
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

CLASSES = {"viewmodels": "EV1", "frames": "EV1", "answers": "EV3", "figures.json": "EV4", "sutras.json": "EV1", "pages.json": "EV5"}
#: Page-load thresholds: bytes within 5%; time within 25% plus 0.25 s (a timing is a measurement of a machine, so it only guards against a regression).
PAGE_BYTES_PCT = 5.0
PAGE_SECONDS_PCT = 25.0
PAGE_SECONDS_ABS = 0.25


def read(path: Path):
    """A capture file in its normal form (idempotent: a capture written under an older rule is read under today's)."""
    return common.normalise(json.loads(path.read_text(encoding="utf-8")))


def files(root: Path) -> dict:
    return {p.relative_to(root).as_posix(): p for p in sorted(root.rglob("*.json")) if p.name != "manifest.json" and not p.name.startswith("compare")}


def load_reviewed(path: Path | None) -> list:
    if not path:
        return []
    rows = json.loads(Path(path).read_text(encoding="utf-8"))
    for r in rows:
        if not r.get("reason", "").strip() or not r.get("artefact") or "path" not in r:
            raise ValueError(f"a reviewed line needs artefact, path and a reason: {r}")
    return rows


def reviewed_by(rows, artefact: str, path: str):
    for r in rows:
        if fnmatch.fnmatchcase(artefact, r["artefact"]) and re.search(r["path"], path):
            return r["reason"]
    return None


def page_differences(base, new) -> list:
    out = []
    nb = {p["path"]: p for p in new}
    for b in base:
        n = nb.get(b["path"])
        if n is None:
            out.append(f"{b['path']}: page missing")
            continue
        if b["status"] != n["status"]:
            out.append(f"{b['path']}: status {b['status']} -> {n['status']}")
        if b["bytes"] and abs(n["bytes"] - b["bytes"]) * 100.0 / b["bytes"] > PAGE_BYTES_PCT:
            out.append(f"{b['path']}: {b['bytes']} bytes -> {n['bytes']} bytes (more than {PAGE_BYTES_PCT}%)")
        if n["seconds"] > b["seconds"] * (1 + PAGE_SECONDS_PCT / 100.0) + PAGE_SECONDS_ABS:
            out.append(f"{b['path']}: {b['seconds']} s -> {n['seconds']} s (slower than {PAGE_SECONDS_PCT}% + {PAGE_SECONDS_ABS} s)")
    out += [f"{p}: page added" for p in nb if p not in {b["path"] for b in base}]
    return out


def compare(base: Path, new: Path, reviewed_rows: list) -> dict:
    fb, fn = files(base), files(new)
    result = {"identical": 0, "differences": []}
    for name in sorted(set(fb) | set(fn)):
        cls = next((v for k, v in CLASSES.items() if name == k or name.startswith(k + "/")), "other")
        if name not in fb or name not in fn:
            diffs = [("(file)", "only in the " + ("new" if name in fn else "baseline") + " capture")]
        elif name == "pages.json":
            diffs = [("/" + d.split(":")[0].strip("/"), d) for d in page_differences(read(fb[name]), read(fn[name]))]
        else:
            a, b = read(fb[name]), read(fn[name])
            diffs = [(d.split(":", 1)[0], d) for d in common.diff(a, b)]
        if not diffs:
            result["identical"] += 1
            continue
        for path, text in diffs:
            reason = reviewed_by(reviewed_rows, name, path)
            result["differences"].append({"artefact": name, "claim": cls, "path": path, "difference": text, "class": "reviewed" if reason else "unlisted", "reason": reason})
    return result


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("baseline", help="a capture folder or a .tar.gz of one (capture.py --tar)")
    ap.add_argument("new", help="a capture folder or a .tar.gz of one")
    ap.add_argument("--reviewed")
    ap.add_argument("--out")
    a = ap.parse_args(argv)
    import tempfile
    tmp = tempfile.TemporaryDirectory()
    dirs = []
    for i, d in enumerate((a.baseline, a.new)):
        if Path(d).is_file() and str(d).endswith(".tar.gz"):
            d = str(common.unpack(Path(d), Path(tmp.name) / str(i)))
        if not Path(d).is_dir():
            print(f"not a capture folder: {d}", file=sys.stderr)
            return 2
        dirs.append(d)
    a.baseline, a.new = dirs
    try:
        rows = load_reviewed(Path(a.reviewed) if a.reviewed else None)
    except (ValueError, OSError) as e:
        print(e, file=sys.stderr)
        return 2
    res = compare(Path(a.baseline), Path(a.new), rows)
    mb, mn = Path(a.baseline) / "manifest.json", Path(a.new) / "manifest.json"
    res["baseline"] = read(mb) if mb.exists() else None
    res["new"] = read(mn) if mn.exists() else None
    unlisted = [d for d in res["differences"] if d["class"] == "unlisted"]
    res["status"] = "no difference" if not res["differences"] else "reviewed differences only" if not unlisted else "unlisted differences"
    if a.out:
        Path(a.out).parent.mkdir(parents=True, exist_ok=True)
        Path(a.out).write_text(json.dumps(res, indent=1, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{res['status']}: {res['identical']} identical, {len(res['differences'])} differences ({len(unlisted)} unlisted)")
    for d in unlisted[:20]:
        print(f"  {d['artefact']}  {d['difference']}")
    return 1 if unlisted else 0


if __name__ == "__main__":
    sys.exit(main())
