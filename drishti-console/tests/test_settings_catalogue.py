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

"""docs/admin/CONFIGURATION.md is the one configuration document, complete by construction (2.9.7 C1-C2): its generated half is what
tools/settings_catalogue.py writes from the sources, every key of every source is a row, every key has a meaning, and every environment
variable a source names or the code reads is in the index."""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import settings_catalogue as cat  # noqa: E402
import settings_sources as S  # noqa: E402

DOC = (ROOT / "docs" / "admin" / "CONFIGURATION.md").read_text(encoding="utf-8")


def _generated() -> str:
    return DOC[DOC.index(cat.BEGIN):DOC.index(cat.END)]


def _row_keys(section: str) -> set[str]:
    return {m.group(1) for m in re.finditer(r"^\| `([^`]+)` \|", section, re.M)}


def test_the_document_is_current_and_nothing_is_missing():
    block, problems = cat.build()
    assert not problems, "\n".join(problems)
    assert cat.splice(DOC, block) == DOC, "run python3 tools/settings_catalogue.py"
    assert cat.main(["--check"]) == 0


def test_every_row_has_the_one_shape():
    gen = _generated()
    heads = re.findall(r"^\| Key \| .* \|$", gen, re.M)
    assert heads and set(heads) == {"| " + " | ".join(cat.COLUMNS) + " |"}
    rows = [ln for ln in gen.splitlines() if ln.startswith("| `") and "Variable" not in ln]
    eight = [r for r in rows if len(re.split(r"(?<!\\)\|", r)) - 2 == 8]
    assert len(eight) > 1500
    for r in eight:
        meaning = re.split(r"(?<!\\)\|", r)[6].strip()
        assert meaning, r


def _flat(node, prefix=""):
    if isinstance(node, dict) and node:
        for k, v in node.items():
            yield from _flat(v, f"{prefix}{k}.")
    else:
        yield prefix[:-1]


def _covered(key: str, keys: set[str], collapsed: tuple[str, ...]) -> bool:
    return key in keys or any(key == c or key.startswith(c + ".") for c in collapsed)


def test_every_key_of_the_two_application_files_is_a_row():
    gen = _generated()
    server = gen[gen.index("### The server"):gen.index("### The console:")]
    console = gen[gen.index("### The console:"):gen.index("### The console's other files")]
    for rel, section, collapsed in (("drishti-server/src/main/resources/application.yaml", server, cat.COLLAPSE["server"]),
                                    ("drishti-console/config/application.yaml", console, cat.COLLAPSE["console"])):
        keys = _row_keys(section)
        flow = {e.key for e in S.read_commented_yaml(ROOT / rel) if e.kind in ("map", "list") and not e.section}
        missing = [k for k in _flat(yaml.safe_load((ROOT / rel).read_text(encoding="utf-8")))
                   if not _covered(k, keys, collapsed + tuple(flow))]
        assert not missing, f"{rel}: not a row of CONFIGURATION.md: {missing}"


def test_every_connector_setting_and_every_schema_key_is_a_row():
    gen = _generated()
    connectors = _row_keys(gen[gen.index("### Connectors"):gen.index("### Packs")])
    catalogue = yaml.safe_load(cat.CATALOGUE.read_text(encoding="utf-8"))
    names = {s["name"] for s in catalogue.get("common") or []}
    for p in (catalogue.get("plugins") or {}).values():
        names |= {s["name"] for s in p.get("settings") or []}
    names |= {s["name"] for s in S.read_java_specs(cat.TLS) + S.read_java_specs(cat.PROXY)}
    assert not names - connectors, sorted(names - connectors)
    every = _row_keys(gen)
    for sid, spec in cat.schemas()["schemas"].items():
        missing = [k["key"] for k in spec["keys"] if k["key"] not in every]
        assert not missing, f"{sid}: {missing}"


def test_every_key_of_the_config_files_is_a_row():
    every = _row_keys(_generated())
    for rel in ("config/store/store.yaml", "config/runs/runs.yaml", "config/edit/edit.yaml", "config/edit/lanes.yaml",
                "config/edit/policy.yaml", "config/edit/retention.yaml", "config/net.examples/node.yaml", "config/net.examples/gossip.yaml",
                "config/net.examples/routing.yaml", "config/ui-kit/brand.yaml", "config/editions/default-packs.yaml"):
        entries = [e for e in S.read_commented_yaml(ROOT / rel) if not e.section]
        missing = [e.key for e in entries if e.key not in every and not any(e.key.startswith(c + ".") for c in ("themes",))]
        assert not missing, f"{rel}: {missing}"


def test_every_environment_variable_is_indexed():
    gen = _generated()
    index = gen[gen.index("### Environment variable index"):]
    named = set(re.findall(r"^\| `([A-Z][A-Z0-9_]*)` \|", index, re.M))
    placed = set()
    for rel in ("drishti-server/src/main/resources/application.yaml", "drishti-console/config/application.yaml"):
        for line in (ROOT / rel).read_text(encoding="utf-8").splitlines():
            if not line.lstrip().startswith("#"):
                placed |= set(re.findall(r"\$\{([A-Z][A-Z0-9_]*)[:}]", S.split_comment(line)[0]))
    assert not placed - named, sorted(placed - named)
    assert not set(cat.code_env()) - named, sorted(set(cat.code_env()) - named)
    assert not set(cat.placeholder_env()) - named


def test_check_fails_on_a_key_without_a_meaning(tmp_path):
    f = tmp_path / "x.yaml"
    f.write_text("a:\n  b: 1          # the b\n  c: ${DRISHTI_C:2}\n", encoding="utf-8")
    rows = cat.yaml_rows("server", [f], "server", "restart")
    problems = cat.problems(rows, [], [])
    assert [r.key for r in rows] == ["a.b", "a.c"]
    assert rows[1].env == ["DRISHTI_C"] and rows[1].default == "2" and rows[1].type == "integer"
    assert any("`a.c` has no meaning" in p for p in problems) and not any("`a.b`" in p for p in problems)


def test_the_comment_reader():
    f = ROOT / "config" / "runs" / "runs.yaml"
    by = {e.key: e for e in S.read_commented_yaml(f)}
    assert by["inline.wait"].comment.startswith("how long a request waits")
    assert S.split_comment('color: "#2a9d8f"  # a colour') == ('color: "#2a9d8f"', "a colour")
    assert S.placeholder_default("${DRISHTI_FEEDS:${drishti.data.dir}/feeds}") == "./data/feeds"
    assert S.placeholder_default("${ANTHROPIC_API_KEY}") is None
    assert S.env_names("${A_B:${C_D:x}}") == ["A_B", "C_D"]
    assert S.since("2.9.7 (RESOURCES_AND_SCALE.md 6.4): the log") == "2.9.7" and S.since("section 5.6") == ""
    assert cat.is_secret("drishti.security.secret") and not cat.is_secret("drishti.bi.ai.max-tokens")
