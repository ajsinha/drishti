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

"""``drishti.py build jar|modules|all`` (tools/launch/modulesbuild.py): each is ONE Maven command (never install; Maven itself installs the jar
and the packages, switched off by --no-copy), and the freshness rule (R2) asked of the server jar's packer (``module fresh``): a stale package
named with the command that mends it, the start refusing it, and a test session over a built checkout refusing it. No Maven runs here; the
freshness tests pack tiny content modules in a scratch folder with the Java packer and need the server jar in lib/ (else skipped). The
packer's own rules are JUnit's (ModulePackTest)."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "launch"))
import javacmd  # noqa: E402
import modulesbuild  # noqa: E402
import procs  # noqa: E402
import serverstart  # noqa: E402

DRISHTI = [sys.executable, str(ROOT / "tools" / "drishti.py")]


def run(*args) -> subprocess.CompletedProcess:
    return subprocess.run([*DRISHTI, *args], capture_output=True, text=True, timeout=120, cwd=str(ROOT))


@pytest.fixture
def jar() -> Path:
    try:
        javacmd.find_java(None)
        return javacmd.server_jar()
    except Exception:  # no jar or no java in this checkout
        pytest.skip("needs the server jar in lib/ (python tools/drishti.py build jar) and a JDK: the packer is the jar's")


def _module(modules: Path, mid: str = "greet", version: str = "0.1.0", text: str = "a: 1\n") -> Path:
    m = modules / mid
    (m / "packs").mkdir(parents=True, exist_ok=True)
    (m / "module.yaml").write_text(f"format: 1\nid: {mid}\nversion: {version}\ntitle: {mid}\n", encoding="utf-8")
    (m / "packs" / "a.yaml").write_text(text, encoding="utf-8")
    return m


def _built(modules: Path, m: Path) -> Path:
    built = modules.parent / "_built"
    dmod = modulesbuild.pack(m, built)
    r = modulesbuild.module_cli(["install", built, "--into", modules])
    assert r.returncode == 0, r.stderr
    return modules / dmod.name


def test_build_jar_is_one_maven_run_that_installs_the_jar():
    r = run("build", "jar", "--dry-run")
    assert r.returncode == 0, r.stderr
    assert "clean package -DskipTests -pl :drishti-app -am" in r.stdout and " install" not in r.stdout.replace("drishti.install", "")
    assert "-Ddrishti.home.lib=" in r.stdout and "drishti.install.skip" not in r.stdout


def test_build_all_is_one_maven_run_of_the_jar_and_the_modules_and_no_copy_installs_nothing():
    r = run("build", "all", "--dry-run")
    assert r.returncode == 0, r.stderr
    assert "-pl :drishti-app,:drishti-modules -am" in r.stdout
    assert "-Ddrishti.kernel.jar" not in r.stdout, "the packages are stamped for the exec jar this very run builds"
    assert "-Ddrishti.home.lib=" in r.stdout and "-Ddrishti.home.modules=" in r.stdout
    r = run("build", "all", "--dry-run", "--no-copy", "--offline")
    assert "-Ddrishti.install.skip=true" in r.stdout and "drishti.home" not in r.stdout and " -o " in r.stdout


def test_build_modules_stamps_for_the_jar_in_lib_and_takes_ids():
    r = run("build", "modules", "--dry-run", "source-endur", "economy")
    assert r.returncode == 0, r.stderr
    assert "clean package -DskipTests -pl :drishti-modules -am" in r.stdout
    assert "-Pmodules-only" in r.stdout
    assert "-Ddrishti.modules.ids=source-endur,economy" in r.stdout
    r = run("build", "modules", "--dry-run", "no-such-module")
    assert r.returncode == 2 and "no module no-such-module" in r.stderr


def test_no_python_packer_is_left():
    """One way per job: the packer is Java (ModuleCli module pack); no Python copy of it, and Maven runs no Python to build."""
    assert not (ROOT / "tools" / "modulepack.py").exists()
    for pom in ("packaging/dist/pom.xml", "packaging/drishti-app/pom.xml", "testing/drishti-modules-it/pom.xml", "drishti-sdk/drishti-module-parent/pom.xml"):
        text = (ROOT / pom).read_text(encoding="utf-8")
        assert "drishti.python" not in text and ".py</argument>" not in text and "modulepack" not in text, pom


def test_a_newer_version_replaces_the_older_file_of_the_same_module_and_no_other(tmp_path, jar):
    modules = tmp_path / "modules"
    other_dmod = _built(modules, _module(tmp_path / "a", "greet-more", "1.0.0"))
    _built(modules, _module(tmp_path / "b", "greet", "0.1.0"))
    _built(modules, _module(tmp_path / "c", "greet", "0.2.0"))
    names = sorted(p.name for p in modules.glob("*.dmod"))
    assert names == ["greet-0.2.0.dmod", "greet-more-1.0.0.dmod"] and other_dmod.is_file()
    assert not list(modules.glob("*.part")) and (modules / "first-party.yaml").is_file()


def test_a_changed_module_folder_is_stale_and_the_words_name_the_module_and_the_fix(tmp_path, jar):
    modules = tmp_path / "modules"
    folder = _module(modules)
    _built(modules, folder)
    assert modulesbuild.fresh_report(modules) == []
    (folder / "packs" / "a.yaml").write_text("a: 2\n", encoding="utf-8")
    (said,) = modulesbuild.fresh_report(modules)
    assert "modules/greet changed since greet-0.1.0.dmod was built" in said and "packs/a.yaml" in said
    assert said.endswith("python tools/drishti.py build modules greet")


def test_a_module_folder_with_no_package_is_named_too(tmp_path, jar):
    modules = tmp_path / "modules"
    _module(modules)
    (said,) = modulesbuild.fresh_report(modules)
    assert "modules/greet has no package (greet-0.1.0.dmod)" in said
    assert "holds module sources and no .dmod" in modulesbuild.missing_report(modules)[0]


def test_an_install_holds_packages_and_no_sources_and_needs_no_java_to_be_current(tmp_path, jar):
    modules = tmp_path / "install" / "modules"
    _built(modules, _module(tmp_path / "src"))
    assert modulesbuild.fresh_report(modules, runner=tmp_path / "no.jar") == [] and modulesbuild.missing_report(modules) == []


def args(**kw):
    return type("A", (), {"modules_dev": False, **kw})()


def test_start_server_refuses_sources_without_a_package_and_a_stale_package_in_words(tmp_path, jar):
    modules = tmp_path / "modules"
    folder = _module(modules)
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


def test_a_test_session_over_a_built_checkout_refuses_a_stale_package_and_over_a_fresh_checkout_does_not(tmp_path, jar):
    assert modulesbuild.session_refusal(tmp_path) is None, "no package ever built: nothing to refuse"
    (tmp_path / "lib").mkdir()
    try:
        (tmp_path / "lib" / jar.name).symlink_to(jar)
    except OSError:                                                  # Windows without the right to make links
        pytest.skip("cannot link the server jar into a scratch lib/")
    modules = tmp_path / "modules"
    folder = _module(modules)
    _built(modules, folder)
    assert modulesbuild.session_refusal(tmp_path) is None
    (folder / "packs" / "b.yaml").write_text("b: 1\n", encoding="utf-8")
    assert "modules/greet changed since" in modulesbuild.session_refusal(tmp_path)


def test_this_checkouts_packages_are_current():
    """The test conftest refuses a session otherwise; here the words, so a failing run names the module."""
    assert modulesbuild.session_refusal(ROOT) is None
