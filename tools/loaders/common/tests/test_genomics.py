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


"""The genomics loader (tools/loaders/genomics/): the clinvar and ensembl feeds against recorded responses (never the network), the readers
of VCF and gene and sample tables, the row checks against the contracts, and your own files through genomics_run.py (an inbox of a tiny
VCF, a gene CSV and a sample JSONL), loaded by the inbox route into the lake the module's screens read."""
from __future__ import annotations

import gzip
import io
import json
import shutil
import sys
from datetime import date
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
GEN = HERE.parents[1] / "genomics"
sys.path[:0] = [str(HERE.parent), str(GEN)]
import clinvar as CV  # noqa: E402
import ensembl as EN  # noqa: E402
import feedbase as FB  # noqa: E402
import feedrunner as R  # noqa: E402
import genomics_kit as G  # noqa: E402
import genomics_run as GR  # noqa: E402

FX = HERE / "fixtures" / "genomics"
DAY = date(2026, 11, 7)


@pytest.fixture(autouse=True)
def data(tmp_path, monkeypatch):
    d = tmp_path / "data"
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(d))
    monkeypatch.delenv("GENOMICS_INBOX_DIR", raising=False)
    return d


class Recorded:
    """An opener for pullkit.Http that answers from the recorded files; any other URL is a test failure (no network)."""

    def __init__(self):
        self.urls = []

    def __call__(self, req, timeout=0):
        url = req.full_url
        self.urls.append(url)
        if "esearch" in url and "BRCA1" in url:
            body = (FX / "clinvar_search_BRCA1.json").read_bytes()
        elif "esummary" in url:
            body = (FX / "clinvar_summary_BRCA1.json").read_bytes()
        elif "lookup/symbol/homo_sapiens/TP53" in url:
            body = (FX / "ensembl_TP53.json").read_bytes()
        else:
            raise AssertionError(f"a test reached for {url}")

        class Resp(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False
        return Resp(body)


def feed(name, **sets):
    logs = []
    op = Recorded()
    o = R.RunOptions(business_date=DAY, load="none", sets=sets)
    rc, entry = R.run_feed(FB.load_spec(name), o, log=logs.append, opener=op, sleep=lambda s: None)
    return rc, entry, logs, op


def rows(data, feed_name, table, day="2026-11-07"):
    with gzip.open(data / "datafeeds" / feed_name / day / f"{table}.{day}.jsonl.gz", "rt", encoding="utf-8") as fh:
        return [json.loads(x) for x in fh]


# ---- the feeds' files and the feed files

@pytest.mark.parametrize("name", ["clinvar", "ensembl"])
def test_the_feed_files_are_valid_and_carry_their_licence(name):
    spec = FB.load_spec(name)
    assert spec.lake == "genomicslake" and spec.licence.url and spec.licence.redistribution in ("allowed", "attribution")
    assert not spec.internal_only
    assert {d.pack for d in spec.datasets.values()} == {"genomics"}
    assert FB.feed_class(spec).__name__ in ("ClinVarFeed", "EnsemblFeed")


def test_the_lake_file_lists_the_four_kinds():
    import yaml
    doc = yaml.safe_load((FB.ROOT / "config" / "lakes" / "genomicslake.yaml").read_text(encoding="utf-8"))
    # the four kinds, and the two feeds' own tables (the module's Home reads them by these names), each of a kind the contracts know
    assert set(doc["tables"]) == {f"genomics.{k}" for k in G.KINDS} | {"genomics.clinvar-variant", "genomics.ensembl-gene"}
    assert {t["id"] for t in doc["tables"].values()} == {G.contract(k)["key"] for k in G.KINDS}
    assert doc["tables"]["genomics.clinvar-variant"]["kind"] == "variant" and doc["tables"]["genomics.ensembl-gene"]["kind"] == "gene"


# ---- clinvar

def test_the_clinvar_feed_writes_variant_rows_in_the_contract(data):
    rc, entry, logs, op = feed("clinvar", genes="BRCA1")
    assert rc == 0, logs
    got = {r["CLINVAR_ID"]: r for r in rows(data, "clinvar", "clinvar-variant")}
    assert len(got) == 3 and all("api_key" not in u for u in op.urls)
    r = got["VCV005042465"]
    assert (r["CHROM"], r["POS"], r["REF"], r["ALT"]) == ("17", 43094820, "TT", "T")           # SPDI is 0-based: position + 1
    assert (r["GENE"], r["IMPACT"], r["PROTEIN_POS"], r["VARIANT_ID"]) == ("BRCA1", "HIGH", 237, "17-43094820-TT-T")
    assert r["CLNSIG"] == "Pathogenic" and r["CONDITIONS"] and r["LAST_EVALUATED"] == "2026-09-18"
    assert set(G.contract("variant")["fields"]) <= set(r)


def test_clinvar_mapping_helpers():
    assert CV.spdi("NC_000017.11:7670673:ATTC:A") == ("17", 7670674, "ATTC", "A")
    assert CV.spdi("NC_000023.11:100:5:G") == ("X", 101, "", "G")
    assert CV.spdi("NG_012345.1:100:A:G") is None and CV.spdi("") is None
    assert [CV.impact(x) for x in ("frameshift variant", "nonsense", "missense variant", "3 prime UTR variant", "")] == \
        ["HIGH", "HIGH", "MODERATE", "LOW", "LOW"]
    assert CV.row_of({"title": "x", "variation_set": [{"canonical_spdi": ""}]}) is None


def test_clinvar_refuses_a_gene_that_is_not_a_symbol():
    rc, entry, logs, op = feed("clinvar", genes="BRCA1; DROP")
    assert rc == FB.REFUSED and "not gene symbols" in entry["error"] and op.urls == []


def test_a_clinvar_dry_run_names_the_requests_and_writes_nothing(data):
    logs = []
    rc, entry = R.run_feed(FB.load_spec("clinvar"), R.RunOptions(business_date=DAY, dry_run=True), log=logs.append)
    assert rc == 0 and sum("esearch" in x for x in logs) == 3 and not (data / "datafeeds" / "clinvar").exists()


# ---- ensembl

def test_the_ensembl_feed_writes_a_gene_row(data):
    rc, entry, logs, op = feed("ensembl", genes="TP53")
    assert rc == 0, logs
    (g,) = rows(data, "ensembl", "ensembl-gene")
    assert (g["GENE"], g["CHROM"], g["START"], g["STRAND"], g["PROTEIN_LENGTH"]) == ("TP53", "17", 7661779, "-", 393)
    assert g["ENSEMBL_ID"] == "ENSG00000141510" and "Source:" not in g["DESCRIPTION"] and g["DOMAINS"] == []


def test_ensembl_row_of_ignores_what_is_not_a_gene():
    assert EN.row_of({}) is None and EN.row_of({"object_type": "Transcript", "display_name": "x"}) is None


# ---- the readers and the checks

def test_the_vcf_reader_follows_the_modules_rules():
    variants, samples, problems = G.read_vcf(FX / "trio_2026-11-26.vcf")
    ids = [v["VARIANT_ID"] for v in variants]
    assert ids == ["rs111", "17-7676154-G-C", "17-7676154-G-T", "13-32340300-A-G"] and problems == []
    by = {v["VARIANT_ID"]: v for v in variants}
    assert by["rs111"]["SAMPLES"] == ["MOM", "KID"] and by["rs111"]["AF"] == round(2 / 3, 4) and by["rs111"]["PROTEIN_POS"] == 237
    assert by["17-7676154-G-C"]["SAMPLES"] == ["MOM"] and by["17-7676154-G-T"]["SAMPLES"] == ["MOM"]       # 1/2 carries allele 1 and 2
    assert by["13-32340300-A-G"]["SAMPLES"] == ["KID"] and by["13-32340300-A-G"]["QUAL"] is None
    kid = next(s for s in samples if s["SAMPLE_ID"] == "KID")
    assert (kid["COHORT"], kid["SEX"], kid["VARIANTS"]) == ("FAM1", "F", 2)


def test_a_gzipped_vcf_reads_the_same(tmp_path):
    gz = tmp_path / "trio.vcf.gz"
    gz.write_bytes(gzip.compress((FX / "trio_2026-11-26.vcf").read_bytes()))
    assert G.read_vcf(gz)[0] == G.read_vcf(FX / "trio_2026-11-26.vcf")[0]


def test_a_file_that_is_not_a_vcf_is_refused_in_words(tmp_path):
    p = tmp_path / "x.vcf"
    p.write_text("##fileformat=VCFv4.2\n", encoding="utf-8")
    with pytest.raises(G.GenomicsError, match="no #CHROM header"):
        G.read_vcf(p)
    p.write_text("hello\n", encoding="utf-8")
    with pytest.raises(G.GenomicsError, match="before the #CHROM"):
        G.read_vcf(p)


def test_check_row_types_and_refusals():
    row, why = G.check_row("gene", {"GENE": "X", "START": "12", "PROTEIN_LENGTH": "3.0", "DOMAINS": "a;b"})
    assert why is None and row["START"] == 12 and row["PROTEIN_LENGTH"] == 3 and row["DOMAINS"] == ["a", "b"]
    assert "START" in G.check_row("gene", {"GENE": "X", "START": "abc"})[1]
    assert "key" in G.check_row("gene", {"START": 1})[1]
    assert "is not integer" in G.check_row("variant", {"VARIANT_ID": "v", "POS": 1.5})[1]


# ---- your own files

def inbox_with(tmp_path, *names):
    box = tmp_path / "inbox"
    box.mkdir()
    for n in names:
        shutil.copyfile(FX / n, box / n)
    return box


def run_own(*argv):
    logs = []
    code = GR.run(GR.parser().parse_args(list(argv)), log=logs.append)
    return code, logs


def test_a_name_decides_the_kind_and_the_date():
    assert GR.split_name(Path("trio_2026-11-26.vcf.gz")) == ("vcf", "trio", date(2026, 11, 26))
    assert GR.split_name(Path("genes.20261126.csv"))[0::2] == ("gene", date(2026, 11, 26))
    assert GR.split_name(Path("Samples.jsonl.gz"))[0::2] == ("sample", None)
    for bad in ("notes.txt.bak", "panel.csv", "genes_2026-13-45.csv"):
        with pytest.raises(G.GenomicsError):
            GR.split_name(Path(bad))


def test_the_inbox_run_loads_by_the_inbox_route_into_the_lake(tmp_path, data):
    box = inbox_with(tmp_path, "trio_2026-11-26.vcf", "genes_2026-11-26.csv", "samples_2026-11-26.jsonl")
    inbox = tmp_path / "dataload"
    code, logs = run_own("--from-dir", str(box), "--load", "inbox", "--inbox", str(inbox))
    assert code == GR.REJECTS, logs                       # the bad gene row and the sample row without an id are refused, the rest loaded
    out = data / "genomics" / "loads" / "2026-11-26"
    man = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert {f["kind"]: f["rows"] for f in man["files"]} == {"variant": 4, "sample": 3, "gene": 2} and man["rejected"] == {"gene": 1, "sample": 1}
    assert (out / "gene.2026-11-26.rejects.jsonl").is_file()
    tar_or_file = sorted(p.name for p in inbox.iterdir())
    assert any(n.endswith(".tar") for n in tar_or_file) and any(n.endswith(".tar.ready") for n in tar_or_file)
    import yaml
    ready = yaml.safe_load(next(inbox.glob("*.ready")).read_text(encoding="utf-8"))
    assert ready["lake"] == "genomicslake" and ready["folders"] == {"variant": "genomics", "sample": "genomics", "gene": "genomics"}
    assert not (data / "modules").exists()                    # nothing for the module's screens beside the lake: they read the lake tables
    assert sorted(p.name for p in (box / "done" / "2026-11-26").iterdir()) == sorted(["trio_2026-11-26.vcf", "genes_2026-11-26.csv", "samples_2026-11-26.jsonl"])
    kid = [json.loads(x) for x in gzip.open(out / "sample.2026-11-26.jsonl.gz", "rt")]
    assert next(s for s in kid if s["SAMPLE_ID"] == "KID")["VARIANTS"] == 9                    # the sample table's row replaced the VCF's


def test_a_second_run_of_the_same_date_replaces_the_date(tmp_path, data):
    box = tmp_path / "box"
    box.mkdir()
    for _ in range(2):
        shutil.copyfile(FX / "trio_2026-11-26.vcf", box / "trio_2026-11-26.vcf")
        code, logs = run_own("--from-dir", str(box), "--load", "none")
        assert code == 0, logs
    rows_ = [json.loads(x) for x in gzip.open(data / "genomics" / "loads" / "2026-11-26" / "variant.2026-11-26.jsonl.gz", "rt")]
    assert len(rows_) == 4


def test_a_dry_run_changes_nothing_and_an_unreadable_file_goes_to_rejected(tmp_path, data):
    box = inbox_with(tmp_path, "trio_2026-11-26.vcf")
    (box / "junk.csv").write_text("a,b\n", encoding="utf-8")
    code, logs = run_own("--from-dir", str(box), "--dry-run")
    assert code == GR.REJECTS and (box / "trio_2026-11-26.vcf").is_file() and not (data / "genomics").exists()
    code, logs = run_own("--from-dir", str(box), "--load", "none")
    assert code == GR.REJECTS and "starts with gene, sample or cohort" in (box / "rejected" / "junk.csv.why.txt").read_text()


def test_an_empty_drop_folder_is_nothing_to_do(tmp_path):
    box = tmp_path / "empty"
    box.mkdir()
    assert run_own("--from-dir", str(box))[0] == GR.NOTHING


def test_a_lake_name_is_known_and_the_loader_names_no_path(tmp_path):
    text = (GEN / "genomics_run.py").read_text(encoding="utf-8")
    assert "/home/" not in text and "C:\\" not in text


def test_the_screens_read_the_lake_through_the_packs_connector_and_no_module_folder():
    """The module's screens answer from genomicslake: the pack's connector reads the four tables, the demo data is built into them, and nothing
    of the module or the loader names the old <data>/modules/genomics/vcf folder."""
    import yaml
    root = G.ROOT
    pack = yaml.safe_load((root / "modules" / "genomics" / "packs" / "genomics" / "pack.yaml").read_text(encoding="utf-8"))
    lake = yaml.safe_load((root / "config" / "lakes" / "genomicslake.yaml").read_text(encoding="utf-8"))
    conn = pack["connectors"]["genomics-store"]
    assert conn["plugin"] == "delta" and conn["settings"]["lake"] == "genomicslake"
    assert conn["settings"]["lake-tables"].split(",") == ["genomics." + k for k in G.KINDS] and set(conn["settings"]["lake-tables"].split(",")) <= set(lake["tables"])
    assert pack["routes"] == {k: "genomics-store" for k in G.KINDS} and pack["kinds"] == list(G.KINDS)
    assert "genomics" in yaml.safe_load((root / "modules" / "genomics" / "module.yaml").read_text(encoding="utf-8"))["packs"]["list"]
    manifest = yaml.safe_load((root / "data" / "testdata" / "testdata.yaml").read_text(encoding="utf-8"))
    mine = {d["kind"]: d for d in manifest["datasets"] if d["pack"] == "genomics"}
    assert set(mine) == {"variant", "sample", "gene"} and all(d["lake"] == "genomicslake" and d["connector"] == "genomics-store" for d in mine.values())
    assert {k: d["documents"] for k, d in mine.items()} == {"variant": 120, "sample": 24, "gene": 8}
    for f in list((root / "modules" / "genomics" / "java").rglob("main/**/*.java")) + [p for p in (root / "tools" / "loaders" / "genomics").glob("*.py") if p.name == "genomics_run.py"]:
        text = f.read_text(encoding="utf-8")
        assert "modules/genomics/vcf" not in text and '"vcf"' not in text, f
