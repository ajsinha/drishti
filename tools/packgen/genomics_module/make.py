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
"""The genomics module's own tables (variant, sample, gene) as committed test data: the module's synthetic samples
(modules/genomics/data/samples: two VCF files and gene.jsonl), read by the genomics loader's own readers (tools/loaders/genomics/genomics_kit.py:
the same checks and the same rows as `genomics_run.py` writes), so the demo lake `genomicslake` holds what a site's own files would give.

    python3 tools/packgen/genomics_module/make.py            write data/testdata/genomics/<kind>/<kind>.<date>.jsonl.gz and its testdata.yaml entries
    python3 tools/packgen/genomics_module/make.py --check    fail if what would be written differs from what is committed

`drishti.py lake demo` builds them into genomicslake (genomics.variant, genomics.sample, genomics.gene); the module's screens read that lake through
the pack's connector, never a file. Standard library and PyYAML only; no network; the same files give byte-identical output.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SAMPLES = ROOT / "modules" / "genomics" / "data" / "samples"
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
sys.path.insert(0, str(ROOT / "tools" / "loaders" / "genomics"))
import genomics_kit as G  # noqa: E402
import publicsnap as SNAP  # noqa: E402

PACK = "genomics"


def documents() -> dict[str, dict[str, dict]]:
    """{kind: {id: row}} of the sample files, each row checked against its contract as the loader checks it."""
    docs: dict[str, dict[str, dict]] = {k: {} for k in ("variant", "sample", "gene")}
    for f in sorted(SAMPLES.glob("*.vcf")):
        variants, samples, problems = G.read_vcf(f)
        if problems:
            raise SystemExit(f"{f.name}: {problems[0]}")
        for kind, rows in (("variant", variants), ("sample", samples)):
            key = G.contract(kind)["key"]
            for r in rows:
                row, why = G.check_row(kind, r)
                if why:
                    raise SystemExit(f"{f.name}: {kind}: {why}")
                docs[kind][str(row[key])] = row
    for line in (SAMPLES / "gene.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            row, why = G.check_row("gene", json.loads(line))
            if why:
                raise SystemExit(f"gene.jsonl: {why}")
            docs["gene"][str(row["GENE"])] = row
    return docs


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="Write or check the genomics module's own test data.")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--testdata", type=Path, default=SNAP.TESTDATA)
    a = ap.parse_args(argv)
    docs = documents()
    if a.check:
        bad = SNAP.differences(a.testdata.resolve(), PACK, docs)
        print("\n".join(bad) if bad else f"genomics test data matches its generator ({sum(len(v) for v in docs.values())} rows)")
        return 1 if bad else 0
    written = SNAP.write(a.testdata.resolve(), PACK, docs)
    print(f"wrote {len(written) - 1} files: " + ", ".join(f"{k} {len(v)}" for k, v in docs.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
