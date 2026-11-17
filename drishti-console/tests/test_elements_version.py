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

"""One version for Drishti Elements (owner rule, 2026-11-16): every library (core, inputs, grid, chart, data, insight, renderer, risk,
designer, graph, tree and a domain module's library) shares ONE semantic version, ``elements`` in static/elements/drishti_elements.json,
separate from Drishti's own. No library has a version of its own; every bundle, the generated manifest, the renderer's folder, the packages,
the reference, the catalogue and the docs name that one value, and nothing names another Elements version."""
import json
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it

ROOT = Path(__file__).resolve().parents[2]
STATIC = ROOT / "drishti-console" / "web" / "static"
MANIFEST = STATIC / "elements" / "drishti_elements.json"
RAW = json.loads(MANIFEST.read_text(encoding="utf-8"))
VERSION = RAW["elements"]
LIBS = "core|inputs|grid|chart|data|insight|renderer|risk|designer|graph|tree|genomics"
NAMED = re.compile(r"\bdrishti-(?:%s) (\d+\.\d+\.\d+)" % LIBS)
RENDER_PATH = re.compile(r"render/(\d+\.\d+\.\d+)/")
FROZEN = {"1.2.0"}       # the renderer folder kept byte for byte for the embedders that pinned it (drishti-console/web/static/render/1.2.0)
SCANNED = ("drishti-console/web", "drishti-console/tests", "tools", "docs/guides", "docs/architecture/ELEMENTS.md", "modules", "packaging")
SKIPPED = ("drishti-console/web/static/render/1.2.0", "drishti-console/tests/test_elements_version.py", "node_modules", "/vendor/", ".venv",
           "drishti-console/web/static/gallery/drishti_catalogue.js", "docs/guides/ELEMENTS_API.md")   # the last two quote the CHANGELOG history


def test_the_version_is_one_semver_and_no_library_has_its_own():
    assert re.fullmatch(r"\d+\.\d+\.\d+", VERSION), VERSION
    assert [n for n, spec in RAW["libraries"].items() if "version" in spec] == []


def test_every_bundle_the_generated_manifest_and_the_loader_carry_that_version():
    for name in RAW["libraries"]:
        short = name[len("drishti-"):]
        head = (STATIC / "elements" / name / f"drishti_{short.replace('-', '_')}.js").read_text(encoding="utf-8")[:1500]
        assert f"/* {name} {VERSION}:" in head, name
    gen = (STATIC / "elements" / "drishti-core" / "src" / "drishti_manifest.js").read_text(encoding="utf-8")
    assert f'"elements": "{VERSION}"' in gen and '"version"' not in gen


def test_the_renderer_folder_is_the_version_and_only_the_frozen_one_is_beside_it():
    from core import render_dist as rd
    assert rd.VERSION == VERSION and rd.versions() == sorted(FROZEN | {VERSION}) and set(rd.FROZEN) == FROZEN
    assert (STATIC / "render" / VERSION / "drishti-render.js").is_file()


def test_the_packages_the_reference_the_catalogue_and_the_webjar_say_it():
    assert re.search(r"<artifactId>drishti-elements</artifactId>\s*<version>([^<]+)</version>", (ROOT / "packaging/drishti-elements/pom.xml").read_text(encoding="utf-8")).group(1) == VERSION
    assert (ROOT / "docs/guides/ELEMENTS_API.md").read_text(encoding="utf-8").count(f"# Drishti Elements {VERSION}: the API reference") == 1
    assert f'"elements":"{VERSION}"' in (STATIC / "gallery" / "drishti_catalogue.js").read_text(encoding="utf-8").replace(" ", "")
    assert f"**{VERSION}**" in (STATIC / "elements" / "drishti_README.md").read_text(encoding="utf-8")


def _texts():
    for sub in SCANNED:
        base = ROOT / sub
        for f in ([base] if base.is_file() else sorted(base.rglob("*"))):
            rel = f.relative_to(ROOT).as_posix()
            if f.is_file() and f.suffix in (".js", ".mjs", ".py", ".html", ".md", ".json", ".css", ".ts", ".xml", ".yaml") and not any(s in rel for s in SKIPPED):
                try:
                    yield rel, f.read_text(encoding="utf-8")
                except UnicodeDecodeError:
                    continue


def test_the_retired_renderer_path_redirects_and_says_so():
    from core import render_dist as rd
    assert rd.RETIRED == ("2.0.0",) and VERSION not in rd.RETIRED and "after i6" in rd.DEPRECATION


def test_nothing_names_another_elements_version():
    bad = []
    for rel, text in _texts():
        for line in text.splitlines():
            if "after i6" in line:             # the one exception: the retired renderer path that redirects for one release (and the words that say so)
                continue
            bad += [f"{rel}: {m.group()}" for m in NAMED.finditer(line) if m.group(1) != VERSION]
            bad += [f"{rel}: render/{m.group(1)}/" for m in RENDER_PATH.finditer(line) if m.group(1) not in FROZEN | {VERSION}
                    and not rel.startswith("drishti-console/tests/")]
    assert not bad, "Elements has one version (" + VERSION + "); these name another:\n" + "\n".join(bad[:30])


def test_every_module_range_follows_the_one_version():
    """A module is built for the Elements of its major and minor: ``>=<major>.<minor> <<major+1>`` of the one version (the generators write it
    through tools/packgen/elements_range.py; the static module.yaml files and the SDK archetype are held to it here)."""
    major, minor = VERSION.split(".")[:2]
    want = f">={major}.{minor} <{int(major) + 1}"
    files = sorted((ROOT / "modules").glob("*/module.yaml")) + [ROOT / "drishti-sdk/drishti-module-archetype/src/main/resources/archetype-resources/module.yaml"]
    bad = []
    for f in files:
        m = re.search(r"^elements:\s*['\"]?([^'\"\n#]+?)['\"]?\s*(?:#.*)?$", f.read_text(encoding="utf-8"), re.M)
        if not m or m.group(1) != want:
            bad.append(f"{f.relative_to(ROOT)}: {m.group(1) if m else 'no elements range'}")
    assert not bad, f"module.yaml elements range must be {want!r}:\n" + "\n".join(bad)
