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

"""Build the gleif pack's documents from the cache fetch.py fills (no network).

    python3 tools/packgen/gleif/make.py [--cache DIR]           write data/testdata/gleif/, the samples and the README
    python3 tools/packgen/gleif/make.py --check [--cache DIR]   fail if what would be written differs from the pack

With a cache, --check rebuilds every document from it. Without one (a fresh clone, CI), --check reads the committed snapshot back
(data/testdata/gleif/) and rebuilds what is derived from it (the reference samples, the README), so it needs no network.

The documents follow Drishti's standard data pattern (tools/testdata/publicsnap.py): one gzipped JSON Lines snapshot per kind under
data/testdata/gleif/<kind>/, listed in data/testdata/testdata.yaml, built into the lake by tools/build_demo_lakes.py and served by
the pack's Delta connector. samples/ keeps only a few reference documents for the README and docs; nothing is served from it.
The pack's manifest, Sutras, fragments and help are hand-written; this writes the snapshot, samples/ and the README
(the pack's story is a section of docs/guides/WORLD_DATA_GUIDE.md since i7k).
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE.parent / "common"))
import packsamples as PS  # noqa: E402
import readme as README  # noqa: E402  (the pack README standard)
import guide_data as GD  # noqa: E402  (its PP: where a pack is)
sys.path.insert(0, str(HERE))
import fetch as F  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
import publicsnap as SNAP  # noqa: E402  (the standard data pattern: data/testdata/<pack>/<kind>/)

PACK = GD.PP.pack_dir("gleif")                                       # a kernel pack: where the one resolver finds it
DEFAULT_CACHE = ROOT / "data" / "cache" / "gleif"
SOURCE = "GLEIF, the Global Legal Entity Identifier Foundation (LEI records, golden copy level 2, mapping files)"
LICENCE = "CC0 1.0 (GLEIF's terms: free of charge, no restriction; attribution appreciated)"
CATEGORY = {"GENERAL": "General", "FUND": "Fund", "BRANCH": "Branch", "SOLE_PROPRIETOR": "Sole proprietor",
            "RESIDENT_GOVERNMENT_ENTITY": "Government entity", "INTERNATIONAL_ORGANIZATION": "International organisation"}
EXCEPTION = {"NATURAL_PERSONS": "Controlled by natural persons, not by an entity",
             "NON_CONSOLIDATING": "The parent does not consolidate it (no consolidated accounts that include it)",
             "NO_KNOWN_PERSON": "No known person controls it (shares widely held)",
             "NON_PUBLIC": "The parent may not be disclosed (legal obstacle or harm)",
             "BINDING_LEGAL_COMMITMENTS": "Binding legal commitments prevent disclosure",
             "LEGAL_OBSTACLES": "Legal obstacles prevent disclosure", "DISCLOSURE_DETRIMENTAL": "Disclosure would be detrimental",
             "DETRIMENT_NOT_EXCLUDED": "Detriment not excluded", "CONSENT_NOT_OBTAINED": "The parent's consent was not obtained"}
LATE_SOON_DAYS = 30


def _load(p: Path, default=None):
    return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else default


def _clean(d: dict) -> dict:
    """Leave out empty values (a missing field reads as a dash on a screen) to keep the documents small."""
    return {k: v for k, v in d.items() if v not in (None, "", [], {})}


def _addr(a: dict | None) -> str:
    if not a:
        return ""
    lines = [x for x in (a.get("addressLines") or []) if x]
    city = " ".join(x for x in (a.get("postalCode"), a.get("city")) if x)
    return ", ".join(lines + [x for x in (city, a.get("region"), a.get("country")) if x])


def _other_packs() -> tuple[dict[str, str], dict[str, str]]:
    """core's legal entities and counterparties by LEI: {LEI: ENTITY_ID}, {LEI: COUNTERPARTY_ID} (fictional, so none match today)."""
    out: tuple[dict[str, str], dict[str, str]] = ({}, {})
    core = GD.PP.pack_dir("core") / "samples"
    for f, key, into in ((core / "legal-entity.jsonl", "ENTITY_ID", out[0]), (core / "counterparty.jsonl", "COUNTERPARTY_ID", out[1])):
        if f.is_file():
            for line in f.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    d = json.loads(line)
                    if d.get("LEI") and d.get(key):
                        into[d["LEI"]] = d[key]
    return out


def _renewal(reg_status: str, next_renewal: str, today: str) -> tuple[str, bool, int | None]:
    """(what the renewal says, late?, days to the renewal date; negative once past it). Late: the registration lapsed, or its renewal
    date has passed (the published record was not renewed in time)."""
    if not next_renewal:
        return ("", False, None)
    days = (date.fromisoformat(next_renewal) - date.fromisoformat(today)).days
    if reg_status == "LAPSED" or days < 0:
        return (f"Late: renewal was due {next_renewal}" + (" (lapsed)" if reg_status == "LAPSED" else ""), True, days)
    if reg_status in ("RETIRED", "MERGED", "ANNULLED", "DUPLICATE", "TRANSFERRED"):
        return (f"Not renewed: {reg_status.lower()}", False, days)
    if days <= LATE_SOON_DAYS:
        return (f"Due in {days} days ({next_renewal})", False, days)
    return (f"Current: next renewal {next_renewal}", False, days)


class Ref:
    """The code lists: legal forms (ELF), countries, LEI issuers."""

    def __init__(self, cache: Path):
        self.elf: dict[str, str] = {}
        for d in (_load(cache / "api" / "elf.json") or {}).get("data", []):
            a = d["attributes"]
            names = a.get("names") or []
            en = next((n for n in names if n.get("languageCode") == "en"), names[0] if names else {})
            self.elf[a["code"]] = en.get("transliteratedName") or en.get("localName") or a["code"]
        self.country = {d["attributes"]["code"]: d["attributes"]["name"] for d in (_load(cache / "api" / "countries.json") or {}).get("data", [])}
        self.lou = {d["id"]: (d["attributes"].get("name") or d["id"]) for d in (_load(cache / "api" / "lous.json") or {}).get("data", [])}

    def form(self, e: dict) -> str:
        lf = e.get("legalForm") or {}
        return self.elf.get(lf.get("id") or "", "") or (lf.get("other") or "") or (lf.get("id") or "")

    def cname(self, code: str) -> str:
        return self.country.get(code, code)


def build(cache: Path, extra: list[str] | None = None, cap: int | None = None) -> dict[str, dict[str, dict]]:
    pub = (_load(cache / "golden" / "publish.json") or {}).get("data", {})
    retrieved = (pub.get("publish_date") or "")[:10]
    if not retrieved or not (cache / "reduced" / "rr.json").is_file():
        raise SystemExit(f"no cache at {cache}: run tools/packgen/gleif/fetch.py first")
    g = F.Graph(_load(cache / "reduced" / "rr.json", []))
    leis, groups = F.universe(cache, extra, cap)
    F.reduce_mappings(cache, leis)                           # offline: from the downloaded files, again only when the universe changed
    F.reduce_repex(cache, leis)
    recs ={k: v["attributes"] for k, v in F.cached_records(cache).items()}
    ref = Ref(cache)
    maps = {n: (_load(cache / "reduced" / f"{n}.json") or {}) for n in F.MAPPINGS}
    bics, isins = maps["bic-lei"].get("data", {}), maps["isin-lei"].get("data", {})
    mics, ocs = maps["mic-lei"].get("data", {}), maps["oc-lei"].get("data", {})
    repex = (_load(cache / "reduced" / "repex.json") or {}).get("data", {})
    core_le, core_cp = _other_packs()
    useq = {s["lei"]: s for s in F.us_equities_seeds()}
    leis = [x for x in leis if x in recs]                    # an LEI the API does not know (retired, annulled) is left out
    inpack = set(leis)
    root_of = {m: r for r, grp in groups.items() for m in grp["members"] if m in inpack}
    seed_of: dict[str, dict] = {}
    for grp in groups.values():
        for s in grp["seeds"]:
            seed_of.setdefault(s["lei"], s)

    def name(lei: str) -> str:
        r = recs.get(lei)
        return r["entity"]["legalName"]["name"] if r else ""

    def country(lei: str) -> str:
        r = recs.get(lei)
        return (r["entity"].get("legalAddress") or {}).get("country", "") if r else ""

    def prov(lei: str) -> dict:
        return {"SOURCE": SOURCE, "RETRIEVED_AT": retrieved, "LICENCE": LICENCE, "URL": f"https://search.gleif.org/#/record/{lei}"}

    meta = {"source": "gleif", "retrievedAt": retrieved, "generation": 1}
    kids_in = {p: sorted(k for k in g.kids.get(p, []) if k in inpack) for p in inpack}

    # ---- entities
    entities: dict[str, dict] = {}
    rels: dict[str, dict] = {}
    for lei in leis:
        a = recs[lei]
        e, reg = a["entity"], a.get("registration") or {}
        la, hq = e.get("legalAddress") or {}, e.get("headquartersAddress") or {}
        cc = la.get("country", "")
        reg_status = reg.get("status", "")
        nxt = (reg.get("nextRenewalDate") or "")[:10]
        renewal, late, days = _renewal(reg_status, nxt, retrieved)
        root = root_of.get(lei, lei)
        # the parents chain: direct parents up to the ultimate parent (or the top of the reported chain)
        chain, x, seen = [], lei, {lei}
        while x in g.direct and g.direct[x] not in seen and len(chain) < 12:
            x = g.direct[x]
            seen.add(x)
            chain.append({"LEVEL": len(chain) + 1, "LEI": x, "NAME": name(x) or x, "RELATIONSHIP": "Direct parent" if not chain else "Higher parent"})
        up = g.ultimate.get(lei)
        if up and up not in seen:
            chain.append({"LEVEL": len(chain) + 1, "LEI": up, "NAME": name(up) or up, "RELATIONSHIP": "Ultimate parent (chain not reported)"})
        elif up and chain:
            for c in chain:
                if c["LEI"] == up:
                    c["RELATIONSHIP"] = "Ultimate parent" if c["LEVEL"] > 1 else "Direct and ultimate parent"
        children = [_clean({"LEI": k, "NAME": name(k), "COUNTRY": country(k), "KIDS": len(kids_in.get(k, [])) or None}) for k in kids_in.get(lei, [])]
        exc = [{"LEVEL": "Direct parent" if cat.startswith("DIRECT") else "Ultimate parent", "REASON": reason,
                "MEANING": EXCEPTION.get(reason, reason.replace("_", " ").lower())} for cat, reason in repex.get(lei, [])
               if "ACCOUNTING_CONSOLIDATION" in cat]
        my_bics, my_isin = bics.get(lei, []), isins.get(lei, {})
        seed = seed_of.get(lei)
        # identifier documents for the seeds and the groups' ultimate parents (every entity lists its own BICs and counts its ISINs)
        mapping_ids = (list(my_bics) + list(mics.get(lei, [])) + list(my_isin.get("sample", [])[:F.ISIN_SAMPLE])) if seed or lei in groups else []
        u = useq.get(lei)
        ocid = (ocs.get(lei) or [""])[0]
        hq_addr = _addr(hq)
        doc = {
            "LEI": lei, "NAME": e["legalName"]["name"],
            "OTHER_NAMES": [{"NAME": n["name"], "TYPE": (n.get("type") or "").replace("_", " ").lower()} for n in (e.get("otherNames") or [])
                            + (e.get("transliteratedOtherNames") or [])][:2],
            "LEGAL_FORM": ref.form(e), "CATEGORY": None if e.get("category") == "GENERAL" else CATEGORY.get(e.get("category") or "", e.get("category")),
            "JURISDICTION": None if e.get("jurisdiction") == cc else e.get("jurisdiction"), "LEI_COUNTRY": cc, "COUNTRY_NAME": ref.cname(cc),
            "LEGAL_ADDRESS": _addr(la), "LEGAL_CITY": la.get("city"),
            "HQ_ADDRESS": hq_addr if hq_addr != _addr(la) else None,
            "HQ_COUNTRY": hq.get("country") if hq.get("country") != cc else None,
            "REGISTERED_AS": e.get("registeredAs"), "ENTITY_STATUS": e.get("status"), "CREATED": (e.get("creationDate") or "")[:10],
            "SUCCESSOR": (e.get("successorEntity") or {}).get("lei"),
            "REGISTRATION_STATUS": reg_status, "INITIAL_REGISTRATION": (reg.get("initialRegistrationDate") or "")[:10],
            "LAST_UPDATE": (reg.get("lastUpdateDate") or "")[:10], "NEXT_RENEWAL": nxt, "RENEWAL": renewal if late or (days is not None and days <= LATE_SOON_DAYS) else None,
            "RENEWAL_LATE": late,
            "MANAGING_LOU": ref.lou.get(reg.get("managingLou") or "", reg.get("managingLou")),
            "BICS": ", ".join(my_bics), "BIC_COUNT": len(my_bics) or None, "ISIN_COUNT": my_isin.get("count") or None, "MIC": ", ".join(mics.get(lei, [])),
            "OPENCORPORATES": ocid,
            "DIRECT_PARENT": g.direct.get(lei) if g.direct.get(lei) in inpack else None,
            "ULTIMATE_PARENT": up if up in inpack and up != g.direct.get(lei) else None,
            "LEI_GROUP": root,
            "PARENTS": chain, "PARENT_EXCEPTIONS": exc, "CHILDREN": children, "CHILD_COUNT": len(children) or None,
            "DIRECT_CHILDREN_GLEIF": len(g.kids.get(lei, [])) or None, "ULTIMATE_CHILDREN_GLEIF": len(g.ukids.get(lei, [])) or None,
            "GLEIF_GROUP_SIZE": groups[lei]["total"] if lei in groups else None,
            "MAPPING_IDS": mapping_ids,
            "SEGMENT": seed["segment"] if seed else "", "US_COMPANY": u["ticker"] if u else None,
            "US_COMPANY_NAME": u["name"] if u else "",
            "LEGAL_ENTITY": core_le.get(lei), "COUNTERPARTY": core_cp.get(lei),
            "PROVENANCE": prov(lei),
        }
        entities[lei] = _clean(doc)
        entities[lei]["RENEWAL_LATE"] = late               # a boolean is kept whatever its value
        # ---- its relationships (as the child), to parents in the pack
        for lv, key in (("DIRECT", "D"), ("ULTIMATE", "U")):
            r = g.rows.get((lei, key))
            if not r or r[1] not in inpack or (key == "U" and f"LEIREL-{lei}-DIRECT" in rels):
                continue
            rid = f"LEIREL-{lei}-{lv}"
            rels[rid] = _clean({
                "RELATIONSHIP_ID": rid, "LEVEL": "Direct" if key == "D" else "Ultimate",
                "CHILD_ENTITY": lei, "PARENT_ENTITY": r[1],
                "RELATIONSHIP_STATUS": r[3], "START": r[5], "END": r[6],
                "PERIODS": [{"TYPE": p[0].replace("_", " ").lower(), "START": p[1], "END": p[2]} for p in r[7] if p[0] != "RELATIONSHIP_PERIOD"],
                "ACCOUNTING_STANDARD": ", ".join(q[1] for q in r[8] if q[0] == "ACCOUNTING_STANDARD"),
                "REGISTRATION_STATUS": r[4], "INITIAL_REGISTRATION": r[9], "LAST_UPDATE": r[10], "NEXT_RENEWAL": r[11],
                "LEI_GROUP": root})

    # The ownership groups (LEIG) and the countries (LEIC) are not written: the gleif-groupings connector computes them from the
    # entities when asked (since 2.9.2; docs/guides/bi/README.md). An ultimate parent carries GLEIF_GROUP_SIZE,
    # its group's size in GLEIF's golden copy (the pack keeps at most F.GROUP_CAP entities a group).

    # ---- mappings: every BIC and MIC of the entities, the ISIN sample of the seed entities
    mdocs: dict[str, dict] = {}
    for lei, ent in entities.items():
        for ident in ent.get("MAPPING_IDS", []):
            typ = "BIC" if ident in bics.get(lei, []) else "MIC" if ident in mics.get(lei, []) else "ISIN"
            src = {"BIC": "bic-lei", "MIC": "mic-lei", "ISIN": "isin-lei"}[typ]
            mdocs.setdefault(ident, _clean({
                "IDENTIFIER": ident, "TYPE": typ, "ENTITY_LEI": lei, "ENTITY_NAME": ent["NAME"], "COUNTRY": ent.get("LEI_COUNTRY"),
                "LEI_GROUP": ent["LEI_GROUP"], "ISIN_COUNT": ent.get("ISIN_COUNT") if typ == "ISIN" else None,
                "MAPPING_FILE": maps[src].get("file", "")}))
    return {"lei-entity": entities, "lei-relationship": rels, "lei-mapping": mdocs}


def catalog(docs: dict[str, dict[str, dict]]) -> list[dict]:
    """Every document's kind and id (the loader's index); the entities with their name as the title and their country and city as the
    subtitle (what the search box and the suggestions find them by). The groups and countries are computed, not documents."""
    out = []
    out += [{"kind": "lei-entity", "id": i, "title": d["NAME"], "subtitle": f"{d.get('LEI_COUNTRY', '')} {d.get('LEGAL_CITY', '')}".strip()}
            for i, d in docs.get("lei-entity", {}).items()]
    for kind in ("lei-relationship", "lei-mapping"):
        out += [{"kind": kind, "id": i} for i in docs.get(kind, {})]
    return out


def _rel(p: Path) -> str:
    return str(p.relative_to(ROOT)) if p.resolve().is_relative_to(ROOT) else str(p)


def files(docs: dict[str, dict[str, dict]]) -> dict[Path, str]:
    ref = SNAP.reference("gleif", docs)                     # a few reference documents: the data itself is in data/testdata
    out = {PACK / "samples" / name: text for name, text in PS.files(ref, catalog(ref), catalog_indent=None).items()}
    out[PACK / "README.md"] = README.render_pack(PACK, files=out)
    return out


def main(argv: list[str], extra: list[str] | None = None, cap: int | None = None) -> int:
    p = argparse.ArgumentParser(description="Build the gleif pack from the fetched cache (no network).")
    p.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    p.add_argument("--check", action="store_true")
    p.add_argument("--pack", type=Path, help="the pack folder to write or check (default modules/reference-data/packs/gleif; "
                                               "drishti.py feed run gleif points a widened build at the data folder)")
    p.add_argument("--testdata", type=Path, help="the test data folder the snapshot goes to (default data/testdata for the committed "
                                                  "pack; beside a widened build's pack folder otherwise)")
    a = p.parse_args(argv)
    global PACK
    PACK = a.pack.resolve() if a.pack else GD.PP.pack_dir("gleif")
    testdata = a.testdata.resolve() if a.testdata else SNAP.default_testdata(PACK, GD.PP.pack_dir("gleif"))
    F.reduce_rr(a.cache)                                     # offline: the relationship file from the downloaded golden copy
    if (a.cache / "reduced" / "rr.json").is_file():
        docs = build(a.cache, extra, cap)
    elif a.check:
        docs = SNAP.read(testdata, "gleif")
        if not docs:
            raise SystemExit(f"no cache at {a.cache} and no snapshot in {testdata}/gleif: run tools/packgen/gleif/fetch.py first")
        print(f"no cache at {a.cache}: checking the committed snapshot ({testdata}/gleif) against what it derives")
    else:
        raise SystemExit(f"no cache at {a.cache}: run tools/packgen/gleif/fetch.py first")
    return SNAP.generate("gleif", PACK, docs, files(docs), a.check, testdata)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
