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

"""The PDF copies of the guides (tools/docs/md_to_pdf.py: the Deployment and QA guide, the developer quickstart, the cluster guide) are
current: each PDF's title carries the fingerprint of its Markdown and pictures, so a guide changed without its PDF regenerated fails here,
with the command that fixes it. No browser is started."""
import importlib.util
import re

import pytest

from conftest import CONSOLE

pytestmark = pytest.mark.static

ROOT = CONSOLE.parent
spec = importlib.util.spec_from_file_location("drishti_md_to_pdf", ROOT / "tools" / "docs" / "md_to_pdf.py")
md_to_pdf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(md_to_pdf)


@pytest.mark.parametrize("source", md_to_pdf.PDFS, ids=lambda p: p.name)
def test_every_pdf_is_current(source):
    assert source.is_file(), source
    why = md_to_pdf.stale(source)
    assert not why, why + ": drishti-console/.venv/bin/python tools/docs/md_to_pdf.py"


@pytest.mark.parametrize("source", md_to_pdf.PDFS, ids=lambda p: p.name)
def test_every_pdf_has_its_pages_and_pictures(source):
    data = source.with_suffix(".pdf").read_bytes()
    assert data[:5] == b"%PDF-"
    pages = len(re.findall(rb"/Type\s*/Page[^s]", data))
    assert pages >= 5, f"{source.name}: {pages} pages"
    shown = md_to_pdf.pictures(source)
    assert all(p.is_file() for p in shown), [p.name for p in shown if not p.is_file()]
    raster = [p for p in shown if p.suffix.lower() != ".svg"]   # an SVG diagram stays vector in the PDF (paths and text, no image object)
    assert len(re.findall(rb"/Subtype\s*/Image", data)) >= len(raster) or not raster, f"{source.name}: a picture is not in the PDF"


def test_the_guides_link_their_pdfs():
    for source in md_to_pdf.PDFS[:2]:
        assert source.with_suffix(".pdf").name in source.read_text(encoding="utf-8"), source.name
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    for source in md_to_pdf.PDFS:
        assert source.with_suffix(".pdf").relative_to(ROOT).as_posix() in readme, source.name
