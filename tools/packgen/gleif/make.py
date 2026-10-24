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

    python3 tools/packgen/gleif/make.py [--cache DIR]           write data/testdata/gleif/ and the pack's guide
    python3 tools/packgen/gleif/make.py --check [--cache DIR]   fail if what would be written differs from the pack

With a cache, --check rebuilds every document from it. Without one (a fresh clone, CI), --check reads the committed snapshot back
(data/testdata/gleif/) and rebuilds what is derived from it (the reference samples, the guide, the README), so it needs no network.

The documents follow Drishti's standard data pattern (tools/testdata/publicsnap.py): one gzipped JSON Lines snapshot per kind under
data/testdata/gleif/<kind>/, listed in data/testdata/testdata.yaml, built into the lake by tools/build_demo_lakes.py and served by
the pack's Delta connector. samples/ keeps only a few reference documents for the README and docs; nothing is served from it.
The pack's manifest, Sutras, fragments and help are hand-written; this writes the snapshot, samples/ and guides/gleif.md.
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
sys.path.insert(0, str(HERE))
import fetch as F  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
import publicsnap as SNAP  # noqa: E402  (the standard data pattern: data/testdata/<pack>/<kind>/)

PACK = ROOT / "config" / "packs" / "gleif"
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
COUNTRY_ROWS = 400       # entities listed on a country page (by name); the counts are of all of them


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
    for f, key, into in ((ROOT / "config/packs/core/samples/legal-entity.jsonl", "ENTITY_ID", out[0]),
                         (ROOT / "config/packs/core/samples/counterparty.jsonl", "COUNTERPARTY_ID", out[1])):
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
            "MAPPING_IDS": mapping_ids,
            "SEGMENT": seed["segment"] if seed else "", "US_COMPANY": u["ticker"] if u else None,
            "US_COMPANY_NAME": u["name"] if u else "",
            "LEGAL_ENTITY": core_le.get(lei), "COUNTERPARTY": core_cp.get(lei),
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

    # ---- groups
    grp_docs: dict[str, dict] = {}
    for root, grp in sorted(groups.items()):
        if root not in entities:
            continue
        members = [m for m in grp["members"] if m in entities]
        mset = set(members)
        depth: dict[str, int] = {root: 0}
        for m in members:                                   # breadth first order: a parent's depth is known first
            p = g.direct.get(m)
            if m != root:
                depth[m] = depth.get(p, 0) + 1 if p in mset else 1

        def node(x: str, seen: set) -> dict:
            ent = entities[x]
            kids = [k for k in kids_in.get(x, []) if k in mset and k not in seen and k != root]
            seen.update(kids)
            return _clean({"LEI": x, "NAME": ent["NAME"], "COUNTRY": ent.get("LEI_COUNTRY"), "CHILDREN": [node(k, seen) for k in kids]})
        seen = {root}
        tree = node(root, seen)
        loose = [m for m in members if m not in seen]           # ultimate children whose direct parent is not in the pack
        if loose:
            tree.setdefault("CHILDREN", []).extend(_clean({"LEI": m, "NAME": entities[m]["NAME"] + " (direct parent not reported)",
                                                           "COUNTRY": entities[m].get("LEI_COUNTRY")}) for m in loose)
        by_c: dict[str, int] = {}
        by_f: dict[str, int] = {}
        for m in members:
            c = entities[m].get("COUNTRY_NAME", "")
            by_c[c] = by_c.get(c, 0) + 1
            f = entities[m].get("LEGAL_FORM", "") or "Not given"
            by_f[f] = by_f.get(f, 0) + 1
        rows = [_clean({"LEI": m, "NAME": entities[m]["NAME"], "COUNTRY": entities[m].get("LEI_COUNTRY"), "LEVEL": depth.get(m, 1),
                        "LATE": "late" if entities[m]["RENEWAL_LATE"] else None})
                for m in members]
        r = entities[root]
        segs = sorted({s["segment"] for s in grp["seeds"]})
        capped = grp["total"] > len(members)
        grp_docs[root] = _clean({
            "GROUP_ID": root, "NAME": r["NAME"], "ENTITY_LEI": root, "COUNTRY": r.get("LEI_COUNTRY"), "COUNTRY_NAME": r.get("COUNTRY_NAME"),
            "LEI_COUNTRY": r.get("LEI_COUNTRY"), "LEGAL_FORM": r.get("LEGAL_FORM"), "SEGMENT": ", ".join(segs),
            "MEMBERS": len(members), "MEMBERS_IN_GLEIF": grp["total"], "CAPPED": capped,
            "CAP_NOTE": (f"The group has {grp['total']:,} entities in GLEIF; the pack keeps {len(members):,} (at most {F.GROUP_CAP} a group, "
                         "the nearest levels first). A wider build: tools/publicdata/gleif.py refresh --scope GROUP:" + root) if capped else "",
            "LEVELS": max(depth.values()), "COUNTRIES": len(by_c), "LATE_RENEWALS": sum(1 for m in members if entities[m]["RENEWAL_LATE"]),
            "SEEDS": [_clean({"LEI": s["lei"], "LABEL": s["label"], "SEGMENT": s["segment"], "TICKER": s.get("ticker")})
                      for s in sorted(grp["seeds"], key=lambda s: s["lei"])],
            "US_COMPANY": next((s["ticker"] for s in grp["seeds"] if s.get("ticker") and s["lei"] == root), None),
            "TREE": [tree], "ROWS": rows,
            "BY_COUNTRY": [{"LABEL": k, "VALUE": v} for k, v in sorted(by_c.items(), key=lambda kv: (-kv[1], kv[0]))][:20],
            "BY_LEGAL_FORM": [{"LABEL": k, "VALUE": v} for k, v in sorted(by_f.items(), key=lambda kv: (-kv[1], kv[0]))][:15],
            "PROVENANCE": prov(root), "_meta": meta})
    seg_count: dict[str, int] = {}
    for d in grp_docs.values():
        for s in d.get("SEGMENT", "").split(", "):
            seg_count[s] = seg_count.get(s, 0) + 1
    all_c: dict[str, int] = {}
    for ent in entities.values():
        all_c[ent.get("COUNTRY_NAME", "")] = all_c.get(ent.get("COUNTRY_NAME", ""), 0) + 1
    grp_docs["ALL"] = {
        "GROUP_ID": "ALL", "NAME": "Every ownership group of the pack", "GROUPS": len(grp_docs), "ENTITIES": len(entities),
        "COUNTRIES": len(all_c), "CAPPED_GROUPS": sum(1 for d in grp_docs.values() if d.get("CAPPED")),
        "LATE_RENEWALS": sum(1 for ent in entities.values() if ent["RENEWAL_LATE"]),
        "ROWS": [{"GROUP_ID": k, "NAME": d["NAME"], "COUNTRY": d.get("COUNTRY", ""), "SEGMENT": d.get("SEGMENT", ""), "MEMBERS": d["MEMBERS"],
                  "MEMBERS_IN_GLEIF": d["MEMBERS_IN_GLEIF"]} for k, d in sorted(grp_docs.items(), key=lambda kv: (-kv[1]["MEMBERS"], kv[1]["NAME"]))],
        "BY_SEGMENT": [{"LABEL": k, "VALUE": v} for k, v in sorted(seg_count.items(), key=lambda kv: (-kv[1], kv[0]))],
        "BY_COUNTRY": [{"LABEL": k, "VALUE": v} for k, v in sorted(all_c.items(), key=lambda kv: (-kv[1], kv[0]))][:20],
        "PROVENANCE": {"SOURCE": SOURCE, "RETRIEVED_AT": retrieved, "LICENCE": LICENCE, "URL": "https://search.gleif.org"}, "_meta": meta}

    # ---- countries
    by_country: dict[str, list[str]] = {}
    for lei, ent in entities.items():
        if ent.get("LEI_COUNTRY"):
            by_country.setdefault(ent["LEI_COUNTRY"], []).append(lei)
    countries: dict[str, dict] = {}
    for cc, ms in sorted(by_country.items()):
        ms = sorted(ms, key=lambda m: entities[m]["NAME"])
        forms: dict[str, int] = {}
        cities: dict[str, int] = {}
        grps: dict[str, int] = {}
        for m in ms:
            ent = entities[m]
            forms[ent.get("LEGAL_FORM", "") or "Not given"] = forms.get(ent.get("LEGAL_FORM", "") or "Not given", 0) + 1
            cities[ent.get("LEGAL_CITY", "") or "Not given"] = cities.get(ent.get("LEGAL_CITY", "") or "Not given", 0) + 1
            grps[ent["LEI_GROUP"]] = grps.get(ent["LEI_GROUP"], 0) + 1
        countries[cc] = _clean({
            "COUNTRY": cc, "NAME": ref.cname(cc), "ENTITIES": len(ms), "GROUPS_PRESENT": len(grps),
            "GROUPS_HEADQUARTERED": sum(1 for r in grp_docs if r != "ALL" and grp_docs[r].get("COUNTRY") == cc),
            "LAPSED": sum(1 for m in ms if entities[m].get("REGISTRATION_STATUS") == "LAPSED"),
            "LATE_RENEWALS": sum(1 for m in ms if entities[m]["RENEWAL_LATE"]),
            "ROWS": [_clean({"LEI": m, "NAME": entities[m]["NAME"], "CITY": entities[m].get("LEGAL_CITY"), "GROUP": entities[m]["LEI_GROUP"],
                             "LATE": "late" if entities[m]["RENEWAL_LATE"] else None}) for m in ms[:COUNTRY_ROWS]],
            "ROWS_NOTE": f"The first {COUNTRY_ROWS} of {len(ms):,} entities, by name" if len(ms) > COUNTRY_ROWS else None,
            "GROUP_ROWS": [{"GROUP_ID": k, "NAME": entities[k]["NAME"], "HOME": entities[k].get("LEI_COUNTRY", ""), "HERE": v}
                           for k, v in sorted(grps.items(), key=lambda kv: (-kv[1], entities[kv[0]]["NAME"]))],
            "BY_LEGAL_FORM": [{"LABEL": k, "VALUE": v} for k, v in sorted(forms.items(), key=lambda kv: (-kv[1], kv[0]))][:15],
            "BY_CITY": [{"LABEL": k, "VALUE": v} for k, v in sorted(cities.items(), key=lambda kv: (-kv[1], kv[0]))][:15],
            "PROVENANCE": {"SOURCE": SOURCE, "RETRIEVED_AT": retrieved, "LICENCE": LICENCE, "URL": f"https://search.gleif.org/#/search/simpleSearch={cc}"},
            "_meta": meta})
    countries["ALL"] = {
        "COUNTRY": "ALL", "NAME": "Every country of the pack's entities", "ENTITIES": len(entities), "COUNTRIES": len(by_country),
        "ROWS": [{"COUNTRY": cc, "NAME": d["NAME"], "ENTITIES": d["ENTITIES"], "GROUPS_HEADQUARTERED": d.get("GROUPS_HEADQUARTERED", 0),
                  "LAPSED": d.get("LAPSED", 0)} for cc, d in sorted(countries.items(), key=lambda kv: (-kv[1]["ENTITIES"], kv[0]))],
        "BY_COUNTRY": [{"LABEL": d["NAME"], "VALUE": d["ENTITIES"]} for cc, d in sorted(countries.items(), key=lambda kv: (-kv[1]["ENTITIES"], kv[0]))][:20],
        "PROVENANCE": {"SOURCE": SOURCE, "RETRIEVED_AT": retrieved, "LICENCE": LICENCE, "URL": "https://search.gleif.org"}, "_meta": meta}

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
    return {"lei-entity": entities, "lei-relationship": rels, "lei-group": grp_docs, "lei-country": countries, "lei-mapping": mdocs}


def catalog(docs: dict[str, dict[str, dict]]) -> list[dict]:
    """Every document's kind and id (the loader's index); the groups, countries and entities with their name as the title and the
    entities' country and city as the subtitle (what the search box and the suggestions find them by)."""
    out = []
    for kind in ("lei-group", "lei-country"):
        out += [{"kind": kind, "id": i, "title": d["NAME"]} for i, d in docs.get(kind, {}).items()]
    out += [{"kind": "lei-entity", "id": i, "title": d["NAME"], "subtitle": f"{d.get('LEI_COUNTRY', '')} {d.get('LEGAL_CITY', '')}".strip()}
            for i, d in docs.get("lei-entity", {}).items()]
    for kind in ("lei-relationship", "lei-mapping"):
        out += [{"kind": kind, "id": i} for i in docs.get(kind, {})]
    return out


GUIDE = """<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
<!-- Generated by tools/packgen/gleif/make.py. Edit the generator, not this file. -->
# The gleif pack

Legal Entity Identifiers (LEI) and **who owns whom**, from GLEIF: {entities} legal entities in {groups} ownership groups across
{countries} countries, retrieved from GLEIF's golden copy of {retrieved}. The groups are those of the us-equities companies (their
ultimate parent with every entity it consolidates) and of {fi_groups} large financial institutions: the FSB's global systemically
important banks, other large banks, insurers, asset managers and market infrastructures. Everything is published data under
**CC0**, not samples.

The pack is **off by default**: add it to the packs a server loads, `DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif`
(it needs no other pack; with `us-equities` on, a company and its LEI record link both ways).

![The entity page: JPMorgan Chase & Co. with a child opened beside its children table](images/gleif-entity.jpg)

## What you can open

| Type | Opens | Examples |
|---|---|---|
| `LEI <code>` | an entity: key facts, addresses, registration and renewal (late renewal flagged), its parents chain, its children (a click opens the child's summary beside), BICs and ISINs, the us-equities company | `LEI 8I5DZWZKVSZI1NUHU748`, `LEI HWUPKR0MPOU8FGXBT394` |
| `LEIG <code>` | a group: the ultimate parent with its whole tree, counts by country and legal form; a click on a member opens it beside | `LEIG 8I5DZWZKVSZI1NUHU748`; `LEIG ALL` lists every group and searches the entities |
| `LEIC <country>` | a country: the pack's entities there, the groups present, legal forms and cities | `LEIC US`, `LEIC GB`; `LEIC ALL` lists every country |
| `LEIR <id>` | one relationship: child, parent, direct or ultimate, status, periods, accounting standard | `LEIR LEIREL-<child LEI>-DIRECT`, from an entity |
| `LEIM <identifier>` | an ISIN, BIC or MIC and the LEI it maps to | a BIC or ISIN from an entity page |

A bare LEI (twenty characters) opens its entity.

## The data

| Kind | Documents | What one holds |
|---|---|---|
| `lei-entity` | {entities} | legal name and other names, legal form (ELF), category, jurisdiction, legal and headquarters addresses, entity and registration status, created, first registered, last updated, next renewal (late renewals flagged), the managing LOU, BICs, the ISIN count, OpenCorporates id, the direct and ultimate parent, the parents chain, the direct children, reporting exceptions |
| `lei-relationship` | {rels} | one consolidation relationship of an entity to its direct parent (or, when none is in the pack, its ultimate parent): status, relationship and accounting periods, accounting standard, the record's registration and renewal |
| `lei-group` | {groups_docs} | an ultimate parent with its tree (a tree table), every member, counts by country and legal form; `ALL` lists the groups |
| `lei-country` | {country_docs} | the entities of one legal-address country, the groups present, legal forms and cities; `ALL` lists the countries |
| `lei-mapping` | {mappings} | an identifier and its LEI: every BIC and MIC of the entities, and up to {isin_sample} ISINs of each seed entity |

**The cap.** A group keeps at most {cap} entities, the nearest levels first; {capped} groups are larger in GLEIF and their page says so
(`MEMBERS_IN_GLEIF`). A wider build of any group goes to the data folder (*Refreshing the data*). The groups are built from the
consolidation relationships only (`IS_DIRECTLY_CONSOLIDATED_BY`, `IS_ULTIMATELY_CONSOLIDATED_BY`); fund management, sub-fund, feeder
and branch relationships are not ownership and are left out. A member whose direct parent is not reported hangs under the ultimate
parent in the tree. An entity with no reported parent may carry a reporting exception (controlled by natural persons, not
consolidated, no known person) which its page shows.

**Late renewal.** An LEI must be renewed every year. A registration whose next renewal date has passed, or which GLEIF lists as
`LAPSED`, is flagged *Late* on its page, in its group and in its country; {late} of the pack's entities are late today.

Segments of the groups: {segments}.

**Where the data lives.** The documents are committed as Drishti's standard test data: one gzipped JSON Lines snapshot per
kind, `data/testdata/gleif/<kind>/snapshot-<date>.jsonl.gz`, listed in `data/testdata/testdata.yaml`. `python3 tools/drishti.py lake demo`
(or `python3 tools/build_demo_lakes.py`) builds them into the demo lake, the same documents on every business day it holds, and the
pack's Delta connector `gleif-store` serves them. Nothing is served by the demo connector: a server with `DRISHTI_DEMO_ENABLED=false`
opens every page of the pack from the lake. The pack's `samples/` folder keeps only a few reference documents for this guide and the
README.

## Links

An entity links to its direct and ultimate parent, its group, its country, its parent relationship (F7) and its identifiers; a relationship to
its child, parent and group; a group to its ultimate parent's entity and its country; an identifier to its entity. With the
`us-equities` pack on, an entity whose LEI is a us-equities company's links to the company (`{useq}` do), and the company's page links
back to the LEI record (its LEI in the key facts, and F5). A `core` legal entity or counterparty whose LEI matches links too
({core_matches} today: core's entities are a fictional bank's).

## Data sources and licence

| Source | What | Licence |
|---|---|---|
| GLEIF LEI API (`api.gleif.org/api/v1`): `lei-records`, `entity-legal-forms`, `countries`, `lei-issuers` | each entity's level 1 record; legal form names; country names; LOU names | CC0 1.0 |
| GLEIF golden copy, level 2 (`goldencopy.gleif.org`): relationship records and reporting exceptions | who consolidates whom, direct and ultimate; why a parent is not reported | CC0 1.0 |
| GLEIF mapping files (`mapping.gleif.org`): BIC, ISIN, MIC, OpenCorporates to LEI | identifiers of the entities | CC0 1.0 (BIC mapping with SWIFT, ISIN with ANNA) |

GLEIF publishes the LEI data free of charge under CC0 1.0: anyone may use it without restriction. **Attribution is appreciated**:
"Source: GLEIF, the Global Legal Entity Identifier Foundation, LEI data, retrieved {retrieved}"
([GLEIF's terms](https://www.gleif.org/en/meta/lei-data-terms-of-use)).

## Refreshing the data

The pack's own refresh script fetches, builds and checks in one step (guide: `docs/guides/PUBLIC_DATA.md`, gleif):

```bash
python3 tools/publicdata/gleif.py status                       # licence, last refreshed, cached size
python3 tools/publicdata/gleif.py refresh --dry-run            # what would be fetched; changes nothing
python3 tools/publicdata/gleif.py refresh                      # the network (about 90 MB of golden copy, 45 MB of mapping files, 70 API calls), then make.py and --check
python3 tools/publicdata/gleif.py refresh --offline            # rebuild the pack from the cache only
python3 tools/publicdata/gleif.py refresh --scope GROUP:<LEI>,COUNTRY:LU,NAME:allianz   # a wider build in the data folder, not this pack
```

`tools/publicdata/gleif.sh` and `tools/windows/publicdata-gleif.ps1` run the same script. Underneath it are the generator's two
steps, which can also be run by hand:

```bash
python3 tools/packgen/gleif/fetch.py --cache data/cache/gleif     # the network
python3 tools/packgen/gleif/make.py --cache data/cache/gleif      # writes data/testdata/gleif/, samples/ and this guide
python3 tools/packgen/gleif/make.py --check                       # no network: the pack matches its generator
```

After a refresh, rebuild the lake so the server reads the new snapshot: `python3 tools/drishti.py lake demo` (or
`python3 tools/build_demo_lakes.py`), then restart the server or let it pick the tables up at its next refresh.

The picture above is made by `tools/packgen/gleif/shot.py` (its own scratch server and console on 18760 and 18761).
"""


def guide(docs: dict[str, dict[str, dict]]) -> str:
    ents, grps = docs.get("lei-entity", {}), docs.get("lei-group", {})
    allg = grps.get("ALL", {})
    real = {k: v for k, v in grps.items() if k != "ALL"}
    segs = {}
    for d in real.values():
        for s in d.get("SEGMENT", "").split(", "):
            if s:
                segs[s] = segs.get(s, 0) + 1
    return GUIDE.format(entities=f"{len(ents):,}", groups=f"{len(real):,}", countries=len([c for c in docs.get("lei-country", {}) if c != "ALL"]),
                        retrieved=allg.get("PROVENANCE", {}).get("RETRIEVED_AT", ""),
                        fi_groups=sum(1 for d in real.values() if any(s.get("SEGMENT") != "US equities" for s in d.get("SEEDS", []))),
                        rels=f"{len(docs.get('lei-relationship', {})):,}", groups_docs=f"{len(grps):,}", country_docs=len(docs.get("lei-country", {})),
                        mappings=f"{len(docs.get('lei-mapping', {})):,}", isin_sample=F.ISIN_SAMPLE, cap=F.GROUP_CAP,
                        capped=allg.get("CAPPED_GROUPS", 0), late=f"{sum(1 for e in ents.values() if e.get('RENEWAL_LATE')):,}",
                        segments=", ".join(f"{k} {v}" for k, v in sorted(segs.items(), key=lambda kv: (-kv[1], kv[0]))),
                        useq=sum(1 for e in ents.values() if e.get("US_COMPANY")),
                        core_matches=sum(1 for e in ents.values() if e.get("LEGAL_ENTITY") or e.get("COUNTERPARTY")))


def _rel(p: Path) -> str:
    return str(p.relative_to(ROOT)) if p.resolve().is_relative_to(ROOT) else str(p)


def files(docs: dict[str, dict[str, dict]]) -> dict[Path, str]:
    ref = SNAP.reference("gleif", docs)                     # a few reference documents: the data itself is in data/testdata
    out = {PACK / "samples" / name: text for name, text in PS.files(ref, catalog(ref), catalog_indent=None).items()}
    out[PACK / "guides" / "gleif.md"] = guide(docs)
    out[PACK / "README.md"] = README.render_pack(PACK, files=out, intro=HERE / "readme" / "gleif.intro.md")
    return out


def main(argv: list[str], extra: list[str] | None = None, cap: int | None = None) -> int:
    p = argparse.ArgumentParser(description="Build the gleif pack from the fetched cache (no network).")
    p.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    p.add_argument("--check", action="store_true")
    p.add_argument("--pack", type=Path, help="the pack folder to write or check (default config/packs/gleif; "
                                               "tools/publicdata/gleif.py points a widened build at the data folder)")
    p.add_argument("--testdata", type=Path, help="the test data folder the snapshot goes to (default data/testdata for the committed "
                                                  "pack; beside a widened build's pack folder otherwise)")
    a = p.parse_args(argv)
    global PACK
    PACK = a.pack.resolve() if a.pack else ROOT / "config" / "packs" / "gleif"
    testdata = a.testdata.resolve() if a.testdata else SNAP.default_testdata(PACK, ROOT / "config" / "packs" / "gleif")
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
