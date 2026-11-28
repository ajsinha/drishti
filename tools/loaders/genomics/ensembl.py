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


"""The ensembl feed: gene annotations of a small gene panel from the Ensembl REST API, as rows of the genomics module's ``gene`` kind.

    python3 tools/drishti.py feed run ensembl --dry-run
    python3 tools/drishti.py feed run ensembl                          the panel (default BRCA1, BRCA2, TP53), loaded into genomicslake
    python3 tools/drishti.py feed run ensembl --set genes=MLH1,APC,MSH2

Source: https://rest.ensembl.org, GET /lookup/symbol/homo_sapiens/<SYMBOL>?expand=1 (GRCh38). Ensembl data is open and free to use and
redistribute (EMBL-EBI terms of use: no restriction, attribution to Ensembl appreciated); the rate limit is 15 requests a second, the feed
asks for about 5. One request per gene.

Mapping: GENE is the symbol; CHROM, START (1-based, GRCh38), END and STRAND (+ or -) are Ensembl's; PROTEIN_LENGTH is the amino acids of the
canonical transcript's translation (empty for a gene with none); DOMAINS stays empty (Ensembl lookup holds none: a gene.jsonl of your own
can carry them); VARIANTS is empty (the module counts them from the variants). Extra columns: ENSEMBL_ID, BIOTYPE, DESCRIPTION, CANONICAL_TRANSCRIPT.
"""
from __future__ import annotations

import re
import sys
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent / "common")]
import feedbase as FB  # noqa: E402
import genomics_kit as G  # noqa: E402

BASE = "https://rest.ensembl.org"


def row_of(doc: dict) -> dict | None:
    """One gene row from an Ensembl lookup document; None when it is not a gene on a chromosome."""
    if not doc or doc.get("object_type", "Gene") != "Gene" or not doc.get("display_name"):
        return None
    canon = (doc.get("canonical_transcript") or "")
    plain = canon.split(".")[0]
    length = None
    for t in doc.get("Transcript") or []:
        if t.get("id") == plain or t.get("id") == canon:
            length = (t.get("Translation") or {}).get("length")
    return {"GENE": doc["display_name"], "CHROM": str(doc.get("seq_region_name", "")), "START": doc.get("start"), "END": doc.get("end"),
            "STRAND": "+" if doc.get("strand") == 1 else "-", "PROTEIN_LENGTH": length, "DOMAINS": [], "VARIANTS": None,
            "ENSEMBL_ID": doc.get("id", ""), "BIOTYPE": doc.get("biotype", ""), "CANONICAL_TRANSCRIPT": canon,
            "DESCRIPTION": re.sub(r"\s*\[Source:.*\]$", "", doc.get("description") or "")}


class EnsemblFeed(FB.Feed):
    def panel(self, run) -> list[str]:
        raw = run.option("genes", ["BRCA1", "BRCA2", "TP53"])
        genes = [g.strip().upper() for g in (raw.split(",") if isinstance(raw, str) else raw) if str(g).strip()]
        bad = [g for g in genes if not re.match(r"^[A-Z0-9][A-Z0-9-]{0,14}$", g)]
        if bad or not genes:
            raise FB.FeedRefused(f"feed ensembl: genes {bad or '(none)'} are not gene symbols (BRCA1, TP53 ...)")
        return genes

    def url(self, gene: str) -> str:
        return f"{BASE}/lookup/symbol/homo_sapiens/{urllib.parse.quote(gene)}?expand=1"

    def plan(self, run):
        return [self.url(g) for g in self.panel(run)]

    def pull(self, run):
        rows = []
        for gene in self.panel(run):
            doc = run.get_json(self.url(gene), name=f"gene-{gene}.json", headers={"Content-Type": "application/json"})
            row = row_of(doc or {})
            if row is None:
                run.log(f"  ensembl: {gene} not found (a symbol Ensembl does not know), left out")
                continue
            checked, why = G.check_row("gene", row)
            if why:
                run.log(f"  ensembl: {gene} left out: {why}")
            else:
                rows.append(checked)
        if not rows:
            raise FB.FeedError("ensembl returned no gene for the panel: nothing written")
        run.emit("gene", rows)
        run.mark("gene", run.business_date)
