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

"""What both pack generators (tools/packgen/economics/make.py, tools/packgen/politics/make.py) run as their main: the pack's glossary
source (about.py), the pack itself (packbuild) and the pack's Sutra tests (pack_tests.py), each written or, with --check, compared.

Environment, set by realdata.py:
  DRISHTI_REALDATA_NO_IMF=1        build without IMF data (about, tests and documents)
  DRISHTI_PACKS_OUT=DIR            write the pack under DIR instead of config/packs
  DRISHTI_ABOUT_DIR=DIR            write the glossary source to DIR/<pack>/about.yaml instead of tools/packgen/<dir>/about.yaml
  DRISHTI_REALDATA_DATASET=DIR     read the dataset from DIR instead of tools/realdata/dataset
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import about as A  # noqa: E402
import build_docs as BD  # noqa: E402
import pack_tests as PT  # noqa: E402


def run(PB, pack_dir: str, spec, docs: dict, known_fields: dict) -> None:
    """pack_dir is the generator directory (economics | politics); PB the packbuild module the generator loaded."""
    check = "--check" in sys.argv
    no_imf = os.environ.get(BD.ENV_NO_IMF) == "1"
    about_dir = os.environ.get("DRISHTI_ABOUT_DIR")
    path = A.targets(Path(about_dir) if about_dir else None)[pack_dir]
    text = A.render(pack_dir, no_imf)
    tests_base = PB.PACKS / spec.name
    problems = []
    if check:
        if not path.exists() or path.read_text(encoding="utf-8") != text:
            problems.append(f"{path} (run tools/realdata/realdata.py about)")
        problems += PT.check(spec.name, docs, tests_base)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    PB.main(spec, docs, known_fields)
    if check:
        if problems:
            raise SystemExit(f"{spec.name}: out of date: {problems[:3]}")
    else:
        print(f"{spec.name}: wrote {PT.write(spec.name, docs, tests_base)} test files")
