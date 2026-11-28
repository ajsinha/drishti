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

    data/testdata/us-equities/us-company/us-company.2026-10-24.jsonl.gz   one gzipped JSON Lines file per kind, named by the load rules
    data/testdata/gleif/lei-entity/lei-entity.2026-10-25.jsonl.gz         (<table>.<YYYY-MM-DD>, docs/guides/DATA_LAKE_GUIDE.md):
                                                                          the published data as retrieved on that date (real: never re-dated)
    data/testdata/funds/fund/fund.tar                                     a large kind: a tar of fund/<YYYY-MM-DD>/snapshot.part-NN.jsonl.gz

A snapshot is the same documents on every business day of the demo lakes (as the news-and-markets pack's captured quotes): the pages
open as of today's business date and every earlier day the lakes hold. The generators (tools/packgen/us_equities/make.py,
tools/packgen/gleif/make.py) write the snapshots and their entries in testdata.yaml; the us-equities, gleif and funds data feeds (feed run <feed> --snapshot)
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
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTDATA = ROOT / "data" / "testdata"
sys.path.insert(0, str(ROOT / "tools" / "lake"))
import feedfiles as FF  # noqa: E402  (tools/lake/feedfiles.py: our dated feed files)

#: per pack: its Delta connector, the source of its data, and the id field of each kind (the lake's key)
PACKS: dict[str, dict] = {
    "us-equities": {"connector": "us-equities-store",
                    "source": "SEC EDGAR, Nasdaq Trader, GLEIF and Wikidata (drishti.py feed run us-equities)",
                    "keys": {"us-company": "TICKER", "index-membership": "INDEX", "filing-feed": "FEED",
                             "filing": "ACCESSION", "financial-fact": "FACT_ID"}},
    "gleif": {"connector": "gleif-store",
              "source": "GLEIF LEI records, relationship records and mapping files (drishti.py feed run gleif)",
              "keys": {"lei-entity": "LEI", "lei-relationship": "RELATIONSHIP_ID", "lei-mapping": "IDENTIFIER"}},
    "funds": {"connector": "funds-store",
              "source": "SEC Form N-PORT, Form N-CEN and risk/return summary data sets, SEC and Nasdaq Trader ticker files, GLEIF (drishti.py feed run funds)",
              "keys": {"fund": "TICKER", "fund-holder": "TICKER", "index-funds": "INDEX"}, "part-bytes": 1_200_000},
    # the genomics module's showcase (modules/genomics/docs/guide.md): synthetic, seeded, so the date is the generator's own
    "genomics-showcase": {"connector": "genomics-showcase-store", "captured": "2026-11-16",
                          "source": "the seeded synthetic generator (python3 tools/packgen/genomics_showcase/make.py)",
                          "keys": {"study-gene": "GENE", "study-sample": "SAMPLE_ID", "study-variant": "VARIANT_ID",
                                   "variant-call": "CALL_ID", "association": "ASSOC_ID", "cnv-segment": "SEGMENT_ID",
                                   "pathway-gene": "PATHWAY_GENE_ID"}},
    # the genomics module's own tables (variant, sample, gene) from its synthetic sample files, in the genomics lake the genomics loader fills
    "genomics": {"connector": "genomics-store", "lake": "genomicslake", "dates": "last", "captured": "2026-11-16",
                 "source": "the module's synthetic samples read by the genomics loader's readers (python3 tools/packgen/genomics_module/make.py)",
                 "keys": {"variant": "VARIANT_ID", "sample": "SAMPLE_ID", "gene": "GENE"}},
    "protein-engineering": {"connector": "protein-engineering-store", "captured": "2026-11-16",
                            "source": "the seeded synthetic generator (python3 tools/packgen/genomics_showcase/make.py)",
                            "keys": {"protein": "PROTEIN", "residue": "RESIDUE_ID", "residue-contact": "CONTACT_ID",
                                     "dms-score": "SCORE_ID", "design": "DESIGN_ID", "design-mutation": "DESIGN_MUTATION_ID",
                                     "design-round": "ROUND_ID"}},
    # the economy module's public-sector packs (docs/guides/WORLD_DATA_GUIDE.md): each read from its own lake (worldlake, newslake), where its
    # feed loads it daily; the demo lake build loads the snapshot there once (dates: last), the connectors read it as of the newest load
    "central-banks": {"connector": "centralbank-store", "lake": "worldlake", "dates": "last", "feed": "central-banks",
                      "source": "FRED (Federal Reserve Bank of St. Louis; needs DRISHTI_FRED_API_KEY) and the Bank of Canada Valet API (drishti.py feed run central-banks --snapshot)",
                      "keys": {"central-bank": "BANK_ID", "rate-series": "SERIES_ID"}},
    "national-statistics": {"connector": "statistics-store", "lake": "worldlake", "dates": "last", "feed": "national-statistics",
                            "source": "Statistics Canada Web Data Service (drishti.py feed run national-statistics --snapshot)",
                            "keys": {"statistics-series": "SERIES_ID"}},
    "oecd": {"connector": "oecd-store", "lake": "worldlake", "dates": "last", "feed": "oecd",
             "source": "OECD Data Explorer SDMX API (drishti.py feed run oecd --snapshot)",
             "keys": {"oecd-country": "COUNTRY_ID", "oecd-series": "SERIES_ID"}},
    "world-development": {"connector": "development-store", "lake": "worldlake", "dates": "last", "feed": "world-development",
                          "source": "World Bank World Development Indicators API (drishti.py feed run world-development --snapshot)",
                          "keys": {"development-profile": "PROFILE_ID", "development-series": "SERIES_ID"}},
    "gdelt-events": {"connector": "gdelt-store", "lake": "newslake", "dates": "last", "feed": "gdelt-events",
                     "source": "GDELT 1.0 daily event files (drishti.py feed run gdelt-events --snapshot)",
                     "keys": {"event-country": "EVENT_COUNTRY_ID"}},
    "official-news": {"connector": "officialnews-store", "lake": "newslake", "dates": "last", "feed": "official-news",
                      "source": "central banks' and regulators' press-release feeds (drishti.py feed run official-news --snapshot)",
                      "keys": {"release-source": "SOURCE_ID", "press-release": "RELEASE_ID"}},
    # the world-economy pack (internal demos only: it carries IMF data): macro-store and civic-store, the demo lake
    "world-economy": {"connector": "macro-store", "dates": "last", "strip-meta": True, "feed": "world-economy", "internal-only": True,
                      "connectors": {"jurisdiction": "civic-store", "party": "civic-store", "office-holder": "civic-store", "election": "civic-store",
                                     "region": "civic-store", "social-indicator": "civic-store"},
                      "source": "World Bank (WDI, WGI, country list), IMF World Economic Outlook, UNDP HDI, CIA World Factbook (drishti.py feed run world-economy --snapshot --internal-ok)",
                      "keys": {"economy": "ECONOMY_ID", "macro-indicator": "INDICATOR_ID", "economic-forecast": "FORECAST_ID", "labour-market": "LABOUR_ID",
                               "fiscal-position": "FISCAL_ID", "region-economy": "REGION_ID", "jurisdiction": "JURISDICTION_ID", "party": "PARTY_ID",
                               "office-holder": "OFFICE_ID", "election": "ELECTION_ID", "region": "REGION_ID", "social-indicator": "INDICATOR_ID"}},
    # the news-and-markets pack's lists of what the capture asks for (the twenty companies, the fifteen topics); its quotes and headlines are
    # captured by tools/testdata/capture_market.py
    "news-and-markets": {"connector": "news-store", "dates": "last", "strip-meta": True, "captured": "2026-11-27", "source": "the pack's own lists (modules/economy/packs/news-and-markets/README.md)",
                         "keys": {"listed-company": "COMPANY_ID", "news-topic": "TOPIC_ID"}},
}
#: a pack with "part-bytes" has each kind's snapshot cut into parts of about that many compressed bytes, so no member is large; a kind
#: in several parts is a tar of <kind>/<date>/snapshot.part-NN.jsonl.gz (<kind>.tar), one part the plain <kind>.<date>.jsonl.gz
#: the kinds computed since 2.9.2 instead of stored (docs/guides/bi/README.md): never written to the test data again
COMPUTED = {"us-equities": ["sic-sector"], "gleif": ["lei-group", "lei-country"]}
#: the documents a pack's samples/ keep as reference (README data structures, docs); the rest of each kind: the first REFERENCE_EACH
REFERENCE_IDS = {"us-equities": {"us-company": ["AAPL", "C", "F"], "index-membership": ["DJIA"], "filing-feed": ["8-K"]},
                 "gleif": {"lei-entity": ["HWUPKR0MPOU8FGXBT394"]},
                 "funds": {"fund": ["QQQ"], "fund-holder": ["AAPL"], "index-funds": ["NDX"]}}
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
                return _local_day(str(when))
    raise ValueError("no document says when its data was retrieved (PROVENANCE.RETRIEVED_AT)")


def _local_day(when: str) -> str:
    """A retrieval time's date on this machine's calendar (a time in UTC after local midnight is not a day "in the future", which the
    load rules refuse in a file's name); a plain date as it is."""
    from datetime import datetime
    try:
        t = datetime.fromisoformat(when.replace("Z", "+00:00"))
    except ValueError:
        return when[:10]
    return (t.astimezone() if t.tzinfo else t).date().isoformat()


def _gzip(text: str) -> bytes:
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, compresslevel=9, mtime=0) as fh:     # no name, no time: deterministic
        fh.write(text.encode("utf-8"))
    return buf.getvalue()


def _lines(kind_docs: dict[str, dict]) -> str:
    return "".join(json.dumps(d, ensure_ascii=False, separators=(",", ":")) + "\n" for d in kind_docs.values())


def _parts(kind_docs: dict[str, dict], part_bytes: int | None) -> list[str]:
    """The kind's JSON Lines cut into parts of about part_bytes compressed (whole documents, in order; deterministic); one without."""
    lines = [json.dumps(d, ensure_ascii=False, separators=(",", ":")) + "\n" for d in kind_docs.values()]
    whole = "".join(lines)
    n = max(1, -(-len(_gzip(whole)) // part_bytes)) if part_bytes else 1
    if n == 1:
        return [whole]
    target = len(whole.encode("utf-8")) / n
    out, cur, size = [], [], 0
    for line in lines:
        cur.append(line)
        size += len(line.encode("utf-8"))
        if size >= target and len(out) < n - 1:
            out.append("".join(cur))
            cur, size = [], 0
    if cur:
        out.append("".join(cur))
    return out


def files(pack: str, docs: dict[str, dict[str, dict]]) -> dict[str, bytes]:
    """{"<pack>/<kind>/<kind>.<date>.jsonl.gz": bytes} for every kind the pack keeps in the test data; a large kind in parts, as a tar
    "<pack>/<kind>/<kind>.tar" of <kind>/<date>/snapshot.part-01.jsonl.gz, part-02 ..."""
    when = PACKS[pack].get("captured") or captured(docs)     # a synthetic pack names its own date
    if PACKS[pack].get("strip-meta"):                        # the generators' record metadata (_meta) is not data: the lake holds the document
        docs = {k: {i: {f: v for f, v in d.items() if f != "_meta"} for i, d in kd.items()} for k, kd in docs.items()}
    out = {}
    for kind in PACKS[pack]["keys"]:
        parts = _parts(docs.get(kind, {}), PACKS[pack].get("part-bytes"))
        if len(parts) == 1:
            out[f"{pack}/{kind}/{FF.plain_name(kind, when)}"] = _gzip(parts[0])
        else:
            out[f"{pack}/{kind}/{FF.tar_name(kind)}"] = FF.tar_bytes(kind, {when: [(f"snapshot.part-{i:02d}.jsonl.gz", _gzip(text))
                                                                                  for i, text in enumerate(parts, 1)]})
    return out


def write(testdata: Path, pack: str, docs: dict[str, dict[str, dict]]) -> list[Path]:
    testdata = Path(testdata)
    want = files(pack, docs)
    for kind in PACKS[pack]["keys"]:
        for old in FF.files(testdata / pack / kind) if (testdata / pack / kind).is_dir() else []:
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


def _newest_all(testdata: Path, pack: str, kind: str) -> list:
    """The parts (feedfiles.Part) of a kind's newest snapshot: its one file, or its tar's members in order."""
    folder = Path(testdata) / pack / kind
    got = FF.read_table(folder) if folder.is_dir() else {}
    return got[max(got)] if got else []


def read(testdata: Path, pack: str) -> dict[str, dict[str, dict]]:
    out: dict[str, dict[str, dict]] = {}
    for kind, key in PACKS[pack]["keys"].items():
        fs = _newest_all(testdata, pack, kind)
        if not fs:
            continue
        out[kind] = {}
        for part in fs:
            out[kind].update({_id(d, key): d for d in (json.loads(x) for x in part.lines())})
    return out


def kind_docs(pack: str, kind: str, testdata: Path = TESTDATA) -> list[dict]:
    """One kind's documents from the newest snapshot (empty when there is none)."""
    return list(read(testdata, pack).get(kind, {}).values()) if _newest_all(testdata, pack, kind) else []


def differences(testdata: Path, pack: str, docs: dict[str, dict[str, dict]]) -> list[str]:
    """The snapshot files that are missing or hold other documents (compared uncompressed: zlib versions may differ), and stale ones."""
    testdata = Path(testdata)
    bad = []
    want = files(pack, docs)
    with tempfile.TemporaryDirectory(prefix="drishti-publicsnap-") as tmp:
        for rel, data in want.items():
            f = testdata / rel
            if not f.is_file():
                bad.append(f"data/testdata/{rel} (missing)")
                continue
            w = Path(tmp) / rel.replace("/", "_")
            w.write_bytes(data)
            if list(FF.contents(f).values()) != list(FF.contents(w).values()):   # compared uncompressed: zlib versions may differ
                bad.append(f"data/testdata/{rel}")
    for kind in PACKS[pack]["keys"]:
        for f in FF.files(testdata / pack / kind) if (testdata / pack / kind).is_dir() else []:
            if str(f.relative_to(testdata)).replace("\\", "/") not in want:
                bad.append(f"data/testdata/{f.relative_to(testdata)} (not written by the generator)")
    listed = {(d["pack"], d["kind"], name) for d in _manifest(testdata).get("datasets") or [] for name in (d.get("files") or [])}
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
            fs = _newest_all(testdata, pack, kind)
            if not fs:
                continue
            n = sum(1 for part in fs for _ in part.lines())
            names = sorted({part.path.name for part in fs})
            entry = {"folder": f"{pack}/{kind}", "pack": pack, "kind": kind, "connector": (spec.get("connectors") or {}).get(kind, spec["connector"]),
                     "key": key, "snapshot": True, "captured": fs[0].date, "source": spec["source"], "documents": n, "files": names,
                     "bytes": sum((Path(testdata) / pack / kind / x).stat().st_size for x in names)}
            for extra in ("lake", "dates"):               # where the demo build writes it (its own lake), and for how many days
                if spec.get(extra):
                    entry[extra] = spec[extra]
            if spec.get("internal-only"):
                entry["internal-only"] = True
            out.append(entry)
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
    # the news-and-markets pack shares its folder with capture_market.py (quote, news): only this module's kinds are replaced
    keep = [ds for ds in m.get("datasets") or [] if ds.get("pack") not in PACKS
            or (ds["pack"] == "news-and-markets" and ds.get("kind") not in PACKS["news-and-markets"]["keys"])]
    m["datasets"] = keep + datasets(testdata)
    path.write_text(head + yaml.safe_dump(m, sort_keys=False, width=140), encoding="utf-8")
    update_readme(testdata)


#: how each snapshot is refreshed (the last column of data/testdata/README.md's table), for the packs that no feed names in PACKS
REFRESH = {"us-equities": "python3 tools/drishti.py feed run us-equities --snapshot", "gleif": "python3 tools/drishti.py feed run gleif --snapshot",
           "funds": "python3 tools/drishti.py feed run funds --snapshot",
           "genomics-showcase": "python3 tools/packgen/genomics_showcase/make.py", "genomics": "python3 tools/packgen/genomics_module/make.py", "protein-engineering": "python3 tools/packgen/genomics_showcase/make.py",
           "news-and-markets": "python3 tools/drishti.py testdata capture-market (quote, news); python3 modules/economy/packs/news-and-markets/tools/make_samples.py (lists)"}
TABLE_BEGIN, TABLE_END = "<!-- BEGIN public-data table (tools/testdata/publicsnap.py) -->", "<!-- END public-data table -->"


def refresh_command(pack: str) -> str:
    spec = PACKS.get(pack, {})
    if spec.get("feed"):
        return f"python3 tools/drishti.py feed run {spec['feed']} --snapshot" + (" --internal-ok" if spec.get("internal-only") else "")
    return REFRESH.get(pack, "")


def readme_table(testdata: Path) -> str:
    """The one table of data/testdata/README.md: every committed snapshot (real or seeded, dated) by pack and kind."""
    rows = ["| Pack | Kind | Files | Rows | Source | Date | Refresh |", "|---|---|---|---|---|---|---|"]
    for ds in _manifest(testdata).get("datasets") or []:
        if not ds.get("snapshot"):
            continue
        files = ", ".join(f"`{ds['folder']}/{f}`" for f in ds.get("files") or [])
        src = str(ds.get("source") or "").replace("|", "/")
        note = " (IMF data: internal demos only)" if ds.get("internal-only") else ""
        rows.append(f"| `{ds['pack']}` | `{ds['kind']}` | {files} | {ds.get('documents', 0):,} | {src}{note} | {str(ds.get('captured'))[:10]} "
                    f"| `{refresh_command(ds['pack'])}` |")
    return "\n".join(rows)


def update_readme(testdata: Path) -> None:
    """data/testdata/README.md's table between its markers, from the manifest (kept current by every write of a snapshot)."""
    path = Path(testdata) / "README.md"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    if TABLE_BEGIN not in text:
        return
    head, rest = text.split(TABLE_BEGIN, 1)
    tail = rest.split(TABLE_END, 1)[1]
    path.write_text(f"{head}{TABLE_BEGIN}\n{readme_table(testdata)}\n{TABLE_END}{tail}", encoding="utf-8")


def default_testdata(pack_dir: Path, committed: Path) -> Path:
    """Where a generator writes the snapshot: data/testdata for the committed pack; beside a widened build's pack folder otherwise
    (<data>/datafeeds/sources/<pack>/packs/<pack> -> <data>/datafeeds/sources/<pack>/testdata), never into the committed test data."""
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
    print(f"wrote {rel(Path(pack_dir))} (reference samples, README) and {rel(Path(testdata))}/{pack}: "
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
