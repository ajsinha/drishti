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


"""What the genomics loader shares: the contracts of the genomics module's kinds, one reader of VCF files and of gene and sample tables, and
the check of every row against its contract. It reads files and returns rows; it writes nothing and talks to no server (the feeds
clinvar.py and ensembl.py, and genomics_run.py for your own files, use it).

    contract(kind)                the kind's contract (modules/genomics/packs/genomics/contracts/<kind>.yaml): fields, types, key
    check_row(kind, row)          (the row with its values in the contract's types, None) or (None, the reason it is refused)
    read_vcf(path)                (variant rows, sample rows) of a VCF 4.x file, plain or .gz, the one VCF reader of the product
    read_table(path, kind)        the rows of a CSV or JSON Lines file (plain or .gz), cells turned into the contract's types

The VCF rules (the loader is the one VCF reader): one row per position and alternate allele; the id
is the ID column, else CHROM-POS-REF-ALT (with several alternate alleles and an ID, ID-ALT); INFO GENE, CSQ, IMPACT and AAPOS fill GENE,
CONSEQUENCE, IMPACT and PROTEIN_POS; a sample CARRIES the allele when its genotype calls it (0/1, 1|1, 1/2 for allele 2 ...); AF is the carriers
over every sample column; the ##SAMPLE=<ID=..,Cohort=..,Sex=..,Population=..> lines describe the samples.
"""
from __future__ import annotations

import csv
import gzip
import io
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CONTRACTS = ROOT / "modules" / "genomics" / "packs" / "genomics" / "contracts"
KINDS = ("variant", "sample", "gene", "cohort")
PACK = "genomics"


class GenomicsError(Exception):
    """A file that cannot be read at all, said in words (a bad row is a reject, not an error)."""


_CONTRACTS: dict[str, dict] = {}


def contract(kind: str) -> dict:
    """The contract of a kind of the genomics pack: {kind, key, fields: {NAME: type}}."""
    if kind not in _CONTRACTS:
        import yaml
        p = CONTRACTS / f"{kind}.yaml"
        if not p.is_file():
            raise GenomicsError(f"no contract for kind '{kind}' ({p}); the kinds are {', '.join(KINDS)}")
        doc = yaml.safe_load(p.read_text(encoding="utf-8"))
        fields = {f["name"]: f.get("type", "string") for f in doc["fields"]}
        key = next(f["name"] for f in doc["fields"] if f.get("key"))
        _CONTRACTS[kind] = {"kind": kind, "key": key, "fields": fields}
    return _CONTRACTS[kind]


def _cell(value, typ: str):
    """One value in the contract's type; ValueError when it cannot be. An empty value is None."""
    if value is None or (isinstance(value, str) and value.strip() in ("", ".")):
        return None
    if typ == "integer":
        if isinstance(value, bool):
            raise ValueError("not an integer")
        if isinstance(value, float) and not value.is_integer():
            raise ValueError("not a whole number")
        return int(float(value)) if isinstance(value, str) and "." in value and float(value).is_integer() else int(value)
    if typ == "number":
        return float(value)
    if typ == "list":
        if isinstance(value, list):
            return value
        text = str(value).strip()
        if text.startswith("["):
            return json.loads(text)
        return [x.strip() for x in re.split(r"[;|,]", text) if x.strip()]
    return value if isinstance(value, str) else str(value) if not isinstance(value, (list, dict)) else value


def check_row(kind: str, row: dict) -> tuple[dict | None, str | None]:
    """The row with its contract fields in their types (other columns kept as delivered), or the reason it is refused: the key missing,
    or a value of the wrong type. Never raises."""
    c = contract(kind)
    out = {}
    for name, value in row.items():
        typ = c["fields"].get(name)
        if typ is None:
            out[name] = value
            continue
        try:
            out[name] = _cell(value, typ)
        except (ValueError, TypeError) as e:
            return None, f"{name}: '{value}' is not {typ} ({e})"
    if out.get(c["key"]) in (None, ""):
        return None, f"{c['key']} is empty (the key of {kind})"
    for name in c["fields"]:
        out.setdefault(name, None)
    return out, None


def opener(path: Path):
    """The file as text, gzip or plain (the name decides)."""
    p = Path(path)
    return io.TextIOWrapper(gzip.open(p, "rb"), encoding="utf-8", newline="") if p.name.lower().endswith(".gz") else open(p, encoding="utf-8", newline="")


def _pairs(text: str, sep: str) -> dict:
    out = {}
    for part in text.split(sep):
        part = part.strip()
        if not part or part == ".":
            continue
        k, eq, v = part.partition("=")
        out[k.strip().upper()] = v.strip() if eq else "true"
    return out


def _carries(gt: str, allele: int) -> bool:
    return str(allele) in re.split(r"[/|]", gt)


def read_vcf(path: Path) -> tuple[list[dict], list[dict], list[str]]:
    """(variant rows, sample rows, problems) of a VCF 4.x file. A data line that is not a VCF record is a problem (named by line), not a
    failure; a file with no ``#CHROM`` header line is refused (GenomicsError)."""
    meta: dict[str, dict] = {}
    columns: list[str] = []
    variants: list[dict] = []
    problems: list[str] = []
    with opener(path) as fh:
        for n, line in enumerate(fh, 1):
            line = line.rstrip("\r\n")
            if not line:
                continue
            if line.startswith("##SAMPLE=<") and line.endswith(">"):
                m = _pairs(line[len("##SAMPLE=<"):-1], ",")
                meta[m.get("ID", "")] = m
            elif line.startswith("##"):
                continue
            elif line.startswith("#CHROM"):
                columns = line.split("\t")
            else:
                if not columns:
                    raise GenomicsError(f"{Path(path).name}: a data line before the #CHROM header line (line {n}): not a VCF file")
                f = line.split("\t")
                if len(f) < 8 or not f[1].isdigit():
                    problems.append(f"line {n}: not a VCF record (needs CHROM, POS, ID, REF, ALT, QUAL, FILTER, INFO)")
                    continue
                variants.extend(_records(f, columns))
    if not columns:
        raise GenomicsError(f"{Path(path).name}: no #CHROM header line: not a VCF file")
    names = columns[9:]
    counts = {s: 0 for s in names}
    for v in variants:
        for s in v["SAMPLES"]:
            counts[s] = counts.get(s, 0) + 1
    samples = [{"SAMPLE_ID": s, "COHORT": meta.get(s, {}).get("COHORT"), "SEX": meta.get(s, {}).get("SEX"),
                "POPULATION": meta.get(s, {}).get("POPULATION"), "VARIANTS": counts.get(s, 0)} for s in names]
    return variants, samples, problems


def _records(f: list[str], columns: list[str]) -> list[dict]:
    chrom, pos, vid, ref, alts = f[0], int(f[1]), f[2], f[3], f[4].split(",")
    qual = None if f[5] in (".", "") else _num(f[5])
    info = _pairs(f[7], ";")
    fmt = f[8].split(":") if len(f) > 8 else []
    gts = {}
    for i in range(9, min(len(f), len(columns))):
        gts[columns[i]] = f[i].split(":")[0] if fmt and fmt[0] == "GT" else "."
    out = []
    for a, alt in enumerate(alts):
        row_id = f"{chrom}-{pos}-{ref}-{alt}" if vid == "." else (f"{vid}-{alt}" if len(alts) > 1 else vid)
        carriers = [s for s, gt in gts.items() if _carries(gt, a + 1)]
        out.append({"VARIANT_ID": row_id, "CHROM": chrom, "POS": pos, "REF": ref, "ALT": alt, "QUAL": qual, "FILTER": f[6],
                    "GENE": info.get("GENE", ""), "CONSEQUENCE": info.get("CSQ", ""), "IMPACT": info.get("IMPACT", ""),
                    "PROTEIN_POS": _int(info.get("AAPOS")), "CARRIERS": len(carriers),
                    "AF": round(len(carriers) / len(gts), 4) if gts else None, "SAMPLES": carriers})
    return out


def _num(text):
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


def _int(text):
    n = _num(text)
    return int(n) if n is not None and n == int(n) else None


def read_table(path: Path, kind: str) -> list[dict]:
    """The rows of a CSV (header line, comma or tab or semicolon) or JSON Lines file, plain or .gz. Columns are matched to the contract's
    names without regard to case (gene -> GENE); cells are not typed here (check_row does that)."""
    name = Path(path).name.lower().removesuffix(".gz")
    fields = {k.lower(): k for k in contract(kind)["fields"]}
    rows = []
    with opener(path) as fh:
        if name.endswith((".jsonl", ".json", ".ndjson")):
            for n, line in enumerate(fh, 1):
                if not line.strip():
                    continue
                try:
                    doc = json.loads(line)
                except ValueError as e:
                    raise GenomicsError(f"{Path(path).name}: line {n} is not JSON ({e})")
                if not isinstance(doc, dict):
                    raise GenomicsError(f"{Path(path).name}: line {n} is not a JSON object")
                rows.append({fields.get(k.lower(), k): v for k, v in doc.items()})
        elif name.endswith((".csv", ".tsv", ".txt")):
            text = fh.read()
            head = text.split("\n", 1)[0]
            delim = max((",", "\t", ";"), key=head.count)
            for row in csv.DictReader(io.StringIO(text), delimiter=delim):
                rows.append({fields.get((k or "").strip().lower(), (k or "").strip()): v for k, v in row.items() if k})
        else:
            raise GenomicsError(f"{Path(path).name}: a table is a .csv, .tsv or .jsonl file (plain or .gz)")
    return rows
