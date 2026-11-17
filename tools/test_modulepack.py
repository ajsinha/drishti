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


"""tools/modulepack.py, the one module packager: the same input twice gives the same bytes; only the layout's names are packed and a
stray is said; the allowance is stamped; the hash list matches the entries; first-party.yaml names the hash list's SHA-256."""
from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import modulepack  # noqa: E402

MODULE_YAML = "format: 1\nid: greet\nversion: 0.1.0\ntitle: Greet\n"


def _module(tmp: Path, extra: str = "") -> Path:
    m = tmp / "modules" / "greet"
    (m / "packs").mkdir(parents=True)
    (m / "docs").mkdir()
    (m / "module.yaml").write_text(MODULE_YAML + extra, encoding="utf-8")
    (m / "packs" / "a.yaml").write_text("a: 1\n", encoding="utf-8")
    (m / "docs" / "z.md").write_text("# z\n", encoding="utf-8")
    return m


def _names(dmod: Path) -> list[str]:
    with zipfile.ZipFile(dmod) as z:
        return z.namelist()


def test_the_same_input_twice_gives_the_same_bytes(tmp_path):
    m = _module(tmp_path)
    a, _ = modulepack.pack(m, out=tmp_path / "o1")
    b, _ = modulepack.pack(m, out=tmp_path / "o2")
    assert a.read_bytes() == b.read_bytes()
    assert a.name == "greet-0.1.0.dmod"
    with zipfile.ZipFile(a) as z:
        assert {i.date_time for i in z.infolist()} == {(1980, 1, 1, 0, 0, 0)}


def test_module_yaml_is_first_and_stored_and_the_hash_list_is_last(tmp_path):
    dmod, _ = modulepack.pack(_module(tmp_path), out=tmp_path / "o")
    with zipfile.ZipFile(dmod) as z:
        first = z.infolist()[0]
        assert first.filename == "module.yaml" and first.compress_type == zipfile.ZIP_STORED
        assert z.namelist()[-1] == "META-INF/DMOD.MF"
        assert z.namelist() == ["module.yaml", "docs/z.md", "packs/a.yaml", "META-INF/DMOD.MF"]


def test_the_hash_list_holds_every_entry_but_itself(tmp_path):
    dmod, _ = modulepack.pack(_module(tmp_path), out=tmp_path / "o")
    with zipfile.ZipFile(dmod) as z:
        lines = z.read("META-INF/DMOD.MF").decode().splitlines()
        assert lines[0].startswith("# DMOD.MF format 1")
        listed = {l.split("  ", 1)[1]: l.split("  ", 1)[0] for l in lines[1:]}
        assert set(listed) == set(z.namelist()) - {"META-INF/DMOD.MF"}
        for name, sha in listed.items():
            assert hashlib.sha256(z.read(name)).hexdigest() == sha


def test_a_name_outside_the_layout_is_left_out_and_said(tmp_path):
    m = _module(tmp_path)
    (m / "old-build").mkdir()
    (m / "old-build" / "x.txt").write_text("x", encoding="utf-8")
    (m / "pom.xml").write_text("<project/>", encoding="utf-8")      # builds the module: allowed, never packed
    dmod, notes = modulepack.pack(m, out=tmp_path / "o")
    assert not any(n.startswith(("old-build", "pom.xml")) for n in _names(dmod))
    assert len(notes) == 1 and "old-build" in notes[0] and "not part of the module layout" in notes[0]


def test_only_jars_directly_in_java_are_packed_and_pycache_never(tmp_path):
    m = _module(tmp_path)
    (m / "java" / "proj" / "src").mkdir(parents=True)
    (m / "java" / "proj" / "src" / "A.java").write_text("class A {}", encoding="utf-8")
    (m / "java" / "greet.jar").write_bytes(b"PK-not-really")
    (m / "python" / "__pycache__").mkdir(parents=True)
    (m / "python" / "__pycache__" / "x.pyc").write_bytes(b"\0")
    (m / "python" / "x.py").write_text("x = 1\n", encoding="utf-8")
    names = _names(modulepack.pack(m, out=tmp_path / "o")[0])
    assert "java/greet.jar" in names and "python/x.py" in names
    assert not any("A.java" in n or "__pycache__" in n for n in names)


def test_a_given_jar_is_placed_by_the_name_module_yaml_gives(tmp_path):
    m = _module(tmp_path, "java:\n  jars:\n  - greet.jar\n  - more.jar\n  lib:\n  - dep.jar\n")
    built, more, dep = (tmp_path / n for n in ("built-1.0.jar", "more.jar", "dep.jar"))
    for j in (built, more, dep):
        j.write_bytes(j.name.encode())
    names = _names(modulepack.pack(m, jars=[built, more, dep], out=tmp_path / "o")[0])
    assert {"java/greet.jar", "java/more.jar", "lib/dep.jar"} <= set(names)


def test_a_jar_module_yaml_does_not_name_is_refused_and_a_named_one_must_be_there(tmp_path):
    m = _module(tmp_path, "java:\n  jars:\n  - greet.jar\n")
    own, other = tmp_path / "own.jar", tmp_path / "other.jar"
    own.write_bytes(b"1")
    other.write_bytes(b"2")
    with pytest.raises(modulepack.PackError, match="in neither java.jars nor java.lib"):
        modulepack.pack(m, jars=[own, other], out=tmp_path / "o")
    with pytest.raises(modulepack.PackError, match="neither in the folder nor given"):
        modulepack.pack(m, out=tmp_path / "o")


def test_the_allowance_is_stamped_with_the_kernel_build(tmp_path):
    m = _module(tmp_path, "kernel-internals: java/kernel-internals.txt\n")
    (m / "java").mkdir()
    (m / "java" / "kernel-internals.txt").write_text("# the allowance\ncom.ash.one  # why\ncom.ash.two\n", encoding="utf-8")
    dmod, _ = modulepack.pack(m, out=tmp_path / "o", kernel_build="2.9.11.i9+20261116")
    with zipfile.ZipFile(dmod) as z:
        text = z.read("module.yaml").decode()
    assert "kernel-internals:\n- com.ash.one\n- com.ash.two\n" in text
    assert text.rstrip().endswith("kernel-build: '2.9.11.i9+20261116'")
    with pytest.raises(modulepack.PackError, match="--kernel-build"):
        modulepack.pack(m, out=tmp_path / "o2", lib_dir=tmp_path / "no-lib")


def test_content_yaml_brings_content_in_and_trims_and_is_not_packed(tmp_path):
    m = _module(tmp_path)
    (tmp_path / "shared" / "keep").mkdir(parents=True)
    (tmp_path / "shared" / "keep" / "k.yaml").write_text("k: 1\n", encoding="utf-8")
    (tmp_path / "shared" / "skip").mkdir()
    (tmp_path / "shared" / "skip" / "s.yaml").write_text("s: 1\n", encoding="utf-8")
    (m / "content.yaml").write_text("copy:\n- {from: ../../shared, to: data/shared, leave-out: [skip]}\ntrim:\n- {path: packs, leave-out: [a.yaml]}\n",
                                    encoding="utf-8")
    names = _names(modulepack.pack(m, out=tmp_path / "o")[0])
    assert "data/shared/keep/k.yaml" in names
    assert "content.yaml" not in names and "packs/a.yaml" not in names and not any("skip" in n for n in names)


@pytest.mark.parametrize("served", sorted(modulepack.SERVED))
def test_content_yaml_never_leaves_out_what_a_pack_serves(tmp_path, served):
    m = _module(tmp_path)
    (m / "content.yaml").write_text(f"trim:\n- {{path: packs, leave-out: [tests, {served}]}}\n", encoding="utf-8")
    with pytest.raises(modulepack.PackError, match="serves at run time"):
        modulepack.pack(m, out=tmp_path / "o")


def test_no_shipped_module_leaves_out_what_a_pack_serves():
    for f in sorted((ROOT / "modules").glob("*/content.yaml")):
        spec = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        for e in (spec.get("trim") or []) + (spec.get("copy") or []):
            assert not {str(x).strip("/").split("/")[-1] for x in e.get("leave-out") or []} & modulepack.SERVED, f"{f}: {e}"


def test_first_party_lists_the_sha256_of_each_hash_list(tmp_path):
    d, _ = modulepack.pack(_module(tmp_path), out=tmp_path / "mods")
    out = modulepack.first_party(tmp_path / "mods")
    with zipfile.ZipFile(d) as z:
        sha = hashlib.sha256(z.read("META-INF/DMOD.MF")).hexdigest()
    assert out.name == "first-party.yaml"
    assert f"{{id: greet, version: 0.1.0, sha256: {sha}}}" in out.read_text(encoding="utf-8")


def test_a_folder_without_module_yaml_is_refused(tmp_path):
    (tmp_path / "empty").mkdir()
    with pytest.raises(modulepack.PackError, match="module.yaml"):
        modulepack.pack(tmp_path / "empty", out=tmp_path / "o")


# ------------------------------------------------------------------------------------------------------------------ R2: stale packages
def test_the_digest_is_the_content_lines_of_the_hash_list_of_the_package(tmp_path):
    m = _module(tmp_path)
    dmod, _ = modulepack.pack(m, out=tmp_path / "out")
    with zipfile.ZipFile(dmod) as z:
        held = z.read("META-INF/DMOD.MF").decode("utf-8")
    assert modulepack.digest(m) == held
    assert modulepack.stale(m, dmod) == []


def test_a_source_edit_makes_the_package_stale_by_content_and_never_by_time(tmp_path):
    m = _module(tmp_path)
    dmod, _ = modulepack.pack(m, out=tmp_path / "out")
    f = m / "packs" / "a.yaml"
    import os
    os.utime(f, (1, 1))                                       # a time alone is not a change
    assert modulepack.stale(m, dmod) == []
    f.write_text("a: 2\n", encoding="utf-8")
    assert modulepack.stale(m, dmod) == ["packs/a.yaml"]
    (m / "packs" / "new.yaml").write_text("n: 1\n", encoding="utf-8")
    assert modulepack.stale(m, dmod) == ["packs/a.yaml", "packs/new.yaml"]
    f.unlink()
    assert modulepack.stale(m, dmod) == ["packs/a.yaml", "packs/new.yaml"]


def test_the_digest_holds_no_time_and_the_first_party_list_holds_none(tmp_path):
    m = _module(tmp_path)
    modulepack.pack(m, out=tmp_path / "out")
    text = modulepack.digest(m) + modulepack.first_party(tmp_path / "out").read_text(encoding="utf-8")
    import re
    assert not re.search(r"\b20\d\d-\d\d-\d\d|\b\d\d:\d\d:\d\d", text)


def test_kernel_jar_stamps_the_build_of_the_server_jar_and_a_foreign_jar_is_refused(tmp_path):
    jar = tmp_path / "drishti-server-1.0-exec.jar"
    with zipfile.ZipFile(jar, "w") as z:
        z.writestr("META-INF/build-info.properties", "build.artifact=drishti-server\nbuild.version=1.0\nbuild.time=2026-01-01T00\\:00\\:00Z\n")
    assert modulepack.kernel_build_of_jar(jar) == "1.0+2026-01-01T00:00:00Z"
    other = tmp_path / "other.jar"
    with zipfile.ZipFile(other, "w") as z:
        z.writestr("x", "y")
    with pytest.raises(modulepack.PackError):
        modulepack.kernel_build_of_jar(other)
