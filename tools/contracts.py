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

"""Data contracts of every command (mnemonic) kind: modules/<module>/packs/<pack>/contracts/<kind>.contract.yaml (docs/contracts/README.md,
section 16; the format: tools/contracts.schema.json).

    python3 tools/contracts.py list                         every mnemonic, its contract, class and status
    python3 tools/contracts.py show NSET                    one contract (by mnemonic or kind)
    python3 tools/contracts.py check                        schema, one contract per mnemonic, each still matching its pack and data
    python3 tools/contracts.py docs [--check]               write (or check) docs/contracts/packs/<pack>.md and its README.md
    python3 tools/contracts.py bootstrap KIND [--write]     draft a contract from the pack and its data (--all: every missing one)

The same commands are `drishti.py contract ...`. A draft is read from pack.yaml (mnemonics, graph, connectors and their layout), the
samples, data/testdata (testdata.yaml), config/about.yaml (glossary: descriptions, units, values) and the Sutras (labels); the product
owner's decisions it cannot read from the data (class, retirement, computed figures) come from tools/contracts.policy.yaml. What it
infers rather than reads (required from coverage, enums from few values, number types from names) is listed under `review`.
"""
from __future__ import annotations

import argparse
import functools
import importlib.util
import json
import re
import sys
from collections import Counter, OrderedDict
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
PACKS = ROOT / "config" / "packs"
TESTDATA = ROOT / "data" / "testdata"
POLICY = ROOT / "tools" / "contracts.policy.yaml"
#: plugins whose connector computes its kinds (a binding hint says computed: true; never bound by a site)
COMPUTED_PLUGINS = {"derived", "reactive", "var-positions", "var-system-baskets"}
SCHEMA = ROOT / "tools" / "contracts.schema.json"
DOCS = ROOT / "docs" / "contracts" / "packs"
VERSION = "1.0"
GENERATOR = "tools/contracts.py docs (drishti.py contract docs)"

if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core import pack_paths as PP  # noqa: E402  (the one rule for where a pack's folder is)

_spec = importlib.util.spec_from_file_location("drishti_pack_readme", ROOT / "tools" / "packgen" / "common" / "readme.py")
R = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(R)

MAX_TESTDATA = 2000       # test-data documents read per kind (the first day's files, then the last day's)
MAX_DEPTH = 3             # nested levels described
MAX_ENUM = 8              # a text field with at most this many values (each seen twice or more) is drafted as an enum
ENGINE_COLUMNS = {"VAR_99", "ES_975", "COMPONENT_VAR", "EAD", "PFE_PEAK", "EE_PEAK", "UTILISATION", "NET_MTM", "EEPE", "EPE"}
RATE_WORDS = {"UTILISATION", "UTILIZATION", "RATE", "VOL", "YIELD", "SPREAD", "HAIRCUT", "MULTIPLIER", "ALPHA", "CONFIDENCE", "RHO",
              "CORRELATION", "WEIGHT", "PCT", "SHARE", "RATIO", "GROWTH", "INFLATION", "AVERAGE", "USE", "BETA", "PD", "LGD",
              "RECOVERY", "MARGIN", "COUPON", "STRIKE"}
AMOUNT_WORDS = {"MTM", "NOTIONAL", "PNL", "VAR", "ES", "AMOUNT", "VALUE", "EXPOSURE", "BALANCE", "EAD", "PFE", "EE", "EPE", "EEPE",
                "ENE", "CVA", "DVA", "FVA", "KVA", "COLLATERAL", "CHARGE", "PV", "IM", "LIMIT", "USED", "HELD", "POSTED", "RECEIVED",
                "NET", "TOTAL", "COST", "RWA", "REVENUE", "INCOME", "PRICE", "CS01", "DV01", "ADD", "USD", "BN"}
NUMBER = {"integer", "amount", "rate"}


# -- the packs ----------------------------------------------------------------------------------------------------------------

_LOADER = getattr(yaml, "CSafeLoader", yaml.SafeLoader)


@functools.lru_cache(maxsize=256)
def _parsed(path: str, mtime: int):
    return yaml.load(Path(path).read_text(encoding="utf-8"), Loader=_LOADER)


def _yaml(path: Path):
    """A YAML file, parsed once while it is unchanged (pack.yaml and testdata.yaml are read for every kind). Read only: shared."""
    path = Path(path)
    return _parsed(str(path.resolve()), path.stat().st_mtime_ns) if path.is_file() else None


def _packlake():
    import importlib
    if str(Path(__file__).resolve().parent) not in sys.path:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
    return importlib.import_module("packlake")


def manifests(packs_dir: Path = PACKS) -> "OrderedDict[str, dict]":
    """Pack name to its manifest (pack.yaml), sorted by name."""
    out = OrderedDict()
    for name, d in PP.pack_dirs(packs_dir).items():
        out[name] = _packlake().view(_yaml(d / "pack.yaml") or {})   # a template in the lake form read with its pack's lake: entries
    return out


def mnemonics(packs_dir: Path = PACKS) -> list[dict]:
    """Every command of every pack: {pack, code, kind, label}, in pack then manifest order."""
    out = []
    for pack, m in manifests(packs_dir).items():
        for code, v in (m.get("mnemonics") or {}).items():
            if isinstance(v, dict) and v.get("kind"):
                out.append({"pack": pack, "code": code, "kind": v["kind"], "label": v.get("label") or v["kind"]})
    return out


def contract_file(packs_dir: Path, pack: str, kind: str) -> Path:
    return PP.pack_dir(pack, packs_dir) / "contracts" / f"{kind}.contract.yaml"


def find(name: str, packs_dir: Path = PACKS) -> dict:
    """The command named by a mnemonic or a kind."""
    for c in mnemonics(packs_dir):
        if name in (c["code"], c["kind"]) or name.upper() == c["code"]:
            return c
    raise SystemExit(f"contracts: no command or kind '{name}' in {packs_dir}")


def policy() -> dict:
    return _yaml(POLICY) or {}


def _connector(all_m: dict, pack: str, name: str | None) -> dict:
    """A connector's definition: the pack's own first, then any pack's (a route may name a connector another pack brings)."""
    if not name:
        return {}
    own = ((all_m.get(pack) or {}).get("connectors") or {}).get(name)
    if isinstance(own, dict):
        return own
    for m in all_m.values():
        c = (m.get("connectors") or {}).get(name)
        if isinstance(c, dict):
            return c
    return {}


def default_connector(c: dict, packs_dir: Path = PACKS, testdata: Path = TESTDATA) -> str | None:
    """The connector a kind is served by in the product's defaults (the pack's route, else its test data's connector): what a site binds
    unless it says otherwise (config/bindings.yaml). Contracts no longer name it (KERNEL_AND_MODULES.md section 5.2)."""
    route = ((manifests(packs_dir).get(c.get("pack")) or {}).get("routes") or {}).get(c.get("kind"))
    if route:
        return route
    sets = testdata_sets(c.get("pack"), c.get("kind"), testdata)
    return sets[0].get("connector") if sets else None


def default_plugin(c: dict, packs_dir: Path = PACKS) -> str | None:
    """The plugin of {@link default_connector}."""
    return _connector(manifests(packs_dir), c.get("pack"), default_connector(c, packs_dir)).get("plugin")


def graph_links(all_m: dict, pack: str, packs_dir: Path) -> dict[str, str]:
    """Field name to the kind it links to: every pack's graph fields, then the pack's ancestors' and its own over them."""
    links: dict[str, str] = {}
    for m in all_m.values():
        links.update({f: v["kind"] for f, v in ((m.get("graph") or {}).get("fields") or {}).items() if isinstance(v, dict) and v.get("kind")})
    own = all_m.get(pack) or {}
    for m in R._ancestors(Path(packs_dir), own) + [own]:
        links.update({f: v["kind"] for f, v in ((m.get("graph") or {}).get("fields") or {}).items() if isinstance(v, dict) and v.get("kind")})
    return links


def graph_labels(all_m: dict) -> dict[str, str]:
    """Field name to its label in any pack's graph (Agreement, Netting set): what a link field is, in words."""
    out: dict[str, str] = {}
    for m in all_m.values():
        out.update({f: str(v["label"]) for f, v in ((m.get("graph") or {}).get("fields") or {}).items() if isinstance(v, dict) and v.get("label")})
    return out


def id_patterns(all_m: dict) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for m in all_m.values():
        for p in (m.get("graph") or {}).get("id-patterns") or []:
            if isinstance(p, dict) and p.get("kind") and p.get("pattern"):
                out.setdefault(p["kind"], []).append(p["pattern"])
    return out


# -- the data -----------------------------------------------------------------------------------------------------------------

def testdata_sets(pack: str, kind: str, testdata: Path = TESTDATA) -> list[dict]:
    y = _yaml(Path(testdata) / "testdata.yaml") or {}
    return [d for d in y.get("datasets") or [] if d.get("pack") == pack and d.get("kind") == kind
            and not d.get("native")]          # a mocked source system's native style is not the canonical shape the contract describes


def _dataset_files(d: dict, testdata: Path) -> list:
    """A dataset's files to read (tools/lake/feedfiles.py parts: a dated file, or a tar's member): the first day's and the last day's
    (snapshot then changes), or a dated dataset's newest date first (the data as it is today), or a snapshot's newest date."""
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    import feedfiles
    folder = Path(testdata) / d["folder"]
    got = feedfiles.read_table(folder) if folder.is_dir() else {}
    if not got:
        return []
    if d.get("days"):
        days = [x["day"] for x in (d["days"] if len(d["days"]) < 2 else [d["days"][0], d["days"][-1]])]
        return [p for day in days for p in got.get(day, [])]
    newest = max(got)
    if d.get("snapshot"):
        return got[newest]
    return [p for day in [newest] + [x for x in got if x != newest] for p in got[day]]


def read_documents(pack_dir: Path, kind: str, testdata: Path = TESTDATA) -> tuple[list[dict], list[dict], list[str]]:
    """(sample documents, test-data documents, the test-data files read) of one kind; at most MAX_TESTDATA test-data documents."""
    samples = []
    for f in sorted(pack_dir.glob(f"samples/{kind}.jsonl")) + sorted(pack_dir.glob(f"samples/{kind}.part-*.jsonl")):
        samples += [json.loads(line) for line in f.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not samples:
        samples = [json.loads(f.read_text(encoding="utf-8")) for f in sorted(pack_dir.glob(f"samples/{kind}/*.json"))]
    sets = [d for d in testdata_sets(pack_dir.name, kind, testdata) if d.get("folder")]
    docs, read = [], []
    for d in sets:
        budget = MAX_TESTDATA // len(sets)
        got = 0
        for part in _dataset_files(d, testdata):
            if got >= budget:
                continue
            f = part.path
            read.append((str(f.relative_to(ROOT)) if f.is_relative_to(ROOT) else str(f)) + (f"!/{part.member}" if part.member else ""))
            for line in part.lines():
                if got >= budget:
                    break
                docs.append(json.loads(line))
                got += 1
    return samples, docs, read


def _empty(v) -> bool:
    return v is None or v == ""



def _catalog(samples: Path) -> list:
    """A samples folder's catalogue: catalog.jsonl (one entry a line, 2.9.4), else the catalog.json array of earlier releases."""
    lines = samples / "catalog.jsonl"
    if lines.is_file():
        return [json.loads(t) for t in lines.read_text(encoding="utf-8").splitlines() if t.strip()]
    old = samples / "catalog.json"
    return json.loads(old.read_text(encoding="utf-8")) if old.is_file() else []

class Stat:
    """What one field held over the documents read: how often it was non-empty, its JSON types, a few values."""

    def __init__(self):
        self.present = 0
        self.types: Counter = Counter()
        self.values: Counter = Counter()
        self.overflow = False
        self.example = None
        self.lengths: set[int] = set()
        self.min = self.max = None
        self.floats = False

    def add(self, v) -> None:
        if _empty(v):
            return
        self.present += 1
        self.types[R._type(v)] += 1
        if isinstance(v, str):
            if not self.overflow:
                self.values[v] += 1
                self.overflow = len(self.values) > 50
            if self.example is None:
                self.example = v if len(v) <= 60 else v[:59] + "…"
        elif isinstance(v, (int, float)) and not isinstance(v, bool):
            self.floats |= isinstance(v, float) and not float(v).is_integer()
            self.min = v if self.min is None else min(self.min, v)
            self.max = v if self.max is None else max(self.max, v)
            if self.example is None:
                self.example = v
        elif isinstance(v, bool):
            self.values[v] += 1
            if self.example is None:
                self.example = v
        elif isinstance(v, list) and v and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v):
            self.lengths.add(len(v))


class Shape:
    """Fields seen over a kind's documents: path ("" for the top, "TRADES[]", "KYC", "LEGS[].INDEX") to field to Stat, and how
    many objects each path held (the denominator of a field's coverage)."""

    def __init__(self):
        self.n = 0
        self.fields: "OrderedDict[str, OrderedDict[str, Stat]]" = OrderedDict()
        self.items: Counter = Counter()

    def add(self, doc: dict) -> None:
        self.n += 1
        self._walk(doc, "", 0)

    def _walk(self, obj: dict, path: str, depth: int) -> None:
        self.items[path] += 1
        into = self.fields.setdefault(path, OrderedDict())
        for k, v in obj.items():
            if k == "_meta" and not path:
                continue
            into.setdefault(k, Stat()).add(v)
            if depth >= MAX_DEPTH:
                continue
            sub = (path + "." if path else "") + k
            if isinstance(v, list) and v and all(isinstance(x, dict) for x in v):
                for item in v[:50]:
                    self._walk(item, sub + "[]", depth + 1)
            elif isinstance(v, dict) and v:
                self._walk(v, sub, depth + 1)


def shape_of(docs: list[dict]) -> Shape:
    s = Shape()
    for d in docs:
        s.add(d)
    return s


def _plain(path: str) -> str:
    """``LEGS[].INDEX`` as the glossaries write it: ``LEGS.INDEX``."""
    return path.replace("[]", "")


def glossary(about: dict, kind: str) -> dict[str, dict]:
    """Field path (``A.B``) to {means, unit, values} from a pack's about.yaml, vocabulary terms resolved (``use:``)."""
    vocab = about.get("vocabulary") or {}
    out = {}
    for f, g in (((about.get("kinds") or {}).get(kind) or {}).get("glossary") or {}).items():
        if not isinstance(g, dict):
            continue
        if g.get("use"):
            g = {**(vocab.get(g["use"]) or {}), **{k: v for k, v in g.items() if k != "use"}}
        out[str(f)] = {"means": " ".join(str(g.get("means") or g.get("term") or "").split()), "unit": g.get("unit"),
                       "values": list((g.get("values") or {}).keys()) if isinstance(g.get("values"), dict) else None}
    return out


def _words(name: str) -> set[str]:
    return set(name.upper().split("_"))


def number_type(name: str, st: Stat) -> tuple[str, bool]:
    """(integer | amount | rate, whether the field's name decided it)."""
    w = _words(name)
    if w & RATE_WORDS:
        return "rate", True
    if w & AMOUNT_WORDS:
        return "amount", True
    return ("amount" if st.floats or "number" in st.types else "integer"), False


def contract_type(name: str, st: Stat, id_like: bool) -> tuple[str, bool, list[str]]:
    """(contract type, decided by name, the JSON types seen when they disagree)."""
    ts = {t for t in st.types if t != "null"}
    if not ts:
        return "unknown", False, []
    if len(ts) > 1 and "list" in ts:
        ts.discard("list")
    if ts <= {"integer", "number"}:
        t, by_name = number_type(name, st)
        return t, by_name, []
    if ts <= {"date"}:
        return "date", False, []
    if ts <= {"timestamp", "date"}:
        return "timestamp", False, []
    if ts == {"boolean"}:
        return "boolean", False, []
    if ts == {"object"}:
        return "record", False, []
    if ts == {"list of objects"}:
        return "record list", False, []
    if ts <= {"list of integers", "list of numbers"}:
        return "vector", False, []
    if ts == {"list of strings"}:
        return ("id list" if name.endswith("IDS") or id_like else "text list"), False, []
    if ts == {"list of dates"}:
        return "date list", False, []
    if all(t.startswith("list") for t in ts):
        return "list", False, sorted(ts) if len(ts) > 1 else []
    if ts <= {"string", "date", "timestamp"}:
        return ("id" if id_like else "text"), False, sorted(ts) if len(ts) > 1 else []
    return "text", False, sorted(ts)


def _matches(values, patterns: list[str]) -> bool:
    vals = [v for v in values if isinstance(v, str)]
    return bool(vals) and bool(patterns) and all(any(re.search(p, v) for p in patterns) for v in vals)


# -- a draft ------------------------------------------------------------------------------------------------------------------

class Draft:
    """Builds one contract from a pack and its data: Draft(kind).build() is the contract as a dict."""

    def __init__(self, kind: str, packs_dir: Path = PACKS, testdata: Path = TESTDATA, pol: dict | None = None):
        self.cmd = find(kind, packs_dir)
        self.kind, self.pack = self.cmd["kind"], self.cmd["pack"]
        self.packs_dir, self.testdata = Path(packs_dir), Path(testdata)
        self.pack_dir = PP.pack_dir(self.pack, self.packs_dir)
        self.all_m = manifests(packs_dir)
        self.m = self.all_m[self.pack]
        self.pol = policy() if pol is None else pol
        self.retiring = (self.pol.get("retiring") or {}).get(self.kind)
        self.split = (self.pol.get("split") or {}).get(self.kind)
        self.route = (self.m.get("routes") or {}).get(self.kind)
        self.sets = testdata_sets(self.pack, self.kind, self.testdata)
        if not self.route and self.sets:
            self.route = self.sets[0].get("connector")
        self.conn = _connector(self.all_m, self.pack, self.route)
        self.links = graph_links(self.all_m, self.pack, self.packs_dir)
        self.patterns = id_patterns(self.all_m)
        self.link_labels = graph_labels(self.all_m)
        self.about = _yaml(self.pack_dir / (self.m.get("about") or "config/about.yaml")) or {}
        self.gloss = glossary(self.about, self.kind)
        F = R._Files(self.pack_dir, None)
        self.sutras = [s for s in R._sutras(F) if s["kind"] == self.kind]
        self.labels = {}
        for s in self.sutras:
            R._labels(s["doc"], "", self.labels)
        self.labels = {_plain(k): v for k, v in self.labels.items()}
        self.samples, self.tdocs, self.read = read_documents(self.pack_dir, self.kind, self.testdata)
        self.shape = shape_of(self.samples + self.tdocs)
        self.review: list[str] = []
        self.notes: list[str] = []
        self.by_name: list[str] = []
        self.enums_inferred: list[str] = []
        self.no_desc: list[str] = []
        self.no_unit: list[str] = []

    # -- what the kind is
    def klass(self) -> str:
        if self.retiring or self.derived_spec():
            return "computed"
        for c, kinds in (self.pol.get("classes") or {}).items():
            if self.kind in (kinds or []):
                return c
        return "fundamental"

    def derived_spec(self) -> dict | None:
        if self.conn.get("plugin") != "derived":
            return None
        spec = (self.conn.get("settings") or {}).get(self.kind)
        return spec if isinstance(spec, dict) else None

    def reactive_spec(self) -> dict | None:
        if self.conn.get("plugin") != "reactive":
            return None
        spec = (self.conn.get("kinds") or {}).get(self.kind) if isinstance(self.conn.get("kinds"), dict) else None
        return spec if isinstance(spec, dict) else {}

    def figures(self) -> tuple[set[str], dict | None]:
        """The fields that are computed figures (paths ``A`` or ``A[].B``) and the policy entry they come from."""
        entry = self.retiring or self.split
        if not entry:
            return set(), None
        figs = entry.get("figures") or []
        if figs == "from-derived-connector":
            spec = self.derived_spec() or {}
            figs = list((spec.get("fields") or {}).keys())
        return set(figs), entry

    def key(self) -> list[str]:
        for d in self.sets:
            if d.get("key"):
                return [d["key"]]
        spec = self.derived_spec()
        if spec and spec.get("id-field"):
            return [spec["id-field"]]
        catalog = _catalog(self.pack_dir / "samples")
        eid = next((str(e.get("id")) for e in catalog if e.get("kind") == self.kind), None)
        first = (self.samples or [{}])[0]
        hit = next((k for k, v in first.items() if eid is not None and str(v) == eid), None)
        if hit:
            self.review.append(f"key {hit} is the field holding the sample catalogue's id ({eid}): confirm it is the business key.")
            return [hit]
        layout = ((self.conn.get("settings") or {}).get("layout") or {}).get(self.kind) or {}
        if layout.get("columns"):
            self.review.append(f"key {layout['columns'][0]} is the first column of the connector's layout: confirm it is the business key.")
            return [layout["columns"][0]]
        if self.reactive_spec() is not None:
            asked = next((r.get("kind") for r in self.conn.get("reads") or [] if isinstance(r, dict) and r.get("id") == "{id}"), None)
            self.notes.append("No stored key: an answer's id is the question asked (the id in the request)"
                              + (f", the id of the {asked} it reads." if asked else "."))
            return []
        self.review.append("no key could be read from the test data, the catalogue or the layout: name the business key.")
        return []

    def source(self) -> str:
        if self.derived_spec() is not None:
            return f"computed by Drishti: the derived connector {self.route} over the kind {self.derived_spec().get('from')}"
        if self.reactive_spec() is not None:
            eng = self.conn.get("engine") or {}
            return f"computed by Drishti: the reactive connector {self.route} ({eng.get('type', '')} engine)".replace(" ( engine)", "")
        for d in self.sets:
            if d.get("source"):
                return f"public data: {d['source']}"
        metas = Counter(str((d.get("_meta") or {}).get("source")) for d in self.samples + self.tdocs if (d.get("_meta") or {}).get("source"))
        intro = R.find_intro(self.pack)
        isrc = ((R.split(intro.read_text(encoding="utf-8"))[0] or {}).get("sources") or []) if intro else []
        public = bool(isrc) and all("proprietary" not in str(s.get("licence", "")).lower() for s in isrc)
        named = [s for s in isrc if re.search(rf"(^|[\s,]){re.escape(self.kind)}([\s,]|$)", str(s.get("used-for", "")))]
        if metas:
            name = ", ".join(n for n, k in metas.most_common() if k * 20 >= sum(metas.values()))
            if public:
                return f"public data: {name}"
            self.review.append(f"source '{name}' is the test data's _meta.source (synthetic): name the production system that delivers it.")
            if named:
                self.review.append(f"the pack also names '{named[0].get('name')}' for this kind ({named[0].get('used-for')}): state when "
                                   f"each source applies.")
            return name
        if named:
            s = named[0]
            return ("" if "proprietary" in str(s.get("licence", "")).lower() else "public data: ") + str(s.get("name"))
        if isrc:
            self.review.append("source is the pack's first listed source (its README): confirm.")
            return str(isrc[0].get("name"))
        self.review.append("no source could be read: name the upstream system.")
        return "to be confirmed"

    def dated_by(self) -> str:
        if self.derived_spec() is not None or self.reactive_spec() is not None:
            return "on request"
        for d in self.sets:
            if d.get("dated-by"):
                return d["dated-by"]
            if d.get("days"):
                self.notes.append("Dated by the lake's business_date partition: `drishti.py lake build` writes each test-data file's day "
                                  "into it; the documents carry no date field of their own.")
                return "business_date"
        return "snapshot"

    def cadence(self, dated: str) -> dict:
        plugin = self.conn.get("plugin")
        if self.derived_spec() is not None:
            every = (self.conn.get("settings") or {}).get("refresh")
            return {"frequency": "on change", "due": f"recomputed from its source kind (connector refresh {every})" if every else "on change"}
        if self.reactive_spec() is not None:
            return {"frequency": "on change", "due": "computed on request and on its triggers"}
        if plugin in ("news", "yfinance", "feed", "rest"):
            self.review.append(f"read live through the {plugin} connector when it is on: confirm the cadence (intraday, on demand).")
            return {"frequency": "intraday", "due": "on demand, when the connector is on"}
        if dated != "snapshot":
            self.review.append("due time of each business date is not known: state it (for example, by 06:00 New York the next business day).")
            return {"frequency": "daily", "due": "to be confirmed"}
        script = ROOT / "tools" / "feeds" / "sources" / (self.pack.replace("-", "_") + ".py")
        if script.is_file():
            self.review.append(f"a snapshot refreshed when its data feed runs ({script.relative_to(ROOT)}; drishti.py feed run): confirm the refresh cadence.")
        return {"frequency": "static", "due": "to be confirmed"}

    def binding_hint(self) -> dict:
        """What a site usually binds (KERNEL_AND_MODULES.md section 5.2): the lake table, partitions, promoted columns; never a connector."""
        s = self.conn.get("settings") or {}
        out = {}
        if self.conn.get("plugin") in COMPUTED_PLUGINS:
            out["computed"] = True
        if self.conn.get("plugin") == "delta":
            table = (s.get("table") or {}).get(self.kind) if isinstance(s.get("table"), dict) else s.get(f"table.{self.kind}")
            out["lake-table"] = f"{s.get('domain', '')}.{table or self.kind}"
            layout = (s.get("layout") or {}).get(self.kind) or {}
            if layout.get("columns"):
                out["columns"] = list(layout["columns"])
            if layout.get("vectors"):
                out["vectors"] = list(layout["vectors"])
        others = sorted({d.get("connector") for d in self.sets if d.get("connector") and d.get("connector") != self.route})
        if others:
            self.notes.append(f"Several source systems: the connector {self.route} and " + ", ".join(others) + " each serve this kind "
                              "from a table of their own with the same layout; a site binds them as one union (config/bindings.yaml).")
        parts = [p for d in self.sets for p in d.get("partition-by") or []]
        if parts:
            out["partition"] = sorted(set(parts), key=parts.index)
        td = [("data/testdata/" + d["folder"]) if d.get("folder") else d.get("samples") for d in self.sets]
        if td:
            out["test-data"] = [t for t in td if t]
        if self.conn.get("plugin") == "unknown" or not self.conn:
            self.review.append("no connector serves this kind in pack.yaml (routes): name where it is read from.")
        return out

    # -- fields
    def field(self, path: str, name: str, st: Stat | None, n_items: int, figs: set[str], entry: dict | None, key: list[str]) -> dict:
        full = f"{path}.{name}" if path else name
        plain = _plain(full)
        g = self.gloss.get(plain) or {}
        link = self.links.get(name)
        link_ok = link and (link != self.kind or name in key)
        id_like = name in key or bool(link_ok) or name.endswith("_ID") or name == "ID" or (
            st is not None and _matches(st.values, [p for ps in self.patterns.values() for p in ps if p.startswith("^") and p.endswith("-")]))
        if st is None:
            t, by_name, mixed = "unknown", False, []
        else:
            t, by_name, mixed = contract_type(name, st, id_like)
        f = {"name": name, "type": t}
        figure = full in figs or (not path and name in figs)
        if figure:
            f["presence"] = "derived"
        elif st is None:
            f["presence"] = "optional"
        else:
            f["presence"] = "required" if st.present == n_items and n_items else "optional"
        if g.get("unit"):
            f["unit"] = str(g["unit"])
        desc = g.get("means") or self.labels.get(plain) or ""
        if not desc and name in key:
            desc = "The record's id (the business key)"
        if not desc and link_ok:
            desc = f"{self.link_labels.get(name, link)}: the {link} it refers to"
        if not desc and t in ("record", "record list"):
            desc = f"Nested: see nested {full}{'[]' if t == 'record list' else ''}"
        if desc:
            f["description"] = desc
        else:
            self.no_desc.append(full)
        if st is not None and st.example is not None and t not in ("record", "record list", "vector", "list", "text list", "id list", "date list"):
            f["example"] = st.example
        if g.get("values"):
            f["enum"] = list(g["values"])
            extra = sorted(str(v) for v in st.values if v not in f["enum"]) if st is not None and not st.overflow else []
            if extra:
                f["enum"] += extra
                self.review.append(f"{full}: the data holds {', '.join(extra)}, which the glossary's values ({', '.join(g['values'])}) "
                                   f"do not describe: correct the data or the glossary.")
        elif st is not None and t == "text" and not st.overflow and 1 < len(st.values) <= MAX_ENUM and st.present >= 12 \
                and min(st.values.values()) >= 2 and len(st.values) * 2 < st.present:
            f["enum"] = sorted(st.values)
            self.enums_inferred.append(full)
        if name in key and not path and self.patterns.get(self.kind) and st is not None and not _matches(st.values, self.patterns[self.kind]):
            odd = next((v for v in st.values if isinstance(v, str) and not any(re.search(p, v) for p in self.patterns[self.kind])), None)
            if odd is not None:
                self.review.append(f"the id pattern {', '.join(self.patterns[self.kind])} does not match every {name} in the data (for "
                                   f"example {odd}): widen the pattern or state the id rule.")
        if name in key and self.patterns.get(self.kind) and st is not None and _matches(st.values, self.patterns[self.kind]):
            f["pattern"] = "|".join(self.patterns[self.kind])
        elif link_ok and len(self.patterns.get(link) or []) == 1 and st is not None and _matches(st.values, self.patterns[link]):
            f["pattern"] = self.patterns[link][0]
        if link_ok:
            f["link"] = link
        if figure and entry:
            if self.split:
                f["retiring-in"] = str(entry["release"])
                f["note"] = f"computed by {entry['measure']} from {entry['release']}; removed from the stored record then"
            else:
                f["note"] = f"computed by {entry['measure']}; removed when retired"
        if st is not None and t == "vector" and len(st.lengths) == 1:
            f["note"] = (f.get("note", "") + "; " if f.get("note") else "") + f"{next(iter(st.lengths))} values in the test data"
        if by_name and t in NUMBER and not g.get("unit"):
            self.by_name.append(f"{full} {t}")
        if t == "amount" and not g.get("unit"):
            self.no_unit.append(full)
        if mixed:
            self.review.append(f"{full} holds values of several types ({', '.join(mixed)}): state its one type.")
        if not figure and name in ENGINE_COLUMNS and not any(name in r for r in (self.pol.get("review") or {}).get(self.kind) or []):
            self.review.append(f"{full} is a column a compute engine produces (docs/contracts/README.md section 14) on a record that keeps "
                               f"it: confirm whether it is computed and removed.")
        return f

    def fields_from_data(self, figs, entry, key) -> tuple[list[dict], dict]:
        top = [self.field("", n, st, self.shape.items[""], figs, entry, key) for n, st in self.shape.fields.get("", {}).items()]
        nested = OrderedDict()
        for path, fields in self.shape.fields.items():
            if path:
                nested[path] = [self.field(path, n, st, self.shape.items[path], figs, entry, key) for n, st in fields.items()]
        return top, nested

    def fields_from_derived(self, spec: dict, figs, entry, key) -> tuple[list[dict], dict]:
        """A derived connector's kind: its id field, the members, the aggregated fields and the rows, read from its settings."""
        src = spec.get("from")
        agg = {"count": "integer", "sum": "amount", "distinct": "text list", "max": "unknown", "min": "unknown", "avg": "number", "median": "number",
               "first": "unknown"}
        also = ", ".join(f"{v}" for v in (spec.get("also-group-by") or {}).values())
        top = [{"name": spec["id-field"], "type": "id", "presence": "required", "description": f"The value the {src} records are grouped by "
                f"({spec.get('group-by')}{'; each also joins ' + also if also else ''})",
                **({"link": self.links[spec["id-field"]]} if self.links.get(spec["id-field"]) else {})}]
        if spec.get("members"):
            top.append({"name": spec["members"], "type": "id list", "presence": "derived",
                        "description": f"The ids of the grouped {src} records (at most {spec.get('max-members')})",
                        **({"note": f"computed by {entry['measure']}; removed when retired"} if entry else {})})
        for name, expr in (spec.get("fields") or {}).items():
            op = str(expr).split()[0]
            t = agg.get(op, "unknown")
            if op in ("max", "min") and "DATE" in str(expr):
                t = "date"
            f = {"name": name, "type": t, "presence": "derived" if name in figs else "optional",
                 "description": f"{op} over the group of: {' '.join(str(expr).split()[1:])[:160]}" if op != "count" else "The number of records in the group"}
            if entry and name in figs:
                f["note"] = f"computed by {entry['measure']}; removed when retired"
            top.append(f)
        nested = OrderedDict()
        if spec.get("rows-field"):
            top.append({"name": spec["rows-field"], "type": "record list", "presence": "derived",
                        "description": f"One row per grouped {src} record", **({"note": f"computed by {entry['measure']}; removed when retired"} if entry else {})})
            rows = []
            for name, expr in (spec.get("rows") or {}).items():
                r = {"name": name, "type": "unknown", "presence": "optional", "description": f"From the {src} record: {expr}"}
                if self.links.get(name):
                    r["link"] = self.links[name]
                rows.append(r)
            if spec.get("tree"):
                rows.append({"name": spec["tree"].get("level", "LEVEL"), "type": "integer", "presence": "derived",
                             "description": f"The row's depth in the group's tree, built from {spec['tree'].get('parent')} (the key 0)"})
            nested[spec["rows-field"] + "[]"] = rows
        if spec.get("tree"):
            t = spec["tree"]
            top.append({"name": t.get("field", "TREE"), "type": "record list", "presence": "derived",
                        "description": f"The rows as a tree under the key: each row with its CHILDREN, by {t.get('parent')}"})
            top.append({"name": t.get("levels", "LEVELS"), "type": "integer", "presence": "derived", "description": "The deepest level of the tree"})
        for name, expr in (spec.get("then") or {}).items():
            top.append({"name": name, "type": "unknown", "presence": "derived", "description": f"Worked out over the group's page: {str(expr)[:160]}"})
        self.review.append(f"field types of a derived kind are read from its connector's expressions (count, sum, distinct): confirm; "
                           f"rows copy the {src} record's fields, whose contract states their types.")
        return top, nested

    def fields_from_labels(self, figs, entry, key) -> tuple[list[dict], dict]:
        """No data ships: the fields the Sutras show (and a reactive kind's declared result), types unknown unless declared."""
        spec = self.reactive_spec() or {}
        result = spec.get("result") or {}
        types = {"number": "amount", "integer": "integer", "string": "text", "boolean": "boolean"}
        top, seen = [], set()
        for name in result.get("required") or []:
            top.append({"name": name, "type": types.get((result.get("types") or {}).get(name), "unknown"), "presence": "required",
                        **({"description": self.labels[name]} if self.labels.get(name) else {})})
            seen.add(name)
        nested = OrderedDict()
        for path, label in sorted(self.labels.items()):
            if path in seen:
                continue
            parent, _, name = path.rpartition(".")
            f = {"name": name, "type": "unknown", "presence": "optional", "description": label}
            if self.links.get(name):
                f["link"] = self.links[name]
            if parent:
                nested.setdefault(parent + "[]", []).append(f)
            else:
                top.append(f)
        self.review.append("no samples or test data ship for this kind: the fields are those its Sutras show (and its declared result); "
                           "state every field's type and presence.")
        return top, nested

    # -- the contract
    def build(self) -> dict:
        figs, entry = self.figures()
        key = self.key()
        klass = self.klass()
        title = self.cmd["label"]
        about_title = ((self.about.get("kinds") or {}).get(self.kind) or {}).get("title")
        base = [s for s in self.sutras if Path(s["file"]).name.startswith(self.kind + ".")] if len(self.sutras) > 1 else self.sutras
        sutra_desc = next((R._first_sentence(s["description"]) for s in base if s["description"]), "")
        description = sutra_desc or about_title or f"{title} (the {self.kind} kind of the {self.pack} pack)"
        source = self.source()
        dated = self.dated_by()
        cadence = self.cadence(dated)
        hint = self.binding_hint()
        if self.derived_spec() is not None:
            top, nested = self.fields_from_derived(self.derived_spec(), figs, entry, key)
        elif self.shape.n:
            top, nested = self.fields_from_data(figs, entry, key)
        else:
            top, nested = self.fields_from_labels(figs, entry, key)
        c = OrderedDict([("kind", self.kind), ("mnemonic", self.cmd["code"]), ("pack", self.pack), ("version", VERSION),
                         ("class", klass), ("status", "retiring" if self.retiring else "active")])
        if self.retiring:
            c["retiring-in"] = str(self.retiring["release"])
            c["replaced-by"] = "docs/contracts/" + self.retiring["contract"]
        c["title"] = title
        c["description"] = description
        c["source"] = source
        grain_key = ", ".join(key) or "a key to be confirmed"
        per = {"business_date": " per business date", "snapshot": ""}.get(dated, f" per {dated}")
        if self.derived_spec() is not None:
            c["grain"] = f"One record per {grain_key} of the {self.derived_spec().get('from')} records, computed when asked."
        elif self.reactive_spec() is not None:
            c["grain"] = "One answer per question (the id asked for and its parameters), computed when asked."
        else:
            c["grain"] = f"One row per {title.lower()}{per}, keyed by {grain_key}."
        c["key"] = key
        if self.patterns.get(self.kind):
            c["id-pattern"] = list(self.patterns[self.kind])
        c["dated-by"] = dated
        c["cadence"] = cadence
        c["binding-hint"] = hint
        c["fields"] = top
        if nested:
            c["nested"] = nested
        links = [{"field": f["name"], "kind": f["link"]} for f in top if f.get("link")]
        links += [{"field": f"{p}.{f['name']}", "kind": f["link"]} for p, fs in nested.items() for f in fs if f.get("link")]
        c["links"] = links
        c["validation"] = self.rules(top, nested, key, dated)
        notes = list(self.notes) + list((self.pol.get("notes") or {}).get(self.kind) or [])
        if self.split:
            notes.append(f"Terms kept, figures computed from {self.split['release']} ({'docs/contracts/' + self.split['contract']}): the "
                         f"fields marked derived are computed by {self.split['measure']} and leave the stored record then.")
        if self.retiring:
            notes.append(f"Retiring in {self.retiring['release']}: the stored kind is removed and computed instead by "
                         f"{self.retiring['measure']} (docs/contracts/{self.retiring['contract']}); old ids and links open the computed page.")
        c["notes"] = notes
        c["review"] = self.review_points()
        return c

    def rules(self, top: list[dict], nested: dict, key: list[str], dated: str) -> list[dict]:
        out = []
        req = [f["name"] for f in top if f["presence"] == "required"]
        if req:
            out.append({"rule": "required", "fields": req})
        if key:
            out.append({"rule": "unique", "key": key, "per": dated if dated not in ("snapshot", "on request") else "snapshot"})
        for path, fs in [("", top)] + list(nested.items()):
            for f in fs:
                name = f"{path}.{f['name']}" if path else f["name"]
                if f.get("pattern") and f["name"] in key and not path:
                    out.append({"rule": "pattern", "field": name, "pattern": f["pattern"]})
                if f.get("enum"):
                    out.append({"rule": "enum", "field": name, "values": f["enum"]})
                if f.get("link"):
                    out.append({"rule": "link", "field": name, "kind": f["link"]})
                if f["type"] == "integer" and "COUNT" in _words(f["name"]):
                    out.append({"rule": "range", "field": name, "min": 0})
                if f["type"] == "vector":
                    out.append({"rule": "vector-length", "field": name, "same": "every row of one business date"})
        return out

    def review_points(self) -> list[str]:
        out = list(self.review)
        if self.shape.n and self.derived_spec() is None:
            src = f"{len(self.samples)} samples" + (f" and {len(self.tdocs)} test-data documents ({', '.join(self.read)})" if self.tdocs else "")
            out.insert(0, f"required inferred from 100% coverage in {self.shape.n} documents ({src}): confirm each required field is "
                          f"mandatory upstream, and each optional one may be empty.")
        if self.enums_inferred:
            out.append("enum values inferred from the data, not from a glossary: " + ", ".join(self.enums_inferred) + ": confirm they are closed lists.")
        if self.by_name:
            out.append("number types decided by the field's name: " + ", ".join(self.by_name) + ": confirm amount, rate or integer.")
        if self.no_unit:
            out.append("no unit stated for the amounts " + _some(self.no_unit) + " (docs/contracts/README.md section 7: USD unless stated): confirm the currency.")
        if self.no_desc:
            out.append("no description in about.yaml or the Sutras for " + _some(self.no_desc) + ": write them in the pack's config/about.yaml glossary.")
        out += list((self.pol.get("review") or {}).get(self.kind) or [])
        return out


def _some(names: list[str], most: int = 12) -> str:
    return ", ".join(names[:most]) + (f" and {len(names) - most} more" if len(names) > most else "")


# -- files --------------------------------------------------------------------------------------------------------------------

def header() -> str:
    text = (ROOT / "config" / "license-header.txt").read_text(encoding="utf-8").strip().splitlines()
    return "".join(f"# {line}".rstrip() + "\n" for line in text) + "\n"


class _Dumper(yaml.SafeDumper):
    pass


_Dumper.add_representer(OrderedDict, lambda d, data: d.represent_dict(data.items()))
_Dumper.ignore_aliases = lambda self, data: True          # a list used twice (the key) is written twice, not as an anchor


def dump(contract: dict) -> str:
    """A contract as its YAML file: the licence, a line on what the file is, the contract."""
    body = yaml.dump(contract, Dumper=_Dumper, sort_keys=False, allow_unicode=True, width=140, default_flow_style=False)
    return (header() + f"# Data contract of the {contract['kind']} kind (command {contract['mnemonic']}): docs/contracts/README.md section 16.\n"
            f"# Drafted by `drishti.py contract bootstrap {contract['kind']}`; owned by people from then on: confirm the review points, then remove them.\n"
            + body)


def load(path: Path) -> dict:
    return _yaml(path) or {}


def contracts(packs_dir: Path = PACKS) -> list[tuple[dict, Path, dict | None]]:
    """(command, contract file, the contract or None when missing) for every mnemonic."""
    out = []
    for c in mnemonics(packs_dir):
        f = contract_file(packs_dir, c["pack"], c["kind"])
        out.append((c, f, load(f) if f.is_file() else None))
    return out


# -- checks -------------------------------------------------------------------------------------------------------------------

def schema_problems(contract: dict) -> list[str]:
    try:
        import jsonschema
    except ModuleNotFoundError:
        return ["jsonschema is not installed (pip install jsonschema): the contract cannot be validated"]
    v = jsonschema.Draft202012Validator(json.loads(SCHEMA.read_text(encoding="utf-8")))
    return [f"{'/'.join(str(p) for p in e.absolute_path) or '(top)'}: {e.message}" for e in sorted(v.iter_errors(contract), key=lambda e: list(e.absolute_path))]


def _family(t: str) -> str:
    return "number" if t in NUMBER else {"id": "text", "timestamp": "date"}.get(t, t)


def _numeric(v) -> bool:
    try:
        float(v)
        return isinstance(v, str)
    except (TypeError, ValueError):
        return False


def drift(contract: dict, packs_dir: Path = PACKS, testdata: Path = TESTDATA) -> list[str]:
    """What in the pack and its data no longer matches the contract: id pattern, links, fields, required, enums, types."""
    out = []
    all_m = manifests(packs_dir)
    kind, pack = contract["kind"], contract["pack"]
    pats = id_patterns(all_m).get(kind)
    if (contract.get("id-pattern") or None) != pats:
        out.append(f"id-pattern {contract.get('id-pattern')} differs from the pack graph's {pats}")
    links = graph_links(all_m, pack, packs_dir)
    samples, tdocs, _ = read_documents(PP.pack_dir(pack, packs_dir), kind, testdata)
    shape = shape_of(samples + tdocs)
    for path, fs in [("", contract.get("fields") or [])] + list((contract.get("nested") or {}).items()):
        seen = shape.fields.get(path)
        names = {f["name"] for f in fs}
        for f in fs:
            full = f"{path}.{f['name']}" if path else f["name"]
            if f.get("link") and links.get(f["name"]) != f["link"]:
                out.append(f"{full} links to {f['link']} but the pack graph says {links.get(f['name'])}")
            if seen is None:
                continue
            st = seen.get(f["name"])
            if st is None:
                if f["presence"] == "required":      # an optional field may be rare: tools/testdata/sample_docs.py audit reads every row
                    out.append(f"{full} is in the contract but not in the data")
                continue
            if f["presence"] == "required" and st.present < shape.items[path]:
                out.append(f"{full} is required but empty in {shape.items[path] - st.present} of {shape.items[path]} records")
            if f.get("enum") and not st.overflow and set(st.values) - set(f["enum"]):
                out.append(f"{full} has values outside its enum: {sorted(map(str, set(st.values) - set(f['enum'])))[:5]}")
            data_t = contract_type(f["name"], st, f["type"] in ("id", "id list"))[0]
            if f["type"] in NUMBER and set(st.types) - {"null"} <= {"integer", "number", "string"} \
                    and all(_numeric(v) for v in st.values):
                data_t = f["type"]                   # numbers, some written as numeric text by an extract
            if f["type"] != "unknown" and data_t != "unknown" and _family(data_t) != _family(f["type"]) and not (
                    f["type"] == "text" and data_t in ("date", "timestamp")):
                out.append(f"{full} is {f['type']} in the contract but {data_t} in the data")
        if seen is not None:
            out += [f"{(path + '.') if path else ''}{n} is in the data but not in the contract" for n in seen if n not in names]
            for n in seen:
                if n in links and n in names and not next(f for f in fs if f["name"] == n).get("link") and links[n] != kind:
                    out.append(f"{(path + '.') if path else ''}{n} links to {links[n]} in the pack graph but not in the contract")
    return out


def check(packs_dir: Path = PACKS, testdata: Path = TESTDATA, data: bool = True) -> list[str]:
    """Every problem: a mnemonic without a contract, a contract without a mnemonic, an invalid contract, one that no longer matches
    its pack (identity, policy) or, with ``data``, its data."""
    out = []
    pol = policy()
    known = set()
    for cmd, f, c in contracts(packs_dir):
        known.add(f.resolve())
        rel = f.relative_to(packs_dir) if f.is_relative_to(packs_dir) else f
        if c is None:
            out.append(f"{cmd['code']} ({cmd['kind']}): no contract: run  drishti.py contract bootstrap {cmd['kind']} --write")
            continue
        probs = schema_problems(c)
        for k, want in (("kind", cmd["kind"]), ("pack", cmd["pack"]), ("mnemonic", cmd["code"])):
            if c.get(k) != want:
                probs.append(f"{k} is '{c.get(k)}', the pack says '{want}'")
        ret = (pol.get("retiring") or {}).get(cmd["kind"])
        if ret and (c.get("status") != "retiring" or c.get("retiring-in") != str(ret["release"])
                    or c.get("replaced-by") != "docs/contracts/" + ret["contract"]):
            probs.append(f"retiring in {ret['release']} (tools/contracts.policy.yaml) replaced by docs/contracts/{ret['contract']}: "
                         f"status, retiring-in or replaced-by differ")
        if not ret and c.get("status") == "retiring":
            probs.append("status retiring, but tools/contracts.policy.yaml retires nothing of this kind")
        if data and not probs:
            probs += drift(c, packs_dir, testdata)
        out += [f"{rel}: {p}" for p in probs]
    codes = {cmd["code"] for cmd in mnemonics(packs_dir)}
    for f in _contract_files(packs_dir):
        if f.resolve() in known:
            continue
        rel = f.relative_to(packs_dir) if f.is_relative_to(packs_dir) else f.relative_to(ROOT)
        c = load(f)
        if c.get("status") != "planned":
            out.append(f"{rel}: no command of its pack has this kind")
            continue
        out += [f"{rel}: {p}" for p in planned_problems(c, f, codes)]
    return out


def _contract_files(packs_dir: Path) -> list[Path]:
    """Every contract file of every pack under ``packs_dir``, in pack then file order."""
    return [f for d in PP.pack_dirs(packs_dir).values() for f in sorted(d.glob("contracts/*.contract.yaml"))]


def planned_problems(c: dict, f: Path, codes: set[str]) -> list[str]:
    """A planned contract: an input designed before its data exists (no command, no data, no test data yet). It must be valid, name
    its own pack and file, and keep its proposed mnemonic free of the commands that exist."""
    probs = schema_problems(c)
    stem = f.name.removesuffix(".contract.yaml")
    if c.get("kind") != stem:
        probs.append(f"kind is '{c.get('kind')}', the file says '{stem}'")
    if c.get("pack") != f.parent.parent.name:
        probs.append(f"pack is '{c.get('pack')}', the folder says '{f.parent.parent.name}'")
    if c.get("mnemonic") in codes:
        probs.append(f"the proposed mnemonic {c.get('mnemonic')} is already a command")
    if (c.get("binding-hint") or {}).get("test-data"):
        probs.append("a planned kind has no test data: remove binding-hint.test-data")
    return probs


def planned(packs_dir: Path = PACKS) -> list[tuple[Path, dict]]:
    """The planned contracts (status planned, no command yet), in pack then file order."""
    known = {contract_file(packs_dir, cmd["pack"], cmd["kind"]).resolve() for cmd in mnemonics(packs_dir)}
    out = []
    for f in _contract_files(packs_dir):
        if f.resolve() not in known:
            c = load(f)
            if c.get("status") == "planned":
                out.append((f, c))
    return out


# -- docs ---------------------------------------------------------------------------------------------------------------------

def _md_header() -> list[str]:
    return [R.licence_comment(), f"<!-- Generated by {GENERATOR} from config/packs/*/contracts/*.contract.yaml. Edit the contracts, not this file. -->", ""]


def _v(x) -> str:
    if isinstance(x, list):
        return ", ".join(f"`{i}`" for i in x) if x else "—"
    if x in (None, ""):
        return "—"
    return str(x)


def sample_pages(c: dict, packs_dir: Path = PACKS, testdata: Path = TESTDATA) -> list[str]:
    """The data-sample pages of a contract's kind (sections of docs/contracts/samples/<domain>.md, written by tools/testdata/sample_docs.py): one per
    test-data folder of the kind, else one from the pack's samples for a kind a bank or a public source produces."""
    folders = [d["folder"] for d in testdata_sets(c["pack"], c["kind"], testdata) if d.get("folder")]
    if folders:
        return folders
    if planned_demo_data(c, testdata):
        return [f"{c['pack']}/{c['kind']}"]
    samples = PP.pack_dir(c["pack"], packs_dir) / "samples"
    if c.get("class") in ("input", "fundamental") and (any(samples.glob(f"{c['kind']}.jsonl")) or any(samples.glob(f"{c['kind']}.part-*.jsonl"))):
        return [f"{c['pack']}/{c['kind']}"]
    return []


def planned_demo_data(c: dict, testdata: Path = TESTDATA) -> list[Path]:
    """A planned input's demo data: files a generator writes under data/testdata/<pack>/<kind>/ before the kind has a command. They are
    not in testdata.yaml (``lake demo`` leaves them out) but they give the contract its data-sample page."""
    if c.get("status") != "planned" or c.get("class") not in ("input", "fundamental"):
        return []
    return sorted((Path(testdata) / c["pack"] / c["kind"]).glob("*.jsonl.gz"))


def _status(c: dict) -> str:
    if c.get("status") == "planned":
        return ("planned: no command yet; demo data generated, not loaded" if planned_demo_data(c)
                else "planned: no command or data yet")
    return f"retiring in {c['retiring-in']}" if c.get("status") == "retiring" else "active"


def _contract_md(c: dict, f: Path, pack_dir_rel: str) -> list[str]:
    out = [f"## {c['mnemonic']}: {c['title']} (`{c['kind']}`)", "", c.get("description", ""), ""]
    st = c.get("binding-hint") or {}
    where = ("computed by Drishti, never bound" if st.get("computed")
             else (f"lake table `{st['lake-table']}`" if st.get("lake-table") else "any source the site binds"))
    rows = [["Contract", f"[{f.name}](../../../{pack_dir_rel}/contracts/{f.name}) · version {c['version']}"],
            ["Class", c["class"]], ["Status", _status(c) + (f", replaced by {c['replaced-by']}" if c.get("replaced-by") else "")],
            ["Source", c["source"]], ["Grain", c["grain"]], ["Key", _v(c.get("key"))], ["Id pattern", _v(c.get("id-pattern"))],
            ["Dated by", f"`{c['dated-by']}`"], ["Cadence", f"{c['cadence']['frequency']} (due: {c['cadence']['due']})"],
            ["Binding hint", where + "; the site binds it (`config/bindings.yaml`, Admin → Data bindings)"]]
    if st.get("columns"):
        rows.append(["Columns", _v(st["columns"])])
    if st.get("vectors"):
        rows.append(["Vectors", _v(st["vectors"])])
    if st.get("partition"):
        rows.append(["Partitioned by", _v(st["partition"])])
    if st.get("test-data"):
        rows.append(["Test data", ", ".join(st["test-data"])])
    worked = sample_pages(c)
    if worked:
        rows.append(["Worked example", " · ".join(f"[{name}](../samples/{name.split('/', 1)[0]}.md#{name.split('/', 1)[1]})" for name in worked)
                     + " (a real record, every field explained, for the production query; tools/testdata/sample_docs.py)"])
    out += R._table(["", ""], rows)

    def ftable(fs: list[dict]) -> list[str]:
        return R._table(["Field", "Type", "Presence", "Unit", "Description", "Example or values", "Link"], [[
            f"`{x['name']}`", x["type"], x["presence"] + (f" (retiring in {x['retiring-in']})" if x.get("retiring-in") else ""), x.get("unit", ""),
            (x.get("description") or "—") + (f" *{x['note']}*" if x.get("note") else ""),
            (", ".join(f"`{e}`" for e in x["enum"]) if x.get("enum") else (f"`{x['example']}`" if x.get("example") not in (None, "") else ""))
            + (f" (pattern `{x['pattern']}`)" if x.get("pattern") else ""),
            f"`{x['link']}`" if x.get("link") else ""] for x in fs])

    out += ["**Fields**", ""] + ftable(c.get("fields") or [])
    for path, fs in (c.get("nested") or {}).items():
        out += [f"**Nested `{path}`**", ""] + ftable(fs)
    if c.get("links"):
        out += ["**Links:** " + ", ".join(f"`{x['field']}` → `{x['kind']}`" for x in c["links"]) + ".", ""]
    if c.get("validation"):
        out += ["**Validation**", ""]
        for r in c["validation"]:
            extra = {k: v for k, v in r.items() if k != "rule"}
            out.append(f"- {r['rule']}: " + "; ".join(f"{k} {_v(v) if isinstance(v, list) else v}" for k, v in extra.items()))
        out.append("")
    if c.get("notes"):
        out += ["**Notes**", ""] + [f"- {n}" for n in c["notes"]] + [""]
    if c.get("review"):
        out += [f"**To review ({len(c['review'])})**", ""] + [f"- {n}" for n in c["review"]] + [""]
    return out


def _repo_rel(p: Path) -> str:
    """A path as the repository names it (modules/<module>/packs/<pack>), else as given."""
    p = Path(p).resolve()
    return p.relative_to(ROOT).as_posix() if p.is_relative_to(ROOT) else p.as_posix()


def render_pack(pack: str, items: list[tuple[dict, Path, dict | None]], plans: list[tuple[Path, dict]] = (), packs_dir: Path = PACKS) -> str:
    where = _repo_rel(PP.pack_dir(pack, packs_dir))
    out = _md_header() + [f"# Data contracts of the {pack} pack", "",
                          f"One section per command of the pack: the shape of its data, how it is stored and checked, and what a person still "
                          f"has to confirm. The rules every contract shares are in [the contracts README](../README.md); the index of every "
                          f"command is [packs/README.md](README.md). The pack's guide, "
                          f"[{where}/guides/{pack}.md](../../../{where}/guides/{pack}.md), links each kind here and to "
                          f"its example (tools/packgen/common/guide_data.py) next to how to use the pack and where each kind's data comes "
                          f"from (*Data retrieval*).", ""]
    out += R._table(["Command", "Kind", "Class", "Status", "Fields", "To review"], [[
        f"[{c['mnemonic']}](#{_anchor(c)})", f"`{c['kind']}`", c["class"], _status(c), str(len(c.get('fields') or [])), str(len(c.get('review') or []))]
        for _, _, c in items if c])
    for cmd, f, c in items:
        if c:
            out += _contract_md(c, f, where)
    if plans:
        out += ["## Planned inputs", "",
                "Contracts of inputs designed before their data exists (`status: planned`): no command and no engine reads them yet, "
                "and `lake demo` loads none (a few have demo data from a generator, for their sample page). Each is the shape a bank delivers once the feature that reads it is built; until then a person may still "
                "change it.", ""]
        out += R._table(["Proposed command", "Kind", "Class", "Status", "Fields", "To review"], [[
            f"[{c['mnemonic']}](#{_anchor(c)})", f"`{c['kind']}`", c["class"], _status(c), str(len(c.get('fields') or [])),
            str(len(c.get('review') or []))] for _, c in plans])
        for f, c in plans:
            out += _contract_md(c, f, where)
    return "\n".join(out).rstrip() + "\n"


def _anchor(c: dict) -> str:
    """GitHub's slug of a contract's heading (lower case, punctuation dropped, spaces to hyphens)."""
    return re.sub(r"[^\w\- ]", "", f"{c['mnemonic']}: {c['title']} ({c['kind']})".lower()).replace(" ", "-")


def render_index(all_items: list[tuple[dict, Path, dict | None]], plans: list[tuple[Path, dict]] = ()) -> str:
    total = sum(len(c.get("review") or []) for _, _, c in all_items if c)
    out = _md_header() + ["# Data contracts of every command", "",
                          "Every command (mnemonic) of every pack has a data contract: a YAML file beside its pack "
                          "(`modules/<module>/packs/<pack>/contracts/<kind>.contract.yaml`, checked against `tools/contracts.schema.json`) and a section of "
                          "its pack's page here. The shared rules are in [the contracts README](../README.md); the computed measures that replace "
                          "the retiring kinds have their own contracts there.", "",
                          f"{sum(1 for _, _, c in all_items if c)} contracts, {total} points still to review. Check them with "
                          "`drishti.py contract check`; draft a new one with `drishti.py contract bootstrap KIND --write`.", ""]
    rows = []
    for cmd, f, c in all_items:
        if not c:
            rows.append([f"`{cmd['code']}`", f"`{cmd['kind']}`", cmd["pack"], "—", "no contract", "", ""])
            continue
        rows.append([f"`{c['mnemonic']}`", f"`{c['kind']}`", f"[{c['pack']}]({c['pack']}.md#{_anchor(c)})", c["class"], _status(c),
                     c.get("replaced-by", "").replace("docs/contracts/", "") or "", str(len(c.get("review") or []))])
    for f, c in plans:
        rows.append([f"`{c['mnemonic']}` (proposed)", f"`{c['kind']}`", f"[{c['pack']}]({c['pack']}.md#{_anchor(c)})", c["class"],
                     _status(c), "", str(len(c.get("review") or []))])
    out += R._table(["Command", "Kind", "Pack", "Class", "Status", "Replaced by", "To review"], rows)
    return "\n".join(out).rstrip() + "\n"


def render_docs(packs_dir: Path = PACKS) -> dict[Path, str]:
    """docs/contracts/packs/<pack>.md for each pack with commands (its planned inputs after them), and the index."""
    items, plans = contracts(packs_dir), planned(packs_dir)
    out = {}
    for pack in sorted({cmd["pack"] for cmd, _, _ in items} | {c.get("pack") for _, c in plans}):
        out[DOCS / f"{pack}.md"] = render_pack(pack, [i for i in items if i[0]["pack"] == pack],
                                               [x for x in plans if x[1].get("pack") == pack], packs_dir)
    out[DOCS / "README.md"] = render_index(items, plans)
    return out


# -- the commands -------------------------------------------------------------------------------------------------------------

def cmd_list(a) -> int:
    rows = [(cmd["code"], cmd["kind"], cmd["pack"], c["class"] if c else "-", _status(c) if c else "NO CONTRACT", len((c or {}).get("review") or []))
            for cmd, _, c in contracts(a.packs)]
    print(f"{'COMMAND':9} {'KIND':22} {'PACK':18} {'CLASS':14} {'STATUS':28} REVIEW")
    for r in rows:
        print(f"{r[0]:9} {r[1]:22} {r[2]:18} {r[3]:14} {r[4]:28} {r[5]}")
    print(f"{len(rows)} commands, {sum(1 for r in rows if r[3] != '-')} contracts, {sum(r[5] for r in rows)} review points")
    return 0


def cmd_show(a) -> int:
    cmd = find(a.kind, a.packs)
    f = contract_file(a.packs, cmd["pack"], cmd["kind"])
    if not f.is_file():
        print(f"no contract for {cmd['code']} ({cmd['kind']}): run  drishti.py contract bootstrap {cmd['kind']}", file=sys.stderr)
        return 1
    if a.json:
        print(json.dumps(load(f), indent=2, ensure_ascii=False))
    else:
        print(f.read_text(encoding="utf-8"), end="")
    return 0


def cmd_check(a) -> int:
    probs = check(a.packs, a.testdata, data=not a.no_data)
    if probs:
        print("data contracts do not match:\n  " + "\n  ".join(probs))
        return 1
    print(f"{len(mnemonics(a.packs))} commands, each with a valid contract that matches its pack" + ("" if a.no_data else " and its data"))
    return 0


def cmd_docs(a) -> int:
    pages = render_docs(a.packs)
    stale = [p for p, text in pages.items() if not p.is_file() or p.read_text(encoding="utf-8") != text]
    extra = [p for p in DOCS.glob("*.md") if p not in pages] if DOCS.is_dir() else []
    if a.check:
        if stale or extra:
            print("contract pages are stale: run  drishti.py contract docs\n  " + "\n  ".join(str(p.relative_to(ROOT)) for p in stale + extra))
            return 1
        print(f"{len(pages)} contract pages are current")
        return 0
    DOCS.mkdir(parents=True, exist_ok=True)
    for p in stale:
        p.write_text(pages[p], encoding="utf-8")
        print(f"wrote {p.relative_to(ROOT)}")
    for p in extra:
        p.unlink()
        print(f"removed {p.relative_to(ROOT)}")
    return 0


def cmd_bootstrap(a) -> int:
    if not a.all and not a.kind:
        raise SystemExit("contracts: name a kind or a command, or --all")
    targets = [find(a.kind, a.packs)] if a.kind else [cmd for cmd, f, c in contracts(a.packs) if c is None or a.force]
    for cmd in targets:
        text = dump(Draft(cmd["kind"], a.packs, a.testdata).build())
        f = contract_file(a.packs, cmd["pack"], cmd["kind"])
        if not a.write:
            print(text, end="")
            continue
        if f.is_file() and not a.force:
            print(f"{f.relative_to(a.packs)} exists: --force replaces it (and every edit made to it)", file=sys.stderr)
            return 1
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")
        print(f"wrote {f.relative_to(ROOT) if f.is_relative_to(ROOT) else f}")
    return 0


def build_parser(ap: argparse.ArgumentParser | None = None, parents=()) -> argparse.ArgumentParser:
    ap = ap or argparse.ArgumentParser(prog="contracts.py", description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--packs", type=Path, default=PACKS, help="the packs folder (default config/packs)")
    common.add_argument("--testdata", type=Path, default=TESTDATA, help="the test data folder (default data/testdata)")
    sub = ap.add_subparsers(dest="contract_cmd", required=True)
    sub.add_parser("list", help="every command, its contract's class and status, and its points to review", parents=[common, *parents]).set_defaults(run=cmd_list)
    p = sub.add_parser("show", help="one contract, by command or kind", parents=[common, *parents])
    p.add_argument("kind", help="a command (NSET) or a kind (netting-set)")
    p.add_argument("--json", action="store_true", help="as JSON")
    p.set_defaults(run=cmd_show)
    p = sub.add_parser("check", help="fail when a command has no contract, a contract is invalid or no longer matches its pack and data",
                       parents=[common, *parents])
    p.add_argument("--no-data", action="store_true", help="skip reading the samples and test data (schema and pack only)")
    p.set_defaults(run=cmd_check)
    p = sub.add_parser("docs", help="write docs/contracts/packs/<pack>.md and its README.md (--check: fail when they are stale)", parents=[common, *parents])
    p.add_argument("--check", action="store_true")
    p.set_defaults(run=cmd_docs)
    p = sub.add_parser("bootstrap", help="draft a contract from the pack and its data (prints it; --write saves it)", parents=[common, *parents])
    p.add_argument("kind", nargs="?", help="a command or a kind")
    p.add_argument("--all", action="store_true", help="every command without a contract (with --force: every command)")
    p.add_argument("--write", action="store_true", help="write modules/<module>/packs/<pack>/contracts/<kind>.contract.yaml")
    p.add_argument("--force", action="store_true", help="replace an existing contract")
    p.set_defaults(run=cmd_bootstrap)
    return ap


def register(groups) -> None:
    """Adds ``drishti.py contract list|show|check|docs|bootstrap``."""
    p = groups.add_parser("contract", help="contract: the data contract of every command (list, show, check, docs, bootstrap)",
                          description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                          epilog="examples:\n  drishti.py contract list\n  drishti.py contract show NSET\n  drishti.py contract check\n"
                                 "  drishti.py contract docs --check\n  drishti.py contract bootstrap my-kind --write")
    build_parser(p)
    p.set_defaults(func=lambda a, extra: a.run(a), cmd=None)


def main(argv: list[str] | None = None) -> int:
    a = build_parser().parse_args(argv)
    return a.run(a)


if __name__ == "__main__":
    sys.exit(main())
