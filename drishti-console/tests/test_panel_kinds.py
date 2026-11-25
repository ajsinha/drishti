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


"""The panel kinds catalogue and the panel developer guide: since 2.9.7 both are in docs/guides/RACHANA_REFERENCE.md (PANELS.md, before it
PANEL_KINDS.md and PANEL_DEVELOPER_GUIDE.md, were folded in). Each kind's catalogue entry is the first part of the reference's own section of
that kind (### Panel kinds, #### <kind>), followed by its keys; developing a kind is "### Developing a panel kind" in the Panels part.

Every panel's ``?`` icon, the workbench inspector's "About <kind>" and F1 open ``/help/rachana-reference#<kind>`` (the versioned client renderer
still opens ``/help/panels#<kind>``, which redirects without a section, so the browser keeps ``#<kind>``), so the reference needs a heading
whose GitHub slug is exactly the kind, and it must be the first heading with that slug (a second ``kv`` heading would be ``kv-1``). Each kind
has a picture, a YAML example cut verbatim from docs/guides/examples, and its keys in the same section."""
from pathlib import Path
import re

from conftest import CONSOLE
from core.guides import GithubSlugger

ROOT = CONSOLE.parent
DOCS = ROOT / "docs" / "guides"
PANELS = DOCS / "RACHANA_REFERENCE.md"                  # the catalogue (### Panel kinds) and developing a kind (### Developing a panel kind)
KINDS_MARK, DEV_MARK = "\n### Panel kinds\n", "\n### Developing a panel kind"


class _Part:
    def __init__(self, dev: bool):
        self.dev, self.name = dev, "RACHANA_REFERENCE.md"

    def read_text(self) -> str:
        """The section, up to the next level-2 heading."""
        text = PANELS.read_text()
        start = text.index(DEV_MARK if self.dev else KINDS_MARK)
        end = text.find("\n## ", start + 1)
        return text[start:end if end > 0 else len(text)]


KINDS_DOC, DEV_DOC = _Part(False), _Part(True)
KINDS = ["kv", "status", "provenance", "metric", "map", "table", "ladder", "tabs", "pivot", "line", "area", "hbar", "gauge", "surface", "candlestick",
         "histogram", "scatter", "waterfall", "graph", "links", "timeline", "markdown"]
HEADING = re.compile(r"^(#{1,6}) (.+?)\s*$", re.M)          # every heading: the help centre gives each an id, numbered in document order


def anchors(text: str) -> dict:
    """{slug: heading text} for every heading outside code fences, slugged in order as the help centre does (pass the whole document)."""
    lines, fence = [], None
    for line in text.splitlines():                 # a fence's lines (a YAML comment starts with #) are not headings
        mark = line.strip()[:3]
        if fence is None and mark in ("```", "~~~"):
            fence = mark
        elif fence is not None and line.strip() == fence * (len(line.strip()) // 3) and line.strip():
            fence = None
        elif fence is None:
            lines.append(line)
    slugger, out = GithubSlugger(), {}
    for _level, title in HEADING.findall("\n".join(lines)):
        out[slugger(title)] = title
    return out


def test_the_catalogue_lists_all_twenty_two_kinds_each_with_its_own_anchor():
    assert len(KINDS) == 22 and len(set(KINDS)) == 22
    found = anchors(PANELS.read_text())
    for kind in KINDS:
        assert found.get(kind) == kind, f"#### {kind} must be the first heading that slugs to '{kind}': got {found.get(kind)!r}"
        assert f"\n#### {kind}\n" in KINDS_DOC.read_text(), kind
    assert "## Panels: the twenty-two kinds" in PANELS.read_text()


# The kinds the grammar added at Level 1.2, which the twenty-two-kind catalogue of this reference does not describe as panel kinds; a kind the
# pinned Rachana adds that is in neither list fails the test below.
LEVEL_1_2_KINDS = {"bullet", "chart", "form", "grid", "live", "tree", "treemap"}


def _grammar() -> dict:
    """The pinned Rachana release's published catalogue (``spec/1/rachana.lang.json`` in ``rachana-conformance-cases``), read from the local Maven
    repository the way the SDK build finds the products: ``DRISHTI_LOCAL_MAVEN_REPO``, else the ``-Dmaven.repo.local`` of ``MAVEN_ARGS``, else ``~/.m2``."""
    import json
    import zipfile
    import sys
    sys.path.insert(0, str(ROOT / "tools" / "launch"))          # sdkbuild imports its neighbours by name, as drishti.py does
    import sdkbuild
    version = sdkbuild.pinned_versions(ROOT)["com.ash.rachana"]
    jar = sdkbuild.local_maven_repository() / "com/ash/rachana/rachana-conformance-cases" / version / f"rachana-conformance-cases-{version}.jar"
    assert jar.is_file(), f"{jar} is not there: install the pinned Rachana release in the local Maven repository (or set DRISHTI_LOCAL_MAVEN_REPO)"
    with zipfile.ZipFile(jar) as z:
        return json.loads(z.read("spec/1/rachana.lang.json"))


def test_the_kinds_of_the_grammar_are_exactly_these_twenty_two():
    assert {k["name"] for k in _grammar()["panelKinds"]} == set(KINDS) | LEVEL_1_2_KINDS


def test_each_kind_has_a_picture_options_linked_to_the_reference_and_a_yaml_example_that_is_a_real_excerpt():
    text = KINDS_DOC.read_text()
    examples = "\n".join(p.read_text() for p in (DOCS / "examples").glob("*.sutra.yaml"))
    sections = re.split(r"^#### ", text, flags=re.M)[1:]
    by_kind = {s.split("\n", 1)[0].strip(): s for s in sections}
    for kind in KINDS:
        body = by_kind[kind]
        assert f"](img/panels/kind-{kind}.jpg)" in body, kind
        assert f"**The keys of `{kind}`.**" in body, kind                 # the catalogue entry and the kind's keys: one section
        general = ("Use it when:", "Not when:", "Key options:")       # the general kinds' three moved to Drishti Rachana's PANEL_KINDS.md (one linked line); links and provenance are Drishti's
        assert "Use it when, not when, key options and every option of" in body or all(label in body for label in general), kind
        for label in ("Empty when", "Masked:", "No access:", "Live:", "Phone:", "CSV:"):
            assert label in body, (kind, label)
        yaml = re.search(r"```yaml\n(.*?)\n```", body, re.S)
        assert yaml and f"kind: {kind}" in yaml.group(1), kind
        assert yaml.group(1) in examples, f"the {kind} example is not a verbatim excerpt of docs/guides/examples"


def test_the_help_links_into_the_catalogue_use_headings_that_exist():
    found = anchors(PANELS.read_text())
    macro = (CONSOLE / "web" / "templates" / "_macros" / "panels.html").read_text()
    inspector = (CONSOLE / "web" / "static" / "js" / "build" / "drishti_inspector.js").read_text()
    assert "/help/panels#{{ p.kind }}" in macro and "'/help/rachana-reference#' + kind" in inspector
    assert set(KINDS) <= set(found)


def test_no_guide_still_says_twenty_panel_kinds():
    for doc in (KINDS_DOC, DEV_DOC):
        assert not re.search(r"(?i)\btwenty (panel )?kinds\b|\bthe twenty panel", doc.read_text()), doc.name
    assert not (DOCS / "PANEL_KINDS.md").exists() and not (DOCS / "PANEL_DEVELOPER_GUIDE.md").exists()


def test_the_catalogue_does_not_copy_option_tables_from_the_reference():
    entries = [s.split("**The keys of", 1)[0] for s in re.split(r"^#### ", KINDS_DOC.read_text(), flags=re.M)[1:]]
    for text in entries:                       # each kind's catalogue entry, before its keys: the options are listed once, in the keys
        assert not re.search(r"^\| Option \| Required", text, re.M), "options are listed in the kind's keys, not in its catalogue entry"


def test_the_developer_guide_walks_through_metric_with_the_real_code():
    text = DEV_DOC.read_text()
    rachana = [         # Rachana's own sources: the guide quotes them; the kind's line is also checked against the pinned release's catalogue below
        "record Metric(Cell value, Cell delta, String unit, String caption) implements PanelData {}",
        "case PanelData.Metric m -> m.value() == null || blank(List.of(m.value()));",
        "case METRIC -> metric(p, c);",
        '"sum", "delta", "highlight", "region");',
    ]
    for snippet in rachana:
        assert snippet in text, f"the guide no longer quotes Rachana's {snippet}"
    metric = next(k for k in _grammar()["panelKinds"] if k["name"] == "metric")
    assert 'METRIC(Set.of("value"), Set.of("label", "fmt", "tone", "delta", "deltaFmt", "deltaTone", "unit", "caption")),' in text
    assert metric["required"] == ["value"] and sorted(metric["optional"]) == sorted(["label", "fmt", "tone", "delta", "deltaFmt", "deltaTone", "unit", "caption"])
    sources = {
        "drishti-console/core/export.py": "if isinstance(d.get(\"value\"), dict):",
        "drishti-console/web/static/js/build/drishti_palette.js": "['metric', 'hash',",
        "drishti-console/web/static/css/drishti_terminal.css": ".metric-v { font-size: var(--d-fs-display-5);",
    }
    for path, snippet in sources.items():
        assert snippet in (ROOT / path).read_text(), f"{path} no longer has the snippet the guide quotes"
        assert snippet in text, f"the guide does not quote {path}"
    for step in ("PanelKind", "PanelOptions.java", "SutraExpressions", "RachanaSchema", "PanelData", "Emptiness.java", "Binder",
                 "panels.html", "drishti_terminal.css", "drishti_charts.js", "export.py", "Bind.java", "PanelChooser.java", "AutoDesigner.java", "drishti_palette.js", "drishti_actions.js",
                 "SampleChecker", "test_terminal.py", "StudioTest", "test_examples.py", "test_workbench_options_browser.py", "CHANGELOG.md"):
        assert step in text, step
    for guard in ("test_guide_images.py", "test_panel_kinds.py", "MetricPanelTest.java", "test_metric.py", "test_workbench_metric_browser.py"):
        assert (ROOT / "drishti-console" / "tests" / guard).exists() or list(ROOT.rglob(guard)), guard
    for pic in re.findall(r"\]\((img/panels/[^)\s]+)\)", text):
        assert (DOCS / pic).is_file(), pic


def test_the_help_centre_serves_the_catalogue_with_an_anchor_per_kind_and_its_pictures(client):
    """The help.yaml entry ``rachana-reference`` (doc: guides/RACHANA_REFERENCE.md); the retired ``panels``, ``panel-kinds`` and ``panel-developer-guide`` are gone (no aliases)."""
    page = client.get("/help/rachana-reference")
    assert page.status_code == 200 and "twenty-two kinds" in page.text and "data-unavailable" not in page.text
    for kind in KINDS:
        assert f'id="{kind}"' in page.text, kind
    srcs = [s for s in re.findall(r'<img[^>]*\bsrc="([^"]+)"', page.text) if "/img/panels/" in s]
    assert len(srcs) >= 21 and all(s.startswith("/help/files/guides/img/panels/") for s in srcs)
    for retired in ("panels", "panel-kinds", "panel-developer-guide"):                 # retired pages have no alias: the reference holds their content
        assert client.get(f"/help/{retired}", follow_redirects=False).status_code == 404, retired
    for s in srcs[:5]:
        assert client.get(s).status_code == 200, s
    assert "metric" in page.text and "Developing a panel kind" in page.text
