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

"""Help ships with the console (docs/architecture/THIN_CONSOLE_DESIGN.md section 4.1: the repository's documents are the console's own
content, not the server's): every console image copies the documents Help renders to ``<install>/docs`` (the console's
``help.docs_dir``, ``../docs`` from its folder) and the root documents beside it, the same list the detached-console test ships with its copy
(detached_console.SHIPPED). A console image that left one out would serve Help's pages as 404 on a machine without the repository."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

import detached_console

pytestmark = pytest.mark.static

REPO = Path(__file__).resolve().parents[2]
IMAGES = ("deploy/docker/console.Dockerfile", "deploy/docker/ecs/console.Dockerfile")
COPY = re.compile(r"^COPY\s+(?:--\S+\s+)*(.+?)\s+(\S+)\s*$", re.M)


def copied(dockerfile: str) -> dict[str, str]:
    """Each source the image copies from the repository, with where it lands (the last argument of its COPY line)."""
    out: dict[str, str] = {}
    for sources, target in COPY.findall((REPO / dockerfile).read_text(encoding="utf-8")):
        for src in sources.split():
            out[src.rstrip("/")] = target
    return out


def test_the_help_setting_points_beside_the_console_where_the_documents_ship():
    text = (REPO / "drishti-console" / "config" / "application.yaml").read_text(encoding="utf-8")
    assert re.search(r"^\s+docs_dir:\s+\.\./docs\b", text, re.M), "help.docs_dir is ../docs: the documents ship beside the console"


@pytest.mark.parametrize("image", IMAGES)
def test_every_console_image_ships_the_documents_help_renders(image):
    got = copied(image)
    workdir = re.findall(r"^WORKDIR\s+(\S+)", (REPO / image).read_text(encoding="utf-8"), re.M)[-1]   # the console's stage: the last
    home = str(Path(workdir).parent)                                   # the install folder: the console is <install>/console
    for name in detached_console.SHIPPED:
        assert name in got, f"{image} does not copy {name}: Help would answer 404 for it on a console without the repository"
        assert got[name].rstrip("/") in (f"{home}/{name}", home), f"{image} copies {name} to {got[name]}, not beside the console ({home})"
