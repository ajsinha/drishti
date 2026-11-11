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

"""Drishti Elements distributed (tools/elements/dist.py, pack.py, cli.py; docs/guides/EMBED_GUIDE.md):

* the zip, the npm tarballs and the WebJar hold what their manifest says, every file with a matching SHA-384 integrity hash, the same
  bytes in all three, reproducibly;
* the pack tool's dependency resolution, the banking exclusion (drishti-risk only with --include-domain banking) and its themes;
* the TypeScript declarations name every export of each library.

The browser half (a static page drawing a grid and a chart from the packed files) is test_elements_pack_browser.py."""
import base64
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "elements"))
import cli  # noqa: E402
import dist  # noqa: E402
import pack  # noqa: E402

TRAIN = dist.Train()
GENERAL = [n for n in TRAIN.libs if not TRAIN.domain(n)]


def sri(b: bytes) -> str:
    return "sha384-" + base64.b64encode(hashlib.sha384(b).digest()).decode()


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    out = tmp_path_factory.mktemp("elements-dist")
    dist.build_all(out)
    dist.build_all(out / "banking", {"banking"})
    return out


def zip_files(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as z:
        top = f"drishti-elements-{TRAIN.version}/"
        assert all(n.startswith(top) for n in z.namelist())
        return {n[len(top):]: z.read(n) for n in z.namelist()}


def tgz_files(path: Path) -> dict[str, bytes]:
    with tarfile.open(path, "r:gz") as t:
        out = {}
        for m in t.getmembers():
            assert m.name.startswith("package/") and m.isfile() and m.mode == 0o644
            out[m.name[len("package/"):]] = t.extractfile(m).read()
        return out


def test_the_zip_holds_every_general_library_with_matching_hashes(built):
    z = built / f"drishti-elements-{TRAIN.version}.zip"
    files = zip_files(z)
    man = json.loads(files["drishti-elements.json"])
    assert man["version"] == TRAIN.version and man["drishti"] == dist.product_version()
    assert list(man["libraries"]) == GENERAL and "drishti-risk" in man["excluded"]
    assert set(man["files"]) == set(files) - {"drishti-elements.json"}                 # nothing unlisted, nothing missing
    for p, rec in man["files"].items():
        assert rec["bytes"] == len(files[p]) and rec["integrity"] == sri(files[p]), p
    for lib, spec in man["libraries"].items():
        assert spec["version"] == TRAIN.libs[lib]["version"]
        for key in ("bundle", "entry", "types"):
            assert spec[key] in files, (lib, key)
        for c in spec["css"]:
            assert c in files, (lib, c)
        for f in ("README.md", "LICENSE-NOTICE.txt"):
            assert f"elements/{lib}/{f}" in files
        srcdir = dist.ELEMENTS / lib / "src"
        assert {p for p in files if p.startswith(f"elements/{lib}/src/")} == {f"elements/{lib}/src/{f.name}" for f in srcdir.glob("*.js")}
        assert files[spec["bundle"]] == (dist.ELEMENTS / lib / dist.bundle_name(lib)).read_bytes()     # the console's own bytes
    assert not any(p.startswith("elements/drishti-risk/") for p in files)
    assert "drishti-risk" not in json.loads(files["elements/drishti_elements.json"])["libraries"]
    assert "vendor/echarts/echarts.min.js" in files and "vendor/echarts/LICENSE" in files and "vendor/codemirror/codemirror.js" in files
    assert any(p.startswith("vendor/echarts/maps/") for p in files)
    themes = dist.theme_names()
    assert set(man["themes"]) == set(themes) and all(f"themes/drishti-theme-{t}.css" in files for t in themes)
    assert files["css/drishti_tokens.css"] == (dist.STATIC / dist.TOKENS).read_bytes()
    notice = files["LICENSE-NOTICE.txt"].decode()
    assert "PROPRIETARY" in notice and "Apache License 2.0" in notice and "MIT" in notice
    assert "drishti-grid" in files["CHANGELOG.md"].decode()


def test_the_banking_zip_adds_drishti_risk_under_its_own_name(built):
    files = zip_files(built / "banking" / f"drishti-elements-{TRAIN.version}+banking.zip")
    man = json.loads(files["drishti-elements.json"])
    assert man["domains"] == ["banking"] and man["libraries"]["drishti-risk"]["module"] == "banking" and man["excluded"] == []
    assert "elements/drishti-risk/drishti_risk.js" in files
    assert "drishti-risk is licensed with the banking module" in files["LICENSE-NOTICE.txt"].decode()


def test_the_npm_tarballs_are_npm_pack_shaped_with_package_names_and_hashes(built):
    names = sorted(p.name for p in (built / "npm").glob("*.tgz"))
    assert names == sorted(dist.tgz_name(TRAIN, lib) for lib in GENERAL)                 # no drishti-risk without banking
    assert (built / "banking" / "npm" / dist.tgz_name(TRAIN, "drishti-risk")).is_file()
    for lib in GENERAL:
        files = tgz_files(built / "npm" / dist.tgz_name(TRAIN, lib))
        pkg = json.loads(files["package.json"])
        s = dist.short(lib)
        assert pkg["name"] == f"@drishti/elements-{s}" and pkg["version"] == TRAIN.libs[lib]["version"]
        assert pkg["dependencies"] == {f"@drishti/elements-{dist.short(r)}": "^" + TRAIN.libs[r]["version"] for r in TRAIN.libs[lib]["requires"]}
        assert pkg["exports"]["."] == {"types": f"./drishti-{s}.d.ts", "import": "./src/drishti_index.js", "require": f"./{dist.bundle_name(lib)}"}
        assert "publishConfig" not in pkg and pkg["type"] == "commonjs" and json.loads(files["src/package.json"]) == {"type": "module"}
        for target in [pkg["main"], pkg["module"], pkg["types"]] + [v for v in pkg["exports"].values() if isinstance(v, str) and "*" not in v]:
            assert target[2:] in files, (lib, target)
        rec = json.loads(files["integrity.json"])
        assert set(rec["files"]) == set(files) - {"integrity.json"}
        for p, r in rec["files"].items():
            assert r["integrity"] == sri(files[p]), (lib, p)
        for p, b in files.items():
            if p.startswith("src/") and p.endswith(".js"):
                assert b"../../drishti-" not in b, (lib, p)                                # other libraries by package name
        bundle = files[dist.bundle_name(lib)].decode()
        for r in TRAIN.libs[lib]["requires"]:
            assert json.dumps(f"@drishti/elements-{dist.short(r)}") in bundle
        for c in TRAIN.libs[lib]["css"]:
            if dist.owner(c) in (None, lib):
                assert Path(c).name in files
    core = tgz_files(built / "npm" / dist.tgz_name(TRAIN, "drishti-core"))
    assert "drishti_tokens.css" in core and any(p.startswith("themes/") for p in core)


def test_the_three_forms_hold_the_same_bytes_and_are_reproducible(built, tmp_path):
    z = zip_files(built / f"drishti-elements-{TRAIN.version}.zip")
    web = dist.webjar_tree(TRAIN, TRAIN.version)
    prefix = f"{dist.WEBJAR}/{TRAIN.version}/"
    assert {p[len(prefix):]: b for p, b in web.items()} == z
    grid = tgz_files(built / "npm" / dist.tgz_name(TRAIN, "drishti-grid"))
    assert grid["drishti_grid.css"] == z["elements/drishti-grid/drishti_grid.css"]
    plain = next(p for p in z if p.startswith("elements/drishti-grid/src/") and b"../../drishti-" not in z[p])
    assert grid[plain[len("elements/drishti-grid/"):]] == z[plain]
    again = dist.write_tgz(dist.npm_files(TRAIN, "drishti-grid"), tmp_path / "again.tgz")
    assert again.read_bytes() == (built / "npm" / dist.tgz_name(TRAIN, "drishti-grid")).read_bytes()


def test_the_webjar_refuses_a_version_that_is_not_the_trains():
    with pytest.raises(dist.DistError, match="not the Elements train"):
        dist.webjar_tree(TRAIN, "9.9.9")
    pom = (ROOT / "packaging/drishti-elements" / "pom.xml").read_text(encoding="utf-8")
    assert re.search(r"<artifactId>drishti-elements</artifactId>\s*<version>([^<]+)</version>", pom).group(1) == TRAIN.version
    assert "<module>drishti-elements</module>" in (ROOT / "pom.xml").read_text(encoding="utf-8")


def test_the_maven_profile_builds_the_webjar(tmp_path):
    exec_plugin = Path.home() / ".m2" / "repository" / "org" / "codehaus" / "mojo" / "exec-maven-plugin" / "3.6.3"
    if not exec_plugin.is_dir() or not shutil.which("java"):
        pytest.skip("needs Java and the exec-maven-plugin in the local Maven repository (an offline build)")
    env = dict(os.environ, TMPDIR=str(tmp_path))
    r = subprocess.run([str(ROOT / "mvnw"), "-o", "-q", "-P", "elements", "-pl", "drishti-elements", "package",
                        f"-Ddrishti.elements.target={tmp_path / 'target'}"], cwd=ROOT, env=env, capture_output=True, text=True, timeout=300)
    assert r.returncode == 0, r.stdout[-3000:] + r.stderr[-3000:]
    jar = tmp_path / "target" / f"drishti-elements-{TRAIN.version}.jar"
    with zipfile.ZipFile(jar) as j:
        names = set(j.namelist())
        prefix = f"{dist.WEBJAR}/{TRAIN.version}/"
        man = json.loads(j.read(prefix + "drishti-elements.json"))
        for p, rec in man["files"].items():
            assert prefix + p in names
            assert rec["integrity"] == sri(j.read(prefix + p)), p


# -- the pack tool --------------------------------------------------------------------------------------------------------------------
def test_dependencies_resolve_deepest_first_each_once():
    assert TRAIN.resolve(["grid"]) == ["drishti-core", "drishti-inputs", "drishti-grid"]          # the grid draws its choices with drishti-inputs' Select
    assert TRAIN.resolve(["chart", "drishti-grid", "core"]) == ["drishti-core", "drishti-inputs", "drishti-chart", "drishti-grid"]
    r = TRAIN.resolve(["renderer"])
    assert r[-1] == "drishti-renderer" and set(r) == {"drishti-core", "drishti-inputs", "drishti-grid", "drishti-chart", "drishti-data", "drishti-graph", "drishti-tree", "drishti-renderer"}
    assert r.index("drishti-core") == 0
    assert TRAIN.resolve(["all"]) == GENERAL
    assert TRAIN.resolve(["insight"]) == ["drishti-core", "drishti-insight"]           # library 6 is in the manifest: packing includes it
    with pytest.raises(dist.DistError, match="no library nope"):
        TRAIN.resolve(["nope"])


def test_drishti_risk_is_refused_without_the_banking_domain(tmp_path, capsys):
    with pytest.raises(dist.DistError, match="--include-domain banking"):
        TRAIN.resolve(["risk"])
    assert TRAIN.resolve(["risk"], {"banking"}) == ["drishti-core", "drishti-inputs", "drishti-grid", "drishti-chart", "drishti-risk"]
    assert "drishti-risk" in TRAIN.resolve(["all"], {"banking"})
    assert cli.main(["pack", "--libs", "core,risk", "--out", str(tmp_path / "no")]) == 1
    assert "licensed with the banking module" in capsys.readouterr().err
    assert not (tmp_path / "no").exists()
    assert cli.main(["pack", "--libs", "risk", "--include-domain", "banking", "--out", str(tmp_path / "yes")]) == 0
    js = (tmp_path / "yes" / pack.JS_NAME).read_text(encoding="utf-8")
    assert "drishti-risk 0" in js[:2000] and "domains: banking" in js[:2000]


def test_the_pack_names_what_it_holds_and_carries_one_theme(tmp_path, capsys):
    assert cli.main(["pack", "--libs", "grid", "--theme", "light", "--out", str(tmp_path), "--no-engines"]) == 0
    said = capsys.readouterr().out
    assert said.startswith("packed: drishti-core ") and "drishti-grid" in said and "theme light" in said
    js = (tmp_path / pack.JS_NAME).read_text(encoding="utf-8")
    css = (tmp_path / pack.CSS_NAME).read_text(encoding="utf-8")
    head = js[:1500]
    assert "drishti-core 1" in head and "drishti-grid 0" in head and "drishti-chart" not in head
    assert "echarts" not in js.lower()[:5000] and js.count("PROPRIETARY") == 1
    assert js.index("drishti-core ") < js.index("/* ---- drishti-grid")
    assert re.findall(r'data-theme="([\w-]+)"', css) == ["light"]                                          # one theme, on :root
    assert "--d-accent:" in css and "drishti_grid.css" in css and css.count("PROPRIETARY") == 1
    rec = json.loads((tmp_path / pack.RECORD).read_text(encoding="utf-8"))
    assert rec["libraries"] == {n: TRAIN.libs[n]["version"] for n in ("drishti-core", "drishti-inputs", "drishti-grid")}
    assert rec["files"][pack.JS_NAME]["integrity"] == sri((tmp_path / pack.JS_NAME).read_bytes())
    assert sorted(p.name for p in (tmp_path / "vendor").iterdir()) == ["codemirror"]                       # only drishti-inputs' code editor engine (a formula cell)


def test_themes_aliases_and_a_theme_sheet_carries_every_token():
    table = dist.themes()
    names = dist.theme_names(table)
    default = next(t for t in names if table[t]["default"])
    assert dist.theme_alias("dark", table) == default and table[dist.theme_alias("light", table)]["scheme"] == "light"
    with pytest.raises(dist.DistError, match="no theme"):
        dist.theme_alias("purple", table)
    tokens = (dist.STATIC / dist.TOKENS).read_text(encoding="utf-8")
    every = set(re.findall(r"(--d-[\w-]+)\s*:", tokens))
    for t in names:
        sheet = dist.theme_css(t, table)
        assert every <= set(re.findall(r"(--d-[\w-]+)\s*:", sheet)), t
        assert ("@media print" in sheet) == ("@media print" in tokens) and "prefers-color-scheme" not in sheet


def test_the_declarations_name_every_export():
    sys.path.insert(0, str(ROOT / "tools" / "docs"))
    import elements_api
    for lib in TRAIN.libs:
        files = dist.library_tree(TRAIN, lib)
        d = files[f"elements/{lib}/drishti-{dist.short(lib)}.d.ts"].decode()
        declared = set(re.findall(r"^export (?:declare (?:function|class|const)|interface|type) ([\w$]+)", d, re.M))
        for name, _ in elements_api.exports_of((dist.ELEMENTS / lib / TRAIN.libs[lib]["entry"]).resolve()):
            assert name in declared, (lib, name)
        assert d.count("{") == d.count("}") and d.count("(") == d.count(")"), lib
        assert "undefined;" not in d and ": ;" not in d
