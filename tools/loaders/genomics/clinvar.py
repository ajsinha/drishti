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


"""The clinvar feed: the variants of NCBI ClinVar for a small gene panel, as rows of the genomics module's ``variant`` kind.

    python3 tools/drishti.py feed run clinvar --dry-run
    python3 tools/drishti.py feed run clinvar                          the panel (default BRCA1, BRCA2, TP53), loaded into genomicslake
    python3 tools/drishti.py feed run clinvar --set genes=MLH1,APC     another panel
    python3 tools/drishti.py feed run clinvar --set max-per-gene=500   more variants of each gene (default 200)

Source: NCBI Entrez E-utilities (esearch + esummary, database clinvar). ClinVar is in the public domain (NCBI's data policy); NCBI asks for
at most 3 requests a second without an API key (10 with NCBI_API_KEY, optional) and a tool name and contact in each request, which the
shared client sends as the User-Agent. One run is 2 requests per gene plus one esummary per 100 variants: a default run is about 10 requests.

Mapping to the contract (docs of the feed: config/feeds/clinvar.md): the position and alleles come from ClinVar's canonical SPDI on GRCh38
(NC_0000nn.11:<0-based position>:<deleted>:<inserted>, so POS is position + 1; a length instead of bases for the deleted part leaves REF empty);
GENE is the gene ClinVar sorts the record under; CONSEQUENCE is the first of ClinVar's molecular consequences with its spaces turned
into '_' (missense_variant ...); IMPACT is the loader's reading of that term: HIGH for nonsense, frameshift, splice and start/stop changes,
MODERATE for missense and in-frame changes, LOW for everything else; PROTEIN_POS comes from the protein change in the title (p.Arg175His -> 175).
Extra columns carry what ClinVar says: CLINVAR_ID (VCV), CLNSIG, REVIEW_STATUS, CONDITIONS, LAST_EVALUATED, TITLE.
"""
from __future__ import annotations

import re
import sys
import urllib.parse
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent / "common")]
import feedbase as FB  # noqa: E402
import genomics_kit as G  # noqa: E402

BASE = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"
CHROMS = {f"NC_{i:06d}": str(i) for i in range(1, 23)} | {"NC_000023": "X", "NC_000024": "Y", "NC_012920": "MT"}
HIGH = ("nonsense", "frameshift", "splice", "stop", "start_lost", "initiator", "start lost", "stop lost", "stop gained")
MODERATE = ("missense", "inframe", "in-frame", "in_frame")
PROTEIN = re.compile(r"p\.[A-Za-z]{3}(\d+)")


def spdi(text: str):
    """(chromosome, 1-based position, ref, alt) from ``NC_000017.11:7670673:A:G``; None when it is not a sequence change on a chromosome."""
    parts = (text or "").split(":")
    if len(parts) != 4 or not parts[1].isdigit():
        return None
    chrom = CHROMS.get(parts[0].split(".")[0])
    if chrom is None:
        return None
    ref = parts[2] if parts[2].isalpha() else ""
    alt = parts[3] if parts[3].isalpha() else ""
    return chrom, int(parts[1]) + 1, ref, alt


def impact(term: str) -> str:
    t = (term or "").lower()
    if any(x in t for x in HIGH):
        return "HIGH"
    if any(x in t for x in MODERATE):
        return "MODERATE"
    return "LOW"


def row_of(doc: dict) -> dict | None:
    """One variant row from an esummary record of ClinVar, or None when it has no position on GRCh38."""
    var = (doc.get("variation_set") or [{}])[0]
    loc = spdi(var.get("canonical_spdi", ""))
    if loc is None:
        return None
    chrom, pos, ref, alt = loc
    csq = next(iter(doc.get("molecular_consequence_list") or []), "")
    gene = doc.get("gene_sort") or next((g.get("symbol") for g in doc.get("genes") or []), "")
    cls = doc.get("germline_classification") or {}
    when = (cls.get("last_evaluated") or "").split(" ")[0].replace("/", "-")
    m = PROTEIN.search(doc.get("title", "") or "")
    return {"VARIANT_ID": f"{chrom}-{pos}-{ref}-{alt}", "CHROM": chrom, "POS": pos, "REF": ref, "ALT": alt, "QUAL": None, "FILTER": "",
            "GENE": gene, "CONSEQUENCE": csq.replace(" ", "_"), "IMPACT": impact(csq), "PROTEIN_POS": int(m.group(1)) if m else None,
            "CARRIERS": None, "AF": None, "SAMPLES": [], "CLINVAR_ID": doc.get("accession", ""), "CLNSIG": cls.get("description", ""),
            "REVIEW_STATUS": cls.get("review_status", ""),
            "CONDITIONS": sorted({t.get("trait_name", "") for t in cls.get("trait_set") or [] if t.get("trait_name")}),
            "LAST_EVALUATED": when if re.match(r"^\d{4}-\d{2}-\d{2}$", when) else "", "TITLE": doc.get("title", "")}


class ClinVarFeed(FB.Feed):
    """ClinVar for a gene panel: pathogenic and likely pathogenic variants (option ``significance``) of each gene (option ``genes``)."""

    def panel(self, run) -> list[str]:
        raw = run.option("genes", ["BRCA1", "BRCA2", "TP53"])
        genes = [g.strip().upper() for g in (raw.split(",") if isinstance(raw, str) else raw) if str(g).strip()]
        bad = [g for g in genes if not re.match(r"^[A-Z0-9][A-Z0-9-]{0,14}$", g)]
        if bad or not genes:
            raise FB.FeedRefused(f"feed clinvar: genes {bad or '(none)'} are not gene symbols (BRCA1, TP53 ...)")
        return genes

    def term(self, run, gene: str) -> str:
        sig = run.option("significance", ["pathogenic", "likely pathogenic"])
        sig = sig.split(",") if isinstance(sig, str) else sig
        clause = " OR ".join(f'"{s.strip()}"[clinsig]' for s in sig if s.strip())
        return f"{gene}[gene] AND ({clause})" if clause else f"{gene}[gene]"

    def url(self, run, path: str, **q) -> str:
        key = run.key("ncbi") if "ncbi" in self.spec.keys else ""
        if key:
            q["api_key"] = key
        return f"{BASE}/{path}?" + urllib.parse.urlencode(q)

    def plan(self, run):
        top = int(run.option("max-per-gene", 200))
        return [self.url(run, "esearch.fcgi", db="clinvar", term=self.term(run, g), retmax=top, retmode="json") for g in self.panel(run)]

    def pull(self, run):
        top = int(run.option("max-per-gene", 200))
        if top < 1 or top > 5000:
            raise FB.FeedRefused("feed clinvar: max-per-gene must be 1 to 5000")
        rows, seen = [], set()
        for gene in self.panel(run):
            found = run.get_json(self.url(run, "esearch.fcgi", db="clinvar", term=self.term(run, gene), retmax=top, retmode="json"),
                                 name=f"search-{gene}-{top}.json")
            ids = ((found or {}).get("esearchresult") or {}).get("idlist") or []
            run.log(f"  clinvar {gene}: {len(ids)} of {((found or {}).get('esearchresult') or {}).get('count', '?')} variants")
            for i in range(0, len(ids), 100):
                part = ids[i:i + 100]
                doc = run.get_json(self.url(run, "esummary.fcgi", db="clinvar", id=",".join(part), retmode="json"),
                                   name=f"summary-{gene}-{i}-{part[0]}.json")
                res = (doc or {}).get("result") or {}
                for uid in part:
                    row = row_of(res.get(uid) or {})
                    if row and row["VARIANT_ID"] not in seen:
                        seen.add(row["VARIANT_ID"])
                        rows.append(row)
        good = []
        for r in rows:
            checked, why = G.check_row("variant", r)
            if why:
                run.log(f"  clinvar: {r.get('CLINVAR_ID')} left out: {why}")
            else:
                good.append(checked)
        if not good:
            raise FB.FeedError("clinvar returned no variant with a position for the panel: nothing written (a gene symbol NCBI does not know?)")
        run.emit("variant", good)
        run.mark("variant", run.business_date)
