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
"""The genomics screens answer from the lake (no browser): the module's console pages draw what the module's REST API returns (rows from the
lake's tables, read through the genomics pack's connector), name no VCF folder or file, and the module's pack, the lake file and the demo data agree
on the same four tables."""
from __future__ import annotations

import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "modules" / "genomics"


def test_the_pages_draw_the_modules_api_and_name_no_vcf_file_or_folder():
    pages = [p for p in (MODULE / "console").rglob("*") if p.is_file() and p.suffix in (".html", ".js", ".yaml")]
    assert pages
    for p in pages:
        text = p.read_text(encoding="utf-8")
        assert "modules/genomics/vcf" not in text and ".vcf" not in text.replace("VCF", ""), p.relative_to(ROOT)
    js = (MODULE / "console" / "static" / "js" / "drishti_genomics-pages.js").read_text(encoding="utf-8")
    assert "/api/v1/genomics" in js and "fetch(" in js
    assert 'data-genomics-api="{{ api(' in (MODULE / "console" / "pages" / "explorer.html").read_text(encoding="utf-8")


def test_the_modules_docs_say_the_files_go_through_the_loader_into_the_lake():
    guide = (MODULE / "docs" / "guide.md").read_text(encoding="utf-8")
    assert "genomics loader" in guide and "genomicslake" in guide and "modules/genomics/vcf" not in guide
    load = (ROOT / "docs" / "guides" / "loads" / "load-genomics-files.md").read_text(encoding="utf-8")
    assert "opens no VCF file" in load and "modules/genomics/vcf" not in load and "--no-module" not in load


def test_the_pack_the_lake_file_and_the_demo_data_name_the_same_tables():
    pack = yaml.safe_load((MODULE / "packs" / "genomics" / "pack.yaml").read_text(encoding="utf-8"))
    lake = yaml.safe_load((ROOT / "config" / "lakes" / "genomicslake.yaml").read_text(encoding="utf-8"))
    tables = pack["connectors"]["genomics-store"]["settings"]["lake-tables"].split(",")
    assert tables == [f"genomics.{k}" for k in ("variant", "sample", "gene", "cohort")] and set(tables) <= set(lake["tables"])
    for kind in ("variant", "sample", "gene"):
        assert list((ROOT / "data" / "testdata" / "genomics" / kind).glob(f"{kind}.*.jsonl.gz")), kind
    assert not list(MODULE.rglob("VcfConnector*")) and not re.search(r"genomics-vcf", (MODULE / "module.yaml").read_text(encoding="utf-8"))
