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

"""The "data" section of each measure or feature guide, from tools/docs/guide_data.yaml and the shipped sample data.

    python3 tools/docs/guide_data.py            write every guide's data section
    python3 tools/docs/guide_data.py --check    fail on a stale section, or a shown field the kind's data contract does not have

Per guide the spec names its file, its section number and, per dataset: the kind, the sample file glob under data/testdata (a
.jsonl.gz, or a tar of <table>/<date>/*.jsonl.gz as DATA_LAKE_GUIDE.md describes), an optional record selector and field list, whether the
feature needs it, what it is for (written by hand) and a link to the guide's own field table. The tool writes, between
``<!-- guide-data:begin <id> -->`` and ``<!-- guide-data:end -->`` in the guide, an at-a-glance table and one subsection per dataset with
the contract's key and one real record, formatted, long arrays shortened as ``/* … N more … */``. Text outside the markers is never
touched.

Deterministic: the last file of the glob in sort order (or the first, ``pick: first``), the first tar member in sort order (or the first
matching ``member``), the first record (or the first matching ``where``). Synthetic only: samples are read from data/testdata and nowhere
else. Field names come from the data contracts (config/packs/<domain>/<pack>/contracts/<kind>.contract.yaml): a shown field the contract does not
have, a ``fields`` entry the record does not have, a contract key the record lacks, or a link to a heading the guide does not have fails
``--check``.
"""
from __future__ import annotations

import argparse
import fnmatch
import gzip
import io
import json
import re
import sys
import tarfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core.pack_paths import pack_dirs  # noqa: E402  (the one rule for where a pack's folder is)
SPEC = Path(__file__).resolve().with_name("guide_data.yaml")
TESTDATA = ROOT / "data" / "testdata"
CONTRACTS = ROOT / "config" / "packs"
PACK_PAGES = ROOT / "docs" / "contracts" / "packs"

BEGIN = "<!-- guide-data:begin {id} -->"
END = "<!-- guide-data:end -->"

SCALAR_MAX = 9          # a scalar array longer than this shows its first and last HEAD
HEAD = 4
OBJECT_HEAD = 2         # an array of objects (or arrays) longer than this shows its first OBJECT_HEAD
KEYS_MAX = 12           # a nested object with more keys shows its first HEAD
STRING_MAX = 160        # a longer string is cut, ending in …


# ---------------------------------------------------------------- contracts

def contracts() -> dict[str, dict]:
    """kind -> its contract (with ``_pack``)."""
    out = {}
    for f in (f for d in pack_dirs(CONTRACTS).values() for f in sorted(d.glob("contracts/*.contract.yaml"))):
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        if doc.get("kind"):
            doc["_pack"] = f.parts[-3]
            out[doc["kind"]] = doc
    return out


def slug(heading: str) -> str:
    """GitHub's anchor of a heading."""
    s = heading.strip().lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def contract_link(contract: dict, rel: str) -> str | None:
    """The kind's section on its pack page (docs/contracts/packs/<pack>.md), relative to the guide's folder."""
    page = PACK_PAGES / f"{contract['_pack']}.md"
    if not page.exists():
        return None
    for line in page.read_text(encoding="utf-8").splitlines():
        if line.startswith("## ") and line.rstrip().endswith(f"(`{contract['kind']}`)"):
            return f"{rel}contracts/packs/{page.name}#{slug(line[3:])}"
    return f"{rel}contracts/packs/{page.name}"


# ---------------------------------------------------------------- samples

def _records(raw: bytes):
    for line in gzip.decompress(raw).decode("utf-8").splitlines() if raw[:2] == b"\x1f\x8b" else raw.decode("utf-8").splitlines():
        if line.strip():
            yield json.loads(line)


def sample(ds: dict) -> tuple[str, dict] | None:
    """(the file it came from, the record) for a dataset, or None when the demo data has none."""
    glob = ds.get("sample")
    if not glob:
        return None
    files = sorted(p for p in TESTDATA.glob(glob) if p.is_file())
    if not files:
        raise ValueError(f"{ds['kind']}: no file matches data/testdata/{glob}")
    where = {k: str(v) for k, v in (ds.get("where") or {}).items()}
    for path in files if ds.get("pick") == "first" else files[::-1]:     # a change file may be empty: the next one in order
        got = _first(path, ds, where)
        if got:
            return got
    raise ValueError(f"{ds['kind']}: no record in data/testdata/{glob} matches {where or 'the member'}")


def _first(path: Path, ds: dict, where: dict) -> tuple[str, dict] | None:
    """The first record of a file (of its first tar member, or of the first member matching ``member``) matching ``where``."""
    origin = path.relative_to(ROOT).as_posix()
    if path.suffix == ".tar":
        with tarfile.open(path) as tf:
            members = sorted((m for m in tf.getmembers() if m.isfile() and m.name.endswith((".jsonl", ".jsonl.gz"))),
                             key=lambda m: m.name)
            if ds.get("member"):
                members = [m for m in members if fnmatch.fnmatch(m.name, ds["member"])]
            for m in members:
                for rec in _records(tf.extractfile(m).read()):
                    if all(str(rec.get(k)) == v for k, v in where.items()):
                        return f"{origin}` → `{m.name}", rec
                if not where:
                    return None
        return None
    for rec in _records(path.read_bytes()):
        if all(str(rec.get(k)) == v for k, v in where.items()):
            return origin, rec
    return None


# ---------------------------------------------------------------- formatting

def _scalar(v) -> str:
    if isinstance(v, str) and len(v) > STRING_MAX:
        v = v[:STRING_MAX - 1].rstrip() + "…"
    return json.dumps(v, ensure_ascii=False)


def _more(n: int, what: str = "more") -> str:
    return f"/* … {n} {what} … */"


def fmt(value, indent: int = 0) -> str:
    """JSON, indented by two, with long arrays and objects shortened by a comment."""
    pad, inner = "  " * indent, "  " * (indent + 1)
    if isinstance(value, dict):
        if not value:
            return "{}"
        items = list(value.items())
        lines = [f'{inner}{json.dumps(k)}: {fmt(v, indent + 1)}' for k, v in items[:HEAD if indent and len(items) > KEYS_MAX else None]]
        if indent and len(items) > KEYS_MAX:
            lines.append(f"{inner}{_more(len(items) - HEAD, 'more keys')}")
        return "{\n" + ",\n".join(lines) + f"\n{pad}}}"
    if isinstance(value, list):
        if not value:
            return "[]"
        nested = any(isinstance(v, (dict, list)) for v in value)
        if nested and len(value) > OBJECT_HEAD:
            parts = [inner + fmt(v, indent + 1) for v in value[:OBJECT_HEAD]] + [inner + _more(len(value) - OBJECT_HEAD)]
        elif not nested and len(value) > SCALAR_MAX:
            parts = ([inner + _scalar(v) for v in value[:HEAD]] + [inner + _more(len(value) - 2 * HEAD)]
                     + [inner + _scalar(v) for v in value[-HEAD:]])
        else:
            parts = [inner + fmt(v, indent + 1) for v in value]
        return "[\n" + ",\n".join(parts) + f"\n{pad}]"
    return _scalar(value)


# ---------------------------------------------------------------- the section

def _rel(guide_file: str) -> str:
    depth = len(Path(guide_file).parent.relative_to("docs").parts)
    return "../" * depth


def build(guide: dict, cons: dict) -> tuple[str, list[str]]:
    """The generated block of one guide (without the markers), and the problems found."""
    problems: list[str] = []
    num, rel = str(guide["number"]), _rel(guide["file"])
    out = [f"### {num}.1 At a glance", "",
           "| Dataset (kind) | What it is for | Required? | Sample files (`data/testdata/…`) | Guide |", "|---|---|---|---|---|"]
    subs: list[str] = []
    for i, ds in enumerate(guide["datasets"], start=2):
        kind, ckind = ds["kind"], ds.get("contract", ds["kind"])
        contract = cons.get(ckind) if ckind else None
        where = f"{guide['id']}/{kind}"
        if ckind and contract is None:
            problems.append(f"{where}: no data contract for kind {ckind!r} (set contract: null if it has none)")
        link = contract_link(contract, rel) if contract else None
        label = ds.get("label") or f"`{kind}`"
        head = f"### {num}.{i} {label}: {ds['title']}"
        anchor = f"#{slug(head[4:])}"
        name = f"[{label}]({anchor})"
        files = f"`{ds['sample']}`" if ds.get("sample") else "no sample in the demo data"
        out.append(f"| {name} | {ds['for'].strip()} | {ds['required']} | {files} | {ds.get('guide') or '—'} |")

        subs += ["", head, "", ds["about"].strip(), ""]
        if contract:
            req = [f["name"] for f in contract.get("fields") or [] if f.get("presence") == "required"]
            key = ", ".join(f"`{k}`" for k in contract.get("key") or []) or "none"
            subs.append(f"- **Data contract:** [`{ckind}`]({link}) ({contract.get('mnemonic', '—')}), key {key}; "
                        f"{len(contract.get('fields') or [])} fields, {len(req)} required.")
        elif ds.get("contract_note"):
            subs.append(f"- **Data contract:** {ds['contract_note'].strip()}")
        try:
            got = sample(ds)
        except (ValueError, OSError, tarfile.TarError) as e:
            problems.append(f"{where}: {e}")
            got = None
        if got is None:
            subs.append(f"- **Sample:** {(ds.get('sample_note') or 'no sample in the demo data.').strip()}")
            continue
        origin, rec = got
        sel = " where " + ", ".join(f"`{k}` = `{v}`" for k, v in ds["where"].items()) if ds.get("where") else ""
        subs.append(f"- **Sample:** `{origin}`, the first record{sel}.")
        shown = {k: v for k, v in rec.items() if not k.startswith("_")}     # an envelope field (_meta) is not data
        if ds.get("fields"):
            missing = [f for f in ds["fields"] if f not in rec]
            if missing:
                problems.append(f"{where}: fields not in the sample record: {', '.join(missing)}")
            shown = {f: rec[f] for f in ds["fields"] if f in rec}
        if contract:
            names = {f["name"] for f in contract.get("fields") or []}
            extra = [f for f in shown if f not in names]
            if extra:
                problems.append(f"{where}: shown fields not in the {ckind} contract: {', '.join(extra)}")
            nokey = [k for k in contract.get("key") or [] if k not in rec]
            if nokey:
                problems.append(f"{where}: the sample record lacks the contract key {', '.join(nokey)}")
        subs += ["", "```jsonc", fmt(shown), "```"]
        omitted = [f for f in rec if f not in shown]
        if omitted:
            listed = ", ".join(f"`{f}`" for f in omitted[:8]) + (f" and {len(omitted) - 8} more" if len(omitted) > 8 else "")
            subs += ["", f"The record shows {len(shown)} of its {len(rec)} fields; omitted here: {listed}."]
    if guide.get("note"):
        out += ["", guide["note"].strip()]
    return "\n".join(out + subs) + "\n", problems


def anchors(text: str) -> set[str]:
    """The anchors of a Markdown document's headings (outside code fences)."""
    out, fence = set(), False
    for line in text.splitlines():
        if line.startswith("```"):
            fence = not fence
        elif not fence and re.match(r"#{1,6} ", line):
            out.add(slug(line.lstrip("#")))
    return out


def splice(text: str, gid: str, block: str) -> str | None:
    """The guide's text with the block between its markers replaced; None when the markers are missing."""
    begin = BEGIN.format(id=gid)
    i = text.find(begin)
    j = text.find(END, i + len(begin)) if i >= 0 else -1
    if i < 0 or j < 0:
        return None
    return text[:i] + begin + "\n" + block + text[j:]


def load() -> list[dict]:
    return yaml.safe_load(SPEC.read_text(encoding="utf-8"))["guides"]


def run(check: bool) -> int:
    cons, problems, stale = contracts(), [], []
    for guide in load():
        path = ROOT / guide["file"]
        block, found = build(guide, cons)
        problems += found
        text = path.read_text(encoding="utf-8")
        new = splice(text, guide["id"], block)
        if new is None:
            problems.append(f"{guide['file']}: no {BEGIN.format(id=guide['id'])} … {END} markers")
            continue
        have = anchors(new)
        for a in sorted(set(re.findall(r"\]\(#([^)]+)\)", block)) - have):
            problems.append(f"{guide['file']}: the data section links to #{a}, a heading the guide does not have")
        if new != text:
            if check:
                stale.append(guide["file"])
            else:
                path.write_text(new, encoding="utf-8")
                print(f"wrote {guide['file']}")
    for p in problems:
        print(f"guide-data: {p}", file=sys.stderr)
    for f in stale:
        print(f"guide-data: stale: {f} (run python3 tools/docs/guide_data.py)", file=sys.stderr)
    return 1 if problems or stale else 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="fail on a stale section or a field the contract does not have")
    return run(ap.parse_args(argv).check)


if __name__ == "__main__":
    sys.exit(main())
