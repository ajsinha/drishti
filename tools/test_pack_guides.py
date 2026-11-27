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

"""Every pack's data is documented (the product owner's request of 2026-10-26: data contract, data retrieval and details for all packs):
each pack with contracts has a README that ends with the generated Data contracts and Data retrieval sections, naming every kind with
links to its contract and its example (never its columns); every stored kind has an origin in data_retrieval.yaml (an external source
or a generator); every external source names its data feed (or the generator it is typed into) and a licence; the pack's story (its
guide until i7k) is a section of a feature guide that links the README's two sections, and the README links back to it and to the
External data page when it has external sources."""
from __future__ import annotations

import importlib.util
import os
import re
import sys
from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

ROOT = Path(__file__).resolve().parents[1]


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


GD = _load("drishti_guide_data", ROOT / "tools" / "packgen" / "common" / "guide_data.py")
PACKS = [d for d in GD.PP.pack_dirs().values() if (d / "contracts").is_dir() and list((d / "contracts").glob("*.contract.yaml"))]
SPEC = GD.spec()


def test_the_ten_packs_with_contracts_are_all_here():
    assert [p.name for p in PACKS] == sorted(SPEC["packs"]), "a pack with contracts needs its origins in data_retrieval.yaml"


@pytest.mark.parametrize("pack", PACKS, ids=lambda d: d.name)
def test_the_readme_has_both_sections_and_links_every_kind(pack):
    text = (pack / "README.md").read_text()
    assert "\n## Data contracts\n" in text and "\n## Data retrieval\n" in text, pack.name
    assert text.index("## Data contracts") < text.index("## Data retrieval")
    contracts = text[text.index("## Data contracts"):text.index("## Data retrieval")]
    retrieval = text[text.index("## Data retrieval"):]
    examples = GD.sample_anchors()
    for c in GD.contracts(pack):
        assert f"| `{c['mnemonic']}` | `{c['kind']}` |" in contracts, f"{pack.name}: {c['kind']}"
        assert f"docs/contracts/packs/{pack.name}.md#" in contracts and f"(`contracts/{c['_file']}`)" in contracts, f"{pack.name}: {c['kind']}"
        if c["kind"] in examples:
            page, anchor = examples[c["kind"]]
            assert f"docs/contracts/packs/{page}.md#{anchor})" in contracts, f"{pack.name}: {c['kind']} does not link its example"
    assert "| Column | Type |" not in contracts and "\nNested `" not in contracts, f"{pack.name}: a copied field table"
    for c in GD.contracts(pack):
        assert f"| `{c['mnemonic']}` | `{c['kind']}` |" in retrieval, f"{pack.name}: {c['kind']} has no origin row"
    assert "WORLD_DATA_GUIDE.md" in retrieval and "lake demo" in retrieval and "Offline" in retrieval
    for link in re.findall(r"\]\((\.\./[^)#\s]+)", text):          # every relative link of the generated sections resolves
        assert (pack / link).resolve().exists(), f"{pack.name}: {link}"


@pytest.mark.parametrize("pack", PACKS, ids=lambda d: d.name)
def test_every_stored_kind_has_an_origin_and_every_source_a_script(pack):
    for c in GD.contracts(pack):
        if c.get("class") in ("computed", "drishti-owned"):
            continue
        o = GD._origin(SPEC, pack.name, c)
        assert o.get("external") or o.get("generator"), f"{pack.name}/{c['kind']}: no origin in data_retrieval.yaml"
        for i in (o.get("external") or []) + (o.get("official") or []):
            e = SPEC["external"][i]
            pulled = (ROOT / "config" / "feeds" / f"{e['feed']}.yaml").is_file() if e.get("feed") else (ROOT / e["script"]).is_file()
            assert e["licence"] and e["url"].startswith("https://") and pulled, i
        if o.get("generator"):
            assert o["generator"] in SPEC["generators"], o


def test_internal_only_sources_are_the_imf_and_yahoo_and_google_news_and_the_non_commercial_publishers():
    internal = sorted(i for i, e in SPEC["external"].items() if e.get("internal"))
    # press-noncommercial: the Bank of England, OSFI and the Canada Gazette allow no commercial reuse (config/feeds/official-news.yaml)
    assert internal == ["google-news", "imf", "press-noncommercial", "yahoo-prices", "yahoo-quote"]


@pytest.mark.parametrize("pack", PACKS, ids=lambda d: d.name)
def test_the_story_is_a_feature_guide_section_that_links_the_readme(pack):
    home = GD.story(pack)
    assert home and home[1], f"{pack.name}: no '## ' section about the pack in the new home of guides/{pack.name}.md (tools/docs/moved.yaml)"
    guide = ROOT / home[0]
    text = guide.read_text()
    rel = os.path.relpath(pack / "README.md", guide.parent).replace(os.sep, "/")
    assert f"]({rel}#data-contracts)" in text and f"]({rel}#data-retrieval)" in text, f"{pack.name}: {home[0]} does not link the README"


@pytest.mark.parametrize("pack", PACKS, ids=lambda d: d.name)
def test_the_readme_points_to_the_story_and_the_external_data_page(pack):
    text = (pack / "README.md").read_text()
    home = GD.story(pack)
    rel = os.path.relpath(ROOT / home[0], pack).replace(os.sep, "/")
    assert f"]({rel}#{home[1]})" in text and "*Data contracts*" in text and "*Data retrieval*" in text, pack.name
    externals = {i for o in [(SPEC["packs"][pack.name].get("default") or {})] + list((SPEC["packs"][pack.name].get("kinds") or {}).values())
                 for i in (o.get("external") or []) + (o.get("official") or [])}
    if externals:
        assert "docs/guides/WORLD_DATA_GUIDE.md" in text, pack.name


def _help_addresses() -> tuple[set[str], dict[str, str]]:
    """(the slugs the console's Help catalogue lists, the moved: rows) of drishti-console/config/help.yaml."""
    data = yaml.safe_load((ROOT / "drishti-console" / "config" / "help.yaml").read_text(encoding="utf-8"))
    slugs = {str(x["slug"]) for sec in data["sections"] for grp in sec.get("groups") or [] for g in grp.get("guides") or []
             for x in [g, *(g.get("children") or [])] if x.get("slug")}
    return slugs, {str(k): str(v) for k, v in data["moved"].items()}


def test_every_about_guide_slug_resolves_to_a_help_page_or_a_moved_row():
    """The About drawer links /help/<guide>: the pack guides are gone, so each such slug is a Help page or a moved: row whose target is one
    (a module page, ``module-<id>-<n>``, exists while its module is on)."""
    slugs, moved = _help_addresses()
    seen: dict[str, list[str]] = {}
    for f in [*ROOT.glob("modules/*/packs/*/config/about.yaml"), *ROOT.glob("config/packs/*/*/config/about.yaml"), *ROOT.glob("examples/packs/*/config/about.yaml"),
              *ROOT.glob("tools/packgen/**/*.yaml")]:
        for ln in f.read_text(encoding="utf-8").splitlines():
            m = re.match(r"^\s+guide: ([A-Za-z0-9_-]+)(#\S*)?\s*(#.*)?$", ln)
            if m:
                seen.setdefault(m.group(1), []).append(str(f.relative_to(ROOT)))
    assert seen, "no about.yaml names a guide: the check would be empty"
    dead = {}
    for slug, where in seen.items():
        target = moved.get(slug, slug).split("#")[0]
        if target not in slugs and not re.fullmatch(r"module-[a-z0-9-]+-\d+", target) and not (ROOT / "docs").glob("**/" + target):
            dead[slug] = where[:2]
    assert not dead, f"a guide: that opens no Help page (add the pack guide's new home to tools/docs/moved.yaml, or drop the guide:): {dead}"


def test_the_pack_guide_rows_of_help_yaml_are_the_generated_ones():
    sys.path.insert(0, str(ROOT / "tools" / "docs"))
    import pack_guide_moves
    assert pack_guide_moves.main(["--check"]) == 0
