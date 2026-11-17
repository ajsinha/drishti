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

"""``drishti.py build modules|all`` and the freshness rule (R2) of the modules beside the server jar (tools/launch/modulesbuild.py): the Maven
lines, the packages going into the modules folder, a stale package named with the command that mends it, the start refusing it, and a
test session over a built checkout refusing it. No Maven runs here: the module folders are tiny content modules in a scratch folder."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "launch"))
import modulepack  # noqa: E402
import modulesbuild  # noqa: E402
import procs  # noqa: E402
import serverstart  # noqa: E402

DRISHTI = [sys.executable, str(ROOT / "tools" / "drishti.py")]


def _module(modules: Path, mid: str = "greet", version: str = "0.1.0", text: str = "a: 1\n") -> Path:
    m = modules / mid
    (m / "packs").mkdir(parents=True, exist_ok=True)
    (m / "module.yaml").write_text(f"format: 1\nid: {mid}\nversion: {version}\ntitle: {mid}\n", encoding="utf-8")
    (m / "packs" / "a.yaml").write_text(text, encoding="utf-8")
    return m


def _built(modules: Path, m: Path) -> Path:
    dmod, _ = modulepack.pack(m, out=modules / "_built")
    modulesbuild._put(dmod, modules)
    return modules / dmod.name


def run(*args) -> subprocess.CompletedProcess:
    return subprocess.run([*DRISHTI, *args], capture_output=True, text=True, timeout=120, cwd=str(ROOT))


def test_build_modules_dry_run_prints_the_maven_line_and_the_modules_it_packs_itself():
    r = run("build", "modules", "--dry-run")
    assert r.returncode == 0, r.stderr
    assert "clean package -DskipTests -pl :drishti-modules -am" in r.stdout and " install" not in r.stdout
    assert "-Dspring-boot.repackage.skip=true" in r.stdout and "-Ddrishti.kernel.jar=" in r.stdout
    assert "source-endur" in r.stdout and "economy" in r.stdout          # the modules with no Java: packed from their folders


def test_build_all_is_one_maven_run_of_the_jar_and_the_modules():
    r = run("build", "all", "--dry-run")
    assert r.returncode == 0, r.stderr
    assert "-pl :drishti-app,:drishti-modules -am" in r.stdout and " install" not in r.stdout
    assert "-Ddrishti.kernel.jar" not in r.stdout, "the packages are stamped for the exec jar this very run builds"


def test_a_module_with_no_java_is_packed_without_maven_and_goes_in_through_a_part_file(tmp_path):
    modules = tmp_path / "modules"
    folder = _module(modules)
    assert not modulesbuild.has_java(folder)
    dmod = _built(modules, folder)
    assert dmod.is_file() and not list(modules.glob("*.part"))
    assert modulesbuild.fresh_report(modules) == []


def test_a_newer_version_replaces_the_older_file_of_the_same_module_and_no_other(tmp_path):
    modules = tmp_path / "modules"
    other = _module(modules, "greet-more", "1.0.0")
    other_dmod = _built(modules, other)
    _built(modules, _module(modules, "greet", "0.1.0"))
    _built(modules, _module(modules, "greet", "0.2.0"))
    names = sorted(p.name for p in modules.glob("*.dmod"))
    assert names == ["greet-0.2.0.dmod", "greet-more-1.0.0.dmod"] and other_dmod.is_file()


def test_a_changed_module_folder_is_stale_and_the_words_name_the_module_and_the_fix(tmp_path):
    modules = tmp_path / "modules"
    folder = _module(modules)
    _built(modules, folder)
    (folder / "packs" / "a.yaml").write_text("a: 2\n", encoding="utf-8")
    (said,) = modulesbuild.fresh_report(modules)
    assert "modules/greet changed since greet-0.1.0.dmod was built" in said and "packs/a.yaml" in said
    assert said.endswith("python tools/drishti.py build modules greet")


def test_a_module_folder_with_no_package_is_named_too(tmp_path):
    modules = tmp_path / "modules"
    _module(modules)
    (said,) = modulesbuild.fresh_report(modules)
    assert "modules/greet has no package (greet-0.1.0.dmod)" in said
    assert "holds module sources and no .dmod" in modulesbuild.missing_report(modules)[0]


def test_an_install_holds_packages_and_no_sources_and_is_never_stale(tmp_path):
    modules = tmp_path / "install" / "modules"
    folder = _module(tmp_path / "src")
    dmod, _ = modulepack.pack(folder, out=tmp_path / "o")
    modules.mkdir(parents=True)
    modulesbuild._put(dmod, modules)
    assert modulesbuild.fresh_report(modules) == [] and modulesbuild.missing_report(modules) == []


def args(**kw):
    return type("A", (), {"modules_dev": False, **kw})()


def test_start_server_refuses_sources_without_a_package_and_a_stale_package_in_words(tmp_path):
    modules = tmp_path / "modules"
    folder = _module(modules)
    jar = tmp_path / "x.jar"
    with pytest.raises(procs.LaunchError, match=r"holds module sources and no \.dmod.*build modules"):
        serverstart.check_modules(args(), modules, jar)
    _built(modules, folder)
    assert "1 package(s)" in serverstart.check_modules(args(), modules, jar)
    (folder / "packs" / "a.yaml").write_text("a: 2\n", encoding="utf-8")
    with pytest.raises(procs.LaunchError, match=r"modules/greet changed since greet-0\.1\.0\.dmod was built.*build modules greet"):
        serverstart.check_modules(args(), modules, jar)
    assert "development switch" in serverstart.check_modules(args(modules_dev=True), modules, jar)


def test_an_empty_modules_folder_starts_a_server_with_no_module(tmp_path):
    modules = tmp_path / "modules"
    modules.mkdir()
    assert "modules: none" in serverstart.check_modules(args(), modules, tmp_path / "x.jar")


def test_a_test_session_over_a_built_checkout_refuses_a_stale_package_and_over_a_fresh_checkout_does_not(tmp_path):
    assert modulesbuild.session_refusal(tmp_path) is None, "no package ever built: nothing to refuse"
    modules = tmp_path / "modules"
    folder = _module(modules)
    _built(modules, folder)
    assert modulesbuild.session_refusal(tmp_path) is None
    (folder / "packs" / "b.yaml").write_text("b: 1\n", encoding="utf-8")
    assert "modules/greet changed since" in modulesbuild.session_refusal(tmp_path)


def test_this_checkouts_packages_are_current():
    """The test conftest refuses a session otherwise; here the words, so a failing run names the module."""
    assert modulesbuild.session_refusal(ROOT) is None
