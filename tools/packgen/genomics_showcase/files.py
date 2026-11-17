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

    pack.yaml, contracts/<kind>.contract.yaml, sutras/<kind>.v1.sutra.yaml, config/about.yaml, config/help.yaml, samples/ (a few
    reference documents and the catalogue), guides/<pack>.md and README.md

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


def _load(name: str, path: Path):
    s = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(s)
    sys.modules[name] = m
    s.loader.exec_module(m)
    return m


README = _load("drishti_pack_readme", ROOT / "tools" / "packgen" / "common" / "readme.py")
GD = _load("drishti_guide_data", ROOT / "tools" / "packgen" / "common" / "guide_data.py")

MODULE_DIR = ROOT / "modules" / "genomics"
GENERATOR = "tools/packgen/genomics_showcase/make.py"
HEADER = "\n".join((HERE / "spec.py").read_text(encoding="utf-8").splitlines()[:13]) + "\n"
SOURCE = ("synthetic: the seeded generator " + GENERATOR + " (nothing real: the genes, patients, protein and designs are invented)")

PACKS = {
    S.GENOMICS: {
        "code": "GENOMICS", "title": "Genomics showcase", "intro": "tools/packgen/genomics_showcase/readme/genomics-showcase.intro.md",
        "description": ("A synthetic cohort of 1,500 patients read against one invented gene, HLXK7: 2,000 genes, 12,000 variant sites, 45,000 "
                        "variant calls, copy-number segments and pathways, and 36,000 association tests of three traits computed from "
                        "the calls. The words ASSOC (a variant's test against a trait), SGENE, SSAMPLE, SVARIANT, VCALL, CNV and PATHWAY "
                        "open the records; the showcase's screens are drawn from them. Everything is seeded and invented."),
        "examples": [("ASSOC {lead_assoc}", "The lead variant of the response peak: its p-value, effect and link to the peak's lead"),
                     ("SGENE HLXK7", "The gene: chromosome, role, protein length and how many samples carry a damaging change"),
                     ("SVARIANT {lead_variant}", "The variant at the first hotspot of HLXK7, with its amino acid change and carriers"),
                     ("SSAMPLE S0001", "One patient: the clinical tracks, the response and the cluster"),
                     ("VCALL {lead_call}", "One call: sample, variant, genotype, allele fraction and depth")],
        "about": "tools/packgen/genomics_showcase/readme/genomics-showcase.intro.md"},
    S.PROTEIN: {
        "code": "PROTENG", "title": "Protein engineering showcase", "intro": "tools/packgen/genomics_showcase/readme/protein-engineering.intro.md",
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
        "about": "tools/packgen/genomics_showcase/readme/protein-engineering.intro.md"},
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
    rules = [{"rule": "required", "fields": [f.name for f in k.fields if f.presence == "required"]},
             {"rule": "unique", "key": [k.key], "per": "snapshot"}]
    rules += [{"rule": "link", "field": x["field"], "kind": x["kind"]} for x in links]
    rules += [{"rule": "enum", "field": f.name, "values": list(f.enum)} for f in k.fields if f.enum]
    c["validation"] = rules
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
    s = {"rachana": 1, "sutra": k.kind, "version": 1,
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
        kinds[k.kind] = {"title": k.title, "about": ABOUT[k.kind], "guide": pack, "glossary": gl}
    return (HEADER + f"\n# About this page: what the {pack} pack's kinds and fields mean (the README, the contracts and the About card read it).\n"
            "# Written by " + GENERATOR + " from tools/packgen/genomics_showcase/spec.py.\n"
            + dump({"about": 1, "vocabulary": {}, "kinds": kinds}))


def help_yaml(pack: str) -> str:
    p = PACKS[pack]
    words = ", ".join(k.word for k in S.kinds_of(pack))
    g = {"slug": f"{pack}-pack", "category": "packs", "title": f"The {pack} pack", "kind": "guide", "icon": "diagram-2",
         "file": f"guides/{pack}.md",
         "summary": f"{p['description'].split('. ')[0]}. Words: {words}."}
    return HEADER + f"\n# The {pack} pack's Help entries. Written by {GENERATOR}.\n" + dump({"guides": [g]})


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
    m["console"] = {"examples": [[a.format(**ids), b] for a, b in p["examples"]], "help": "config/help.yaml"}
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


GUIDE_TEXT = {
    S.GENOMICS: """# The genomics-showcase pack

A synthetic cohort of 1,500 patients read against one invented gene, `HLXK7`. It is a pack of the **genomics module**
(`modules/genomics/`), made to show Drishti outside finance: the same words, links, pages and Explain drill-down over genes, variants,
samples and association tests instead of trades and curves. Every record is invented (the genes `HLX…`, the patients, the numbers) and
every screen that shows it says *Synthetic*.

## What is in it

| Kind | Rows | One row is |
|---|---|---|
| `study-gene` | 2,000 | a gene; 24 are the planted drivers, `HLXK7` the strongest |
| `study-sample` | 1,500 | a patient's sample of cohort ONC-1, with its clinical tracks, response and place on the map of samples |
| `study-variant` | 12,000 | a variant site, with the amino acid change that joins it to the protein workbench's scan |
| `variant-call` | 45,000 | a sample carrying a variant: genotype, allele fraction, depth |
| `association` | 36,000 | a variant tested against a trait (RESPONSE, TOXICITY, RELAPSE) |
| `cnv-segment` | 3,000 | an amplification, deletion, gain or loss in one sample |
| `pathway-gene` | 500 | a gene in one of twelve pathways |

The tests are **computed**, not painted: each association is a Wald test of the log odds ratio of the generated calls against the
generated traits, so the peak on the association plot, the figures on a page and the records behind them agree by construction. The
peaks are planted by the generator's model (an altered `HLXK7` cuts the response sharply; three more drivers move it less; one
driver each moves toxicity and relapse), and the neighbours of each peak gene carry variants linked to its lead.

## The words

`ASSOC GWAS-RESPONSE-<variant>` opens one test; `SGENE HLXK7`, `SSAMPLE S0001`, `SVARIANT <id>`, `VCALL <id>`, `CNV <id>` and
`PATHWAY <id>` open the other records. Each is a plain record page (the strip and a table of every field, linked fields opening their
kinds). The showcase's own screens (the association plot, the cohort matrix, the map of samples, the variant lollipop) are drawn from
the same kinds by the genomics module, opened by its own words GWAS, PROTEIN and DESIGNS.

## The join to proteins

`study-variant` carries the gene, the protein position and the amino acid after the change. With the gene as the protein's id, those
three values are the key of a scan score in the [protein-engineering pack](../../protein-engineering/guides/protein-engineering.md): every variant of a gene that has a
protein has a measured functional reading.

## Making the data again

`python3 tools/packgen/genomics_showcase/make.py` writes the committed data (`data/testdata/genomics-showcase/<kind>/`, gzipped JSON
Lines) and this pack's contracts, Sutras, samples, README and guide from one seed (`--seed`, default 20261116). The same seed gives
byte-identical files; `--check` fails when the committed files differ from what the generator would write.
""",
    S.PROTEIN: """# The protein-engineering pack

Four invented proteins and the campaign that redesigned one of them: **Helixase-7**, the enzyme of gene `HLXK7` (312 amino acids). A
pack of the **genomics module** (`modules/genomics/`), the other half of the showcase: genomics reads the changes nature made, protein
engineering writes our own, and the join is the protein. Every record is invented and every screen that shows it says *Synthetic*.

## What is in it

| Kind | Rows | One row is |
|---|---|---|
| `protein` | 4 | a protein: sequence, family, active site (lengths 312, 248, 286, 350) |
| `residue` | 1,196 | a position: fold, domain, burial, coordinates, the mean effect of changing it |
| `residue-contact` | 5,952 | two positions close in space: the contact map's cells |
| `dms-score` | 47,840 | a scan measurement: position, amino acid, assay (activity or stability) |
| `design` | 12,000 | a designed variant of Helixase-7: eight rounds of 1,500 |
| `design-mutation` | 33,000 | one amino acid change of one design |
| `design-round` | 8 | a round: method, best and median activity, designs caught on the false peak |

The fold's coordinates are generated (helices and strands laid on a grid and joined by loops); burial, contacts and the active site are
**measured** from those coordinates. The scan comes from a small physical model: buried positions and the active site are intolerant, a
like-for-like change is mild, proline breaks helices and strands, and 14 beneficial changes sit on the surface. The campaign's activity
follows a fitness field with a summit, a false peak and a ridge; its stability follows the scan.

## The words

`PROT HLXK7` opens a protein; `DESIGN <id>` a design; `RESIDUE`, `DMS`, `CONTACT`, `DMUT` and `DROUND` the other records. Each is
a plain record page, linked fields opening their kinds. The workbench's own screens (the scan heatmap, the structure, the design
campaign with its rounds, the leaderboard) are drawn from the same kinds by the genomics module, opened by its own words GWAS, PROTEIN and DESIGNS.

## The join to genomics

The [genomics-showcase pack](../../genomics-showcase/guides/genomics-showcase.md)'s `study-variant` carries a gene, a protein position and an amino acid; with the gene
as the protein's id they are the key of a `dms-score` row.

## Making the data again

One generator writes both packs: `python3 tools/packgen/genomics_showcase/make.py` (see the genomics-showcase guide).
""",
}


def guide(pack: str, pack_dir: Path) -> str:
    return (README.licence_comment().rstrip() + "\n" + GUIDE_TEXT[pack].rstrip() + "\n\n" + "\n".join(GD.sections(pack_dir)).rstrip()
            + "\n")


def pack_files(pack: str, docs: dict, story: dict, guide_sections: bool = True) -> dict:
    """{path: text} of every text file of a pack (README last: it reads the others)."""
    d = MODULE_DIR / "packs" / pack
    out = {d / "pack.yaml": pack_yaml(pack, story)}
    for k in S.kinds_of(pack):
        out[d / "contracts" / f"{k.kind}.contract.yaml"] = contract(k)
        ex = next(iter(reference(pack, docs, story)[k.kind]))
        out[d / "sutras" / f"{k.kind}.v1.sutra.yaml"] = sutra(k, ex)
    out[d / "config" / "about.yaml"] = about(pack)
    out[d / "config" / "help.yaml"] = help_yaml(pack)
    ref = reference(pack, docs, story)
    for name, text in PSAMP.files(ref, [e for e in catalog(ref)], catalog_indent=None).items():
        out[d / "samples" / name] = text
    if guide_sections:
        out[d / "guides" / f"{pack}.md"] = guide(pack, d)
    out[d / "README.md"] = README.render_pack(d, files=dict(out), intro=ROOT / PACKS[pack]["intro"])
    return out
