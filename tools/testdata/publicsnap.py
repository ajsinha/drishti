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
"""The public-data snapshots of the us-equities and gleif packs in the committed test data (data/testdata), the standard data
pattern every pack follows: the documents live in data/testdata/<pack>/<kind>/, `drishti.py lake demo` (tools/build_demo_lakes.py)
builds them into the Delta (or Iceberg) lake, and the pack's own Delta connector serves them. Never the demo connector.

    data/testdata/us-equities/us-company/snapshot-2026-10-24.jsonl.gz     one gzipped JSON Lines file per kind: the published data as
    data/testdata/gleif/lei-entity/snapshot-2026-10-24.jsonl.gz           retrieved on that date (real, dated: never re-dated)

A snapshot is the same documents on every business day of the demo lakes (as the news-and-markets pack's captured quotes): the pages
open as of today's business date and every earlier day the lakes hold. The generators (tools/packgen/us_equities/make.py,
tools/packgen/gleif/make.py) write the snapshots and their entries in testdata.yaml; tools/publicdata/us_equities.py and gleif.py
refresh them; tools/testdata/make_testdata.py keeps them as they are and lists them.

    files(pack, docs)              {path under data/testdata: bytes} of a pack's snapshot (deterministic gzip)
    write(testdata, pack, docs)    writes them, removes older snapshots of the pack, updates testdata.yaml; the paths written
    read(testdata, pack)           {kind: {id: document}} from the newest snapshot of each kind
    differences(testdata, pack, docs)   what differs between the committed snapshot and these documents (compared uncompressed)
    datasets(testdata)             the testdata.yaml entries of every pack's snapshots
    reference(pack, docs)          the few documents the pack's samples/ keep for its README and docs (not served from there)
"""
from __future__ import annotations

import gzip
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTDATA = ROOT / "data" / "testdata"

#: per pack: its Delta connector, the source of its data, and the id field of each kind (the lake's key)
PACKS: dict[str, dict] = {
    "us-equities": {"connector": "us-equities-store",
                    "source": "SEC EDGAR, Nasdaq Trader, GLEIF and Wikidata (tools/publicdata/us_equities.py)",
                    "keys": {"us-company": "TICKER", "index-membership": "INDEX", "sic-sector": "SIC", "filing-feed": "FEED",
                             "filing": "ACCESSION", "financial-fact": "FACT_ID"}},
    "gleif": {"connector": "gleif-store",
              "source": "GLEIF LEI records, relationship records and mapping files (tools/publicdata/gleif.py)",
              "keys": {"lei-entity": "LEI", "lei-group": "GROUP_ID", "lei-relationship": "RELATIONSHIP_ID", "lei-country": "COUNTRY",
                       "lei-mapping": "IDENTIFIER"}},
}
#: the documents a pack's samples/ keep as reference (README data structures, docs); the rest of each kind: the first REFERENCE_EACH
REFERENCE_IDS = {"us-equities": {"us-company": ["AAPL", "C", "F"], "index-membership": ["DJIA"], "sic-sector": ["3711"],
                                 "filing-feed": ["8-K"]},
                 "gleif": {"lei-entity": ["HWUPKR0MPOU8FGXBT394"], "lei-group": ["8I5DZWZKVSZI1NUHU748"], "lei-country": ["US"]}}
REFERENCE_EACH = 2


def _id(doc: dict, key: str) -> str:
    v = doc.get(key)
    return str(int(v)) if isinstance(v, float) and v.is_integer() else str(v)


def captured(docs: dict[str, dict[str, dict]]) -> str:
    """The date the data was retrieved (its provenance), the date in the snapshot's name."""
    for kind_docs in docs.values():
        for d in kind_docs.values():
            p = d.get("PROVENANCE") if isinstance(d.get("PROVENANCE"), dict) else {}
            when = p.get("RETRIEVED_AT") or (d.get("_meta") or {}).get("retrievedAt")
            if when:
                return str(when)[:10]
    raise ValueError("no document says when its data was retrieved (PROVENANCE.RETRIEVED_AT)")


def _gzip(text: str) -> bytes:
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, compresslevel=9, mtime=0) as fh:     # no name, no time: deterministic
        fh.write(text.encode("utf-8"))
    return buf.getvalue()


def _lines(kind_docs: dict[str, dict]) -> str:
    return "".join(json.dumps(d, ensure_ascii=False, separators=(",", ":")) + "\n" for d in kind_docs.values())


def files(pack: str, docs: dict[str, dict[str, dict]]) -> dict[str, bytes]:
    """{"<pack>/<kind>/snapshot-<date>.jsonl.gz": bytes} for every kind the pack keeps in the test data."""
    when = captured(docs)
    return {f"{pack}/{kind}/snapshot-{when}.jsonl.gz": _gzip(_lines(docs.get(kind, {}))) for kind in PACKS[pack]["keys"]}


def write(testdata: Path, pack: str, docs: dict[str, dict[str, dict]]) -> list[Path]:
    testdata = Path(testdata)
    want = files(pack, docs)
    for kind in PACKS[pack]["keys"]:
        for old in (testdata / pack / kind).glob("snapshot-*.jsonl.gz"):
            if str(old.relative_to(testdata)).replace("\\", "/") not in want:
                old.unlink()
    out = []
    for rel, data in want.items():
        f = testdata / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(data)
        out.append(f)
    update_manifest(testdata)
    return out + [testdata / "testdata.yaml"]


def _newest(testdata: Path, pack: str, kind: str) -> Path | None:
    got = sorted((Path(testdata) / pack / kind).glob("snapshot-*.jsonl.gz"))
    return got[-1] if got else None


def read(testdata: Path, pack: str) -> dict[str, dict[str, dict]]:
    out: dict[str, dict[str, dict]] = {}
    for kind, key in PACKS[pack]["keys"].items():
        f = _newest(testdata, pack, kind)
        if f is None:
            continue
        with gzip.open(f, "rt", encoding="utf-8") as fh:
            out[kind] = {_id(d, key): d for d in (json.loads(x) for x in fh if x.strip())}
    return out


def kind_docs(pack: str, kind: str, testdata: Path = TESTDATA) -> list[dict]:
    """One kind's documents from the newest snapshot (empty when there is none)."""
    return list(read(testdata, pack).get(kind, {}).values()) if _newest(testdata, pack, kind) else []


def differences(testdata: Path, pack: str, docs: dict[str, dict[str, dict]]) -> list[str]:
    """The snapshot files that are missing or hold other documents (compared uncompressed: zlib versions may differ), and stale ones."""
    testdata = Path(testdata)
    bad = []
    want = files(pack, docs)
    for rel, data in want.items():
        f = testdata / rel
        if not f.is_file():
            bad.append(f"data/testdata/{rel} (missing)")
        elif gzip.decompress(f.read_bytes()) != gzip.decompress(data):
            bad.append(f"data/testdata/{rel}")
    for kind in PACKS[pack]["keys"]:
        for f in (testdata / pack / kind).glob("snapshot-*.jsonl.gz"):
            if str(f.relative_to(testdata)).replace("\\", "/") not in want:
                bad.append(f"data/testdata/{f.relative_to(testdata)} (not written by the generator)")
    listed = {(d["pack"], d["kind"], d.get("snapshot")) for d in _manifest(testdata).get("datasets") or []}
    for rel in want:
        p, kind, name = rel.split("/")
        if (p, kind, name) not in listed:
            bad.append(f"data/testdata/testdata.yaml (does not list {rel})")
    return bad


def datasets(testdata: Path) -> list[dict]:
    """The testdata.yaml entries of every pack's newest snapshots, in pack and kind order."""
    out = []
    for pack, spec in PACKS.items():
        for kind, key in spec["keys"].items():
            f = _newest(testdata, pack, kind)
            if f is None:
                continue
            with gzip.open(f, "rt", encoding="utf-8") as fh:
                n = sum(1 for x in fh if x.strip())
            out.append({"folder": f"{pack}/{kind}", "pack": pack, "kind": kind, "connector": spec["connector"], "key": key,
                        "snapshot": f.name, "captured": f.name[len("snapshot-"):-len(".jsonl.gz")], "source": spec["source"],
                        "documents": n, "bytes": f.stat().st_size})
    return out


def _manifest(testdata: Path) -> dict:
    import yaml
    path = Path(testdata) / "testdata.yaml"
    return (yaml.safe_load(path.read_text(encoding="utf-8")) or {}) if path.is_file() else {}


def update_manifest(testdata: Path) -> None:
    """testdata.yaml with these packs' snapshot entries in place of earlier ones (its header comment and every other entry kept)."""
    import yaml
    path = Path(testdata) / "testdata.yaml"
    text = path.read_text(encoding="utf-8") if path.is_file() else ""
    lines = text.splitlines()
    i = 0
    while i < len(lines) and (lines[i].startswith("#") or not lines[i].strip()):
        i += 1
    head = "\n".join(lines[:i]) + "\n" if i else ""
    m = yaml.safe_load(text) or {}
    keep = [ds for ds in m.get("datasets") or [] if ds.get("pack") not in PACKS]
    m["datasets"] = keep + datasets(testdata)
    path.write_text(head + yaml.safe_dump(m, sort_keys=False, width=140), encoding="utf-8")


def default_testdata(pack_dir: Path, committed: Path) -> Path:
    """Where a generator writes the snapshot: data/testdata for the committed pack; beside a widened build's pack folder otherwise
    (<data>/publicdata/<pack>/packs/<pack> -> <data>/publicdata/<pack>/testdata), never into the committed test data."""
    return TESTDATA if Path(pack_dir).resolve() == Path(committed).resolve() else Path(pack_dir).resolve().parent.parent / "testdata"


def generate(pack: str, pack_dir: Path, docs: dict[str, dict[str, dict]], text: dict[Path, str], check: bool, testdata: Path) -> int:
    """A generator's last step. ``text``: the pack files it writes (the reference samples, the guide, the README). --check: 0 when
    those and the snapshot in ``testdata`` match, else 1 with what differs; otherwise writes them all (stale samples removed)."""
    def rel(p: Path) -> str:
        return str(p.relative_to(ROOT)) if p.resolve().is_relative_to(ROOT) else str(p)
    samples = Path(pack_dir) / "samples"
    have = {f for f in samples.glob("*") if f.is_file()}
    if check:
        bad = [rel(f) for f, t in text.items() if not f.is_file() or f.read_text(encoding="utf-8") != t]
        bad += [rel(f) + " (not written by the generator)" for f in have - set(text)]
        bad += differences(testdata, pack, docs)
        if bad:
            print(f"{pack} pack differs from its generator:\n  " + "\n  ".join(bad))
            return 1
        print(f"{pack} pack matches its generator ({sum(len(v) for v in docs.values())} documents in {rel(Path(testdata))})")
        return 0
    for f, t in text.items():
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(t, encoding="utf-8")
    for f in have - set(text):
        f.unlink()
    written = write(testdata, pack, docs)
    total = sum(f.stat().st_size for f in written if f.name.endswith(".gz"))
    print(f"wrote {rel(Path(pack_dir))} (reference samples, guide, README) and {rel(Path(testdata))}/{pack}: "
          f"{ {k: len(v) for k, v in docs.items()} }; {len(written) - 1} snapshot files, {total / 1e6:.1f} MB")
    return 0


def reference(pack: str, docs: dict[str, dict[str, dict]]) -> dict[str, dict[str, dict]]:
    """The few documents of each kind the pack's samples/ keep for its README and docs: the named ones, else the first few."""
    out = {}
    for kind in PACKS[pack]["keys"]:
        have = docs.get(kind, {})
        ids = [i for i in REFERENCE_IDS.get(pack, {}).get(kind, []) if i in have] or list(have)[:REFERENCE_EACH]
        if ids:
            out[kind] = {i: have[i] for i in ids}
    return out
