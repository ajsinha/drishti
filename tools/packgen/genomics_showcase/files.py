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

"""The text files of the two showcase packs, written from the kinds table (spec.py) and the generated documents (model.py):

    pack.yaml, contracts/<kind>.contract.yaml, sutras/<kind>.v1.sutra.yaml, config/about.yaml, samples/ (a few
    reference documents and the catalogue) and README.md (each pack's story is a section of modules/genomics/docs/guide.md since i7k)

Nothing in them is edited by hand: change the table or this file, run make.py. Both packs belong to the genomics module
(modules/genomics/packs/); their data is the committed snapshot data/testdata/<pack>/<kind>/ that `lake demo` builds into the demo lake
and the packs' own Delta connectors serve.
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))
import spec as S  # noqa: E402
import packsamples as PSAMP  # noqa: E402
import sutradoc as SD  # noqa: E402  (the structured doc fields of a Sutra)


#: the Help page of the genomics module's guide (drishti-console/config/help.yaml)
GUIDE_PAGE = "module-genomics-1"


def _load(name: str, path: Path):
    s = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(s)
    sys.modules[name] = m
    s.loader.exec_module(m)
    return m


README = _load("drishti_pack_readme", ROOT / "tools" / "packgen" / "common" / "readme.py")
CONTRACTS = _load("drishti_contracts", ROOT / "tools" / "contracts.py")    # the one way a contract's validation rules follow its fields

MODULE_DIR = ROOT / "modules" / "genomics"
GENERATOR = "tools/packgen/genomics_showcase/make.py"
HEADER = "\n".join((HERE / "spec.py").read_text(encoding="utf-8").splitlines()[:13]) + "\n"
SOURCE = ("synthetic: the seeded generator " + GENERATOR + " (nothing real: the genes, patients, protein and designs are invented)")

PACKS = {
    S.GENOMICS: {
        "code": "GENOMICS", "title": "Genomics showcase",
        "description": ("A synthetic cohort of 1,500 patients read against one invented gene, HLXK7: 2,000 genes, 12,000 variant sites, 45,000 "
                        "variant calls, copy-number segments and pathways, and 36,000 association tests of three traits computed from "
                        "the calls. The words ASSOC (a variant's test against a trait), SGENE, SSAMPLE, SVARIANT, VCALL, CNV and PATHWAY "
                        "open the records; the showcase's screens are drawn from them. Everything is seeded and invented."),
        "examples": [("ASSOC {lead_assoc}", "The lead variant of the response peak: its p-value, effect and link to the peak's lead"),
                     ("SGENE HLXK7", "The gene: chromosome, role, protein length and how many samples carry a damaging change"),
                     ("SVARIANT {lead_variant}", "The variant at the first hotspot of HLXK7, with its amino acid change and carriers"),
                     ("SSAMPLE S0001", "One patient: the clinical tracks, the response and the cluster"),
                     ("VCALL {lead_call}", "One call: sample, variant, genotype, allele fraction and depth")],
        },
    S.PROTEIN: {
        "code": "PROTENG", "title": "Protein engineering showcase",
        "description": ("Four invented proteins and the campaign that redesigned one of them, Helixase-7 (gene HLXK7, 312 amino acids): every "
                        "position and every amino acid measured in two assays (47,840 scan scores), the fold with its coordinates, burial "
                        "and contacts, and eight rounds of 1,500 designs that climb to about fifteen times the natural activity. The words "
                        "PROT (a protein), DESIGN (a design), RESIDUE, DMS, CONTACT, DMUT and DROUND open the records; the showcase's "
                        "screens are drawn from them. Everything is seeded and invented."),
        "examples": [("PROT HLXK7", "Helixase-7: length, family, sequence and the positions of its active site"),
                     ("DESIGN {lead_design}", "The lead design of the campaign: its changes, activity and stability"),
                     ("DMS {lead_score}", "One scan score: what changing one position to one amino acid did to the activity"),
                     ("RESIDUE {lead_residue}", "The active site's centre: fold, burial, coordinates and mean effects"),
                     ("DROUND {lead_round}", "The round of the lead design: method, best and median activity")],
        },
}
PACK_OF_CODE = {"GENOMICS": S.GENOMICS, "PROTENG": S.PROTEIN}

ABOUT = {
    "study-gene": "${$.GENE} on chromosome ${$.CHROM} (${$.ROLE}): ${$.ALTERED_SAMPLES} of the cohort's 1,500 samples carry a damaging change in it.",
    "study-sample": "Sample ${$.SAMPLE_ID}: ${$.TUMOUR_TYPE}, stage ${$.STAGE}, ${$.RESPONSE}, in cluster ${$.CLUSTER}.",
    "study-variant": "${$.VARIANT_ID} in ${$.GENE}: ${$.CONSEQUENCE}, ${$.IMPACT} impact, carried by ${$.CARRIERS} samples.",
    "variant-call": "Sample ${$.SAMPLE_ID} carries ${$.VARIANT_ID} (${$.GENE}) with ${$.GENOTYPE} at an allele fraction of ${$.VAF}.",
    "association": "${$.VARIANT_ID} in ${$.GENE} against ${$.TRAIT}: minus log10 p ${$.NEG_LOG10_P}, effect ${$.BETA}.",
    "cnv-segment": "Sample ${$.SAMPLE_ID}: ${$.CALL} on chromosome ${$.CHROM} (${$.COPY_NUMBER} copies).",
    "pathway-gene": "${$.GENE} is ${$.ROLE_IN_PATHWAY} in ${$.PATHWAY}.",
    "protein": "${$.NAME} (${$.PROTEIN}): ${$.LENGTH} amino acids, ${$.FAMILY}.",
    "residue": "Position ${$.POS} of ${$.PROTEIN} is ${$.AA}, in a ${$.SECONDARY} of the ${$.DOMAIN}, burial ${$.BURIAL}.",
    "residue-contact": "Positions ${$.POS_A} and ${$.POS_B} of ${$.PROTEIN} are ${$.DISTANCE} angstroms apart.",
    "dms-score": "${$.MUTATION} of ${$.PROTEIN} scores ${$.SCORE} for ${$.ASSAY}.",
    "design": "${$.DESIGN_ID}: ${$.N_CHANGES} changes, ${$.ACTIVITY} times the natural activity.",
    "design-mutation": "${$.MUTATION} in design ${$.DESIGN_ID}.",
    "design-round": "Round ${$.ROUND} of the campaign on ${$.PROTEIN}: best activity ${$.BEST_ACTIVITY}, median ${$.MEDIAN_ACTIVITY}.",
}

DATE_FMT = {"date": "date"}


def dump(obj) -> str:
    return yaml.safe_dump(obj, sort_keys=False, allow_unicode=True, width=140)


def table_of(k: S.Kind) -> str:
    return f"{k.pack}.{k.kind}"


def _field_yaml(f: S.F) -> dict:
    d = {"name": f.name, "type": f.type, "presence": f.presence}
    if f.unit:
        d["unit"] = f.unit
    d["description"] = f.means
    if f.example is not None:
        d["example"] = f.example
    if f.enum:
        d["enum"] = list(f.enum)
    if f.link:
        d["link"] = f.link
    return d


def contract(k: S.Kind) -> str:
    c = {"kind": k.kind, "mnemonic": k.word, "pack": k.pack, "version": "1.0", "class": "fundamental", "status": "active",
         "title": k.title, "description": k.description, "source": SOURCE, "grain": k.grain, "key": [k.key]}
    if k.prefix:
        c["id-pattern"] = ["^" + k.prefix]
    c["dated-by"] = "snapshot"
    c["cadence"] = {"frequency": "static", "due": "regenerated only when the generator or its seed changes; the same documents on every business day"}
    c["binding-hint"] = {"lake-table": table_of(k), "columns": list(k.promote), "test-data": [f"data/testdata/{k.pack}/{k.kind}"]}
    c["fields"] = [_field_yaml(f) for f in k.fields]
    links = [{"field": f.name, "kind": f.link} for f in k.fields if f.link]
    if links:
        c["links"] = links
    c["validation"] = CONTRACTS.Draft.rules(None, c["fields"], {}, c["key"], c["dated-by"])
    c["notes"] = ["Synthetic and seeded: the same seed gives the same documents, byte for byte. Nothing in it is real: the gene, protein, "
                  "patients and designs are invented, and every screen that shows it says so.",
                  "A snapshot: the same documents on every business day of the demo lake."]
    c["review"] = []
    return (HEADER + f"\n# Data contract of the {k.kind} kind (command {k.word}): docs/contracts/README.md section 16.\n"
            "# Written by " + GENERATOR + " from tools/packgen/genomics_showcase/spec.py: edit those, not this file.\n" + dump(c))


def _bind(f: S.F) -> str:
    if f.link:
        return f"link($.{f.name}, '{f.link}')"
    return f"$.{f.name}"


def sutra(k: S.Kind, example_id: str) -> str:
    kv = []
    for f in k.fields:
        if f.type == "list":
            continue
        e = {"label": f.name.replace("_", " ").capitalize(), "bind": _bind(f)}
        if f.type == "integer" and not f.unit == "amino acids":
            e["fmt"] = "amount0"
        elif f.type == "date":
            e["fmt"] = "date"
        kv.append(e)
    strip = []
    for n, name in enumerate(k.strip):
        f = next(x for x in k.fields if x.name == name)
        e = {"label": name.replace("_", " ").capitalize(), "bind": _bind(f)}
        if f.type == "integer" and f.unit != "amino acids":
            e["fmt"] = "amount0"
        if n == len(k.strip) - 1:
            e["emphasis"] = True
        strip.append(e)
    pack_label = "Genomics" if k.pack == S.GENOMICS else "Protein engineering"
    s = {"rachana": 1.2, "sutra": k.kind, "version": 1,
         "description": f"{k.title}: {k.description.split('. ')[0].rstrip('.')}.",
         "doc": {"summary": f"One {k.title.lower()}, as a record.",
                 "description": (f"{k.description} The page is the plain record view: the strip gives the headline fields and the table every "
                                 "field, a linked field opens its kind; the showcase's own screens are drawn from the same data."),
                 "params": {}, "emits": [], "expects": [],
                 "examples": [f"/v/{k.kind}/{example_id}", f"{k.word} {example_id}"], "since": "2.9.11"},
         "match": {"kind": k.kind, "priority": 10},
         "title": {"pill": f"{pack_label} · {k.title}", "id": f"$.{k.key}"},
         "strip": strip,
         "panels": [{"id": "record", "kind": "kv", "title": k.title, "columns": kv}],
         "keys": {"F9": "raw"}}
    return HEADER + "\n# Written by " + GENERATOR + " from the kinds table: the plain record view of the kind.\n" + dump(s)


def about(pack: str) -> str:
    kinds = {}
    for k in S.kinds_of(pack):
        gl = {}
        for f in k.fields:
            e = {"term": f.name.replace("_", " ").capitalize(), "means": f.means}
            if f.unit:
                e["unit"] = f.unit
            gl[f.name] = e
        kinds[k.kind] = {"title": k.title, "about": ABOUT[k.kind], "guide": GUIDE_PAGE, "glossary": gl}
    return (HEADER + f"\n# About this page: what the {pack} pack's kinds and fields mean (the README, the contracts and the About card read it).\n"
            "# Written by " + GENERATOR + " from tools/packgen/genomics_showcase/spec.py.\n"
            + dump({"about": 1, "vocabulary": {}, "kinds": kinds})
            + dump({"intro": yaml.safe_load((HERE / "intros.yaml").read_text(encoding="utf-8"))[pack]["intro"]}))


def pack_yaml(pack: str, story: dict) -> str:
    p = PACKS[pack]
    ks = S.kinds_of(pack)
    connector = f"{pack}-store"
    ids = dict(story, lead_assoc=f"GWAS-RESPONSE-{story['lead_variant']}")
    m = {"pack": pack, "domain": "genomics", "version": "1.0.0", "code": p["code"], "title": p["title"], "description": p["description"],
         "kinds": [k.kind for k in ks], "sutras": "sutras",
         "mnemonics": {k.word: {"kind": k.kind, "label": k.label} for k in ks}}
    patterns = [{"pattern": "^" + k.prefix, "kind": k.kind} for k in ks if k.prefix]
    link_fields = {}
    for k in S.KINDS:
        for f in k.fields:
            if f.link and f.name not in link_fields:
                link_fields[f.name] = f.link
    fields = {name: {"kind": kind, "label": S.BY_KIND[kind].title} for name, kind in link_fields.items()
              if any(f.name == name and f.link for k in ks for f in k.fields)}
    m["graph"] = {"id-patterns": patterns, "fields": fields}
    form = {}
    for k in ks:
        rows = []
        for name in k.search:
            f = next(x for x in k.fields if x.name == name)
            if name == k.key:
                rows.append({"path": name, "type": "id", "group": "Identity"})
            elif f.enum:
                rows.append({"path": name, "type": "enum", "group": "Filters"})
            elif f.type in ("integer", "amount", "rate"):
                rows.append({"path": name, "type": "number", "group": "Filters"})
            else:
                rows.append({"path": name, "type": "text", "group": "Filters"})
        form[k.kind] = rows
    m["form"] = form
    m["console"] = {"examples": [[a.format(**ids), b] for a, b in p["examples"]]}
    m["columns"] = {k.kind: list(k.columns) for k in ks}
    m["connectors"] = {connector: {"plugin": "delta", "enabled": "${DRISHTI_LAKE_ENABLED:true}", "kinds": [k.kind for k in ks],
                                   "settings": {"lake": "demo", "lake-tables": ",".join(table_of(k) for k in ks)}}}
    m["routes"] = {k.kind: connector for k in ks}
    m["lake"] = {"tables": {table_of(k): {"id": k.key, "promote": list(k.promote)} for k in ks}}
    return (HEADER + f"\n# {p['title']} · a pack of the genomics module. Synthetic and seeded: the genes,\n"
            "# proteins, patients and designs are invented. Its data is the committed snapshot data/testdata/" + pack + "/<kind>/\n"
            "# (tools/packgen/genomics_showcase/make.py writes it), built into the demo lake by `drishti.py lake demo` and served by the Delta\n"
            f"# connector {connector}. Written by the generator from tools/packgen/genomics_showcase/spec.py: edit that, not this file.\n"
            + dump(m))


def reference(pack: str, docs: dict, story: dict) -> dict:
    """The few documents of each kind the pack's samples keep for its README: the story's own, else the first two."""
    pick = {"study-gene": "lead_gene", "study-sample": "lead_sample", "study-variant": "lead_variant", "variant-call": "lead_call",
            "association": None, "cnv-segment": "lead_segment", "pathway-gene": "lead_pathway", "protein": "lead_gene",
            "residue": "lead_residue", "residue-contact": "lead_contact", "dms-score": "lead_score", "design": "lead_design",
            "design-mutation": "lead_mutation", "design-round": "lead_round"}
    out = {}
    for k in S.kinds_of(pack):
        have = docs[k.kind]
        first = story.get(pick[k.kind]) if pick[k.kind] else f"GWAS-RESPONSE-{story['lead_variant']}"
        ids = [first] if first in have else []
        ids += [i for i in have if i not in ids][:2 - len(ids)]
        out[k.kind] = {i: have[i] for i in ids}
    return out


def catalog(ref: dict) -> list:
    out = []
    for k in S.KINDS:
        for i in ref.get(k.kind, {}):
            out.append({"kind": k.kind, "id": i, "title": f"{k.word} {i}", "subtitle": f"{k.title}"})
    return out


def pack_files(pack: str, docs: dict, story: dict, readme: bool = True) -> dict:
    """{path: text} of every text file of a pack (README last: it reads the others, its data sections the contracts on the disk)."""
    d = MODULE_DIR / "packs" / pack
    out = {d / "pack.yaml": pack_yaml(pack, story)}
    for k in S.kinds_of(pack):
        out[d / "contracts" / f"{k.kind}.contract.yaml"] = contract(k)
        ex = next(iter(reference(pack, docs, story)[k.kind]))
        f = d / "sutras" / f"{k.kind}.v1.sutra.yaml"
        out[f] = SD.enrich(sutra(k, ex), f)
    out[d / "config" / "about.yaml"] = about(pack)
    ref = reference(pack, docs, story)
    for name, text in PSAMP.files(ref, [e for e in catalog(ref)], catalog_indent=None).items():
        out[d / "samples" / name] = text
    if readme:
        out[d / "README.md"] = README.render_pack(d, files=dict(out))
    return out
