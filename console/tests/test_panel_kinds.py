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


"""The panel kinds catalogue (docs/guides/PANEL_KINDS.md) and the panel developer guide (docs/guides/PANEL_DEVELOPER_GUIDE.md).

Every panel's ``?`` icon, the workbench inspector's "About <kind>" and F1 open ``/help/panel-kinds#<kind>``, so the catalogue needs a heading whose
GitHub slug is exactly the kind, and it must be the first heading with that slug (a second ``kv`` heading would be ``kv-1``). Each kind has a
picture, a YAML example cut verbatim from docs/guides/examples, and a link into the Rachana reference."""
from pathlib import Path
import re

from conftest import CONSOLE
from core.guides import GithubSlugger

ROOT = CONSOLE.parent
DOCS = ROOT / "docs" / "guides"
KINDS_DOC = DOCS / "PANEL_KINDS.md"
DEV_DOC = DOCS / "PANEL_DEVELOPER_GUIDE.md"
KINDS = ["kv", "status", "provenance", "metric", "table", "ladder", "tabs", "pivot", "line", "area", "hbar", "gauge", "surface", "candlestick",
         "histogram", "scatter", "waterfall", "graph", "links", "timeline", "markdown"]
HEADING = re.compile(r"^(#{2,3}) (.+?)\s*$", re.M)          # the help centre's table of contents covers levels 2 and 3


def anchors(text: str) -> dict:
    """{slug: heading text} for every level 2 and 3 heading outside code fences, slugged in order as the help centre does."""
    plain = re.sub(r"```.*?```", "", text, flags=re.S)
    slugger, out = GithubSlugger(), {}
    for _level, title in HEADING.findall(plain):
        out[slugger(title)] = title
    return out


def test_the_catalogue_lists_all_twenty_one_kinds_each_with_its_own_anchor():
    assert len(KINDS) == 21 and len(set(KINDS)) == 21
    found = anchors(KINDS_DOC.read_text())
    for kind in KINDS:
        assert found.get(kind) == kind, f"### {kind} must be the first heading that slugs to '{kind}': got {found.get(kind)!r}"
    assert "# The twenty-one panel kinds" in KINDS_DOC.read_text()


def test_the_kinds_of_the_grammar_are_exactly_these_twenty_one():
    java = (ROOT / "drishti-rachana" / "src" / "main" / "java" / "com" / "ash" / "drishti" / "rachana" / "model" / "PanelKind.java").read_text()
    declared = re.findall(r"^    ([A-Z]+)\(Set\.of", java, re.M)
    assert sorted(k.lower() for k in declared) == sorted(KINDS)


def test_each_kind_has_a_picture_options_linked_to_the_reference_and_a_yaml_example_that_is_a_real_excerpt():
    text = KINDS_DOC.read_text()
    examples = "\n".join(p.read_text() for p in (DOCS / "examples").glob("*.sutra.yaml"))
    sections = re.split(r"^### ", text, flags=re.M)[1:]
    by_kind = {s.split("\n", 1)[0].strip(): s for s in sections}
    for kind in KINDS:
        body = by_kind[kind].split("\n## ", 1)[0]
        assert f"](img/panels/kind-{kind}.jpg)" in body, kind
        assert "RACHANA_REFERENCE.md#" in body, kind
        for label in ("Use it when:", "Not when:", "Key options:", "Empty when", "Masked:", "No access:", "Live:", "Phone:", "CSV:"):
            assert label in body, (kind, label)
        yaml = re.search(r"```yaml\n(.*?)\n```", body, re.S)
        assert yaml and f"kind: {kind}" in yaml.group(1), kind
        assert yaml.group(1) in examples, f"the {kind} example is not a verbatim excerpt of docs/guides/examples"


def test_the_help_links_into_the_catalogue_use_headings_that_exist():
    found = anchors(KINDS_DOC.read_text())
    macro = (CONSOLE / "web" / "templates" / "_macros" / "panels.html").read_text()
    inspector = (CONSOLE / "web" / "static" / "js" / "build" / "inspector.js").read_text()
    assert "/help/panel-kinds#{{ p.kind }}" in macro and "'/help/panel-kinds#' + kind" in inspector
    assert set(KINDS) <= set(found)


def test_no_guide_still_says_twenty_panel_kinds():
    for doc in (KINDS_DOC, DEV_DOC):
        assert not re.search(r"(?i)\btwenty (panel )?kinds\b|\bthe twenty panel", doc.read_text()), doc.name
    stub = (DOCS / "PANELS.md").read_text()
    assert "PANEL_KINDS.md" in stub and len(stub.splitlines()) <= 20


def test_the_catalogue_does_not_copy_option_tables_from_the_reference():
    text = KINDS_DOC.read_text()
    assert not re.search(r"^\| Option \| Required", text, re.M), "options are listed in RACHANA_REFERENCE.md, not here"


def test_the_developer_guide_walks_through_metric_with_the_real_code():
    text = DEV_DOC.read_text()
    sources = {
        "drishti-rachana/src/main/java/com/ash/drishti/rachana/model/PanelKind.java": 'METRIC(Set.of("value"), Set.of("label", "fmt", "tone", "delta", "deltaFmt", "deltaTone", "unit", "caption"));',
        "drishti-engine/src/main/java/com/ash/drishti/engine/view/PanelData.java": "record Metric(Cell value, Cell delta, String unit, String caption) implements PanelData {}",
        "drishti-engine/src/main/java/com/ash/drishti/engine/view/Emptiness.java": "case PanelData.Metric m -> m.value() == null || blank(List.of(m.value()));",
        "drishti-engine/src/main/java/com/ash/drishti/engine/bind/Binder.java": "case METRIC -> metric(p, c);",
        "console/core/export.py": "if isinstance(d.get(\"value\"), dict):",
        "console/web/static/js/build/palette.js": "['metric', 'hash',",
        "console/web/static/css/terminal.css": ".metric-v { font-size: 2.2rem;",
        "drishti-rachana/src/main/java/com/ash/drishti/rachana/design/ops/Bind.java": '"sum", "delta");',
    }
    for path, snippet in sources.items():
        assert snippet in (ROOT / path).read_text(), f"{path} no longer has the snippet the guide quotes"
        assert snippet in text, f"the guide does not quote {path}"
    for step in ("PanelKind.java", "PanelOptions.java", "SutraExpressions.java", "RachanaSchema.java", "PanelData.java", "Emptiness.java", "Binder.java",
                 "panels.html", "terminal.css", "charts.js", "export.py", "Bind.java", "PanelChooser.java", "AutoDesigner.java", "palette.js", "actions.js",
                 "SampleChecker", "test_terminal.py", "StudioTest", "test_examples.py", "test_workbench_options_browser.py", "CHANGELOG.md"):
        assert step in text, step
    for guard in ("test_guide_images.py", "test_panel_kinds.py", "MetricPanelTest.java", "test_metric.py", "test_workbench_metric_browser.py"):
        assert (ROOT / "console" / "tests" / guard).exists() or list(ROOT.rglob(guard)), guard
    for pic in re.findall(r"\]\((img/panels/[^)\s]+)\)", text):
        assert (DOCS / pic).is_file(), pic


def test_the_help_centre_serves_the_catalogue_with_an_anchor_per_kind_and_its_pictures(client):
    """Needs the help.yaml entry ``panel-kinds`` (doc: guides/PANEL_KINDS.md); ``panels`` moves to it."""
    page = client.get("/help/panel-kinds")
    assert page.status_code == 200 and "The twenty-one panel kinds" in page.text and "data-unavailable" not in page.text
    for kind in KINDS:
        assert f'id="{kind}"' in page.text, kind
    srcs = re.findall(r'<img[^>]*\bsrc="([^"]+)"', page.text)
    assert len(srcs) >= 21 and all(s.startswith("/help/files/guides/img/panels/") for s in srcs)
    for s in srcs[:5]:
        assert client.get(s).status_code == 200, s
    assert client.get("/help/panels", follow_redirects=False).headers["location"] == "/help/panel-kinds"
    guide = client.get("/help/panel-developer-guide")
    assert guide.status_code == 200 and "metric" in guide.text and "Developing a panel kind" in guide.text
