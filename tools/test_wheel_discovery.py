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

"""The wheel pack, found without help (docs/plans/FIRST_RUN_ROBUSTNESS.md S4, S5; matrix rows 21 to 31): ``--wheels``, DRISHTI_WHEELS,
~/drishti-wheels (flat, one folder deep, or the zip), the pack zip for this system, arch and Python in Downloads; a pack for another
machine is one [FAIL] naming both sides; a zip is never unzipped (one [FAIL]: unzip it into ~/drishti-wheels); pip only ``--isolated --no-index``.
Scratch homes only; pip is never really run."""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parent
for p in (str(TOOLS), str(TOOLS / "launch")):
    if p not in sys.path:
        sys.path.insert(0, p)
import pylibs  # noqa: E402
from procs import LaunchError  # noqa: E402

ME = ("linux", "x64", "313")
REL = "i7g"


def make_zip(folder: Path, name: str, wheels=("alpha-1.2-py3-none-any.whl",), sums: bool = True, nested: str = "") -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    z = folder / name
    with zipfile.ZipFile(z, "w") as f:
        lines = []
        for w in wheels:
            body = f"wheel {w}".encode()
            f.writestr(nested + w, body)
            lines.append(f"{hashlib.sha256(body).hexdigest()}  {w}")
        if sums:
            f.writestr(nested + "SHA256SUMS", "\n".join(lines) + "\n")
    return z


def env_of(tmp: Path, **extra) -> dict:
    home = tmp / "John Smith"
    home.mkdir(parents=True, exist_ok=True)
    return {"HOME": str(home), "USERPROFILE": str(home), **extra}


def find(tmp: Path, *, wheels=None, me=ME, release=REL, **env):
    return pylibs.find_pack(wheels, env_of(tmp, **env), places=[tmp / "John Smith" / "Downloads"], me=me, release=release)


def name(rel=REL, system="linux", arch="x64", py="313") -> str:
    return pylibs.pack_zip_name(rel, system, arch, py)


# ---- row 21: none anywhere ----------------------------------------------------------------------------------------------------------

def test_row21_no_pack_anywhere_lists_the_places_and_the_exact_zip_name(tmp_path):
    found = find(tmp_path)
    assert found.path is None and not found.problem
    line = found.fail("setup")
    assert line.startswith("[FAIL] libraries: no wheel pack found") and "Fix:" in line
    assert name() in line and str(tmp_path / "John Smith" / "drishti-wheels") in line and "Downloads" in line


# ---- rows 22, 29: the zip left in Downloads; of two packs the one of this release ------------------------------------------------------

def snapshot(root: Path) -> list:
    return sorted((str(p.relative_to(root)), p.stat().st_size if p.is_file() else -1) for p in root.rglob("*"))


def test_row22_the_zip_in_downloads_is_found_never_unzipped_and_is_one_fail_saying_unzip_it(tmp_path, monkeypatch):
    dl = tmp_path / "John Smith" / "Downloads"
    z = make_zip(dl, name())
    env = env_of(tmp_path)
    monkeypatch.setattr(pylibs, "machine", lambda *a, **k: ME)
    before = snapshot(tmp_path)
    found = find(tmp_path)
    assert found.path == z and not found.problem
    with pytest.raises(LaunchError) as e:
        with pylibs.wheel_folder(str(found.path), env):
            pass
    home_wheels = tmp_path / "John Smith" / "drishti-wheels"
    assert str(e.value) == f"[FAIL] wheel pack: {z} is still zipped. Fix: unzip it into {home_wheels}, then run setup again"
    assert snapshot(tmp_path) == before and not home_wheels.exists(), "setup writes nothing outside the checkout: the zip stays a zip"
    assert find(tmp_path).path == z, "the next run finds the same zip: nothing was unzipped"
    monkeypatch.setattr(pylibs.procs, "windows", lambda: True)
    said = pylibs.still_zipped(z, {"HOME": "/ignored", "USERPROFILE": r"C:\Users\John Smith"})
    assert "Fix: unzip it into C:\\Users\\John Smith" in said and said.endswith("drishti-wheels, then run setup again")


def test_row29_of_two_packs_the_one_for_this_release_is_taken_never_the_newest(tmp_path):
    dl = tmp_path / "John Smith" / "Downloads"
    make_zip(dl, name("i7f"))
    mine = make_zip(dl, name("i7g"))
    newer = make_zip(dl, name("i7h"))
    newer.touch()
    assert find(tmp_path).path == mine


# ---- rows 23, 24: unzipped flat, one folder deep, or the zip inside ~/drishti-wheels ---------------------------------------------------

def test_row23_a_pack_unzipped_flat_into_drishti_wheels(tmp_path):
    home = tmp_path / "John Smith" / "drishti-wheels"
    home.mkdir(parents=True)
    (home / "alpha-1.2-py3-none-any.whl").write_bytes(b"w")
    assert find(tmp_path).path == home


def test_row24_a_pack_unzipped_one_folder_deep_or_the_zip_inside_a_folder(tmp_path):
    home = tmp_path / "John Smith" / "drishti-wheels"
    deep = home / name()[:-4]
    deep.mkdir(parents=True)
    (deep / "alpha-1.2-py3-none-any.whl").write_bytes(b"w")
    found = find(tmp_path)
    assert found.path == home and pylibs.wheel_dirs(home) == [deep], "pip is given the folder that holds the wheels"
    other = tmp_path / "other"
    z = make_zip(other / "John Smith" / "drishti-wheels" / "download", name())
    assert find(other).path == z, "the pack zip one folder down in ~/drishti-wheels"
    z2 = make_zip(other / "John Smith" / "drishti-wheels", name())
    z.unlink()
    assert find(other).path == z2, "the pack zip in ~/drishti-wheels itself"


# ---- --wheels and DRISHTI_WHEELS win; a named place that is not there is a FAIL, never a fall-through --------------------------------

def test_the_named_pack_wins_and_a_named_one_that_is_gone_is_one_fail(tmp_path):
    home = tmp_path / "John Smith" / "drishti-wheels"
    home.mkdir(parents=True)
    (home / "alpha-1.2-py3-none-any.whl").write_bytes(b"w")
    given = tmp_path / "given"
    given.mkdir()
    assert find(tmp_path, wheels=str(given)).path == given
    assert find(tmp_path, DRISHTI_WHEELS=str(given)).path == given
    gone = find(tmp_path, DRISHTI_WHEELS=str(tmp_path / "gone"))
    assert gone.path is None and "DRISHTI_WHEELS names" in gone.problem and "not there" in gone.problem
    assert find(tmp_path, wheels=str(given), DRISHTI_WHEELS=str(tmp_path / "gone")).path == given, "--wheels beats the variable"


# ---- rows 25, 26, 27: another system, arch, Python or release: one [FAIL] naming both sides, pip never runs ----------------------------

@pytest.mark.parametrize("zipname, theirs", [
    (name(system="windows"), "windows-x64, Python 3.13"),
    (name(arch="arm64"), "linux-arm64, Python 3.13"),
    (name(py="312"), "linux-x64, Python 3.12"),
], ids=["row25-system", "row25-arch", "row26-python"])
def test_rows25_26_a_pack_for_another_machine_is_one_fail_naming_both(tmp_path, zipname, theirs):
    make_zip(tmp_path / "John Smith" / "Downloads", zipname)
    found = find(tmp_path)
    assert found.path is None
    assert theirs in found.problem and "this machine is linux-x64, Python 3.13" in found.problem
    line = found.fail("setup")
    assert line.count("[FAIL]") == 1 and "Fix: fetch " + name() in line


def test_row27_a_pack_of_another_release_is_refused_naming_both(tmp_path):
    make_zip(tmp_path / "John Smith" / "Downloads", name("i7f"))
    found = find(tmp_path)
    assert found.path is None and "is of release i7f; this checkout is i7g" in found.problem


def test_row25_a_wrong_zip_named_with_wheels_is_refused_and_never_unzipped(tmp_path, monkeypatch):
    z = make_zip(tmp_path / "dl", name(system="windows"))
    monkeypatch.setattr(pylibs, "machine", lambda *a, **k: ME)
    with pytest.raises(LaunchError, match=r"^\[FAIL\] wheel pack: the wheel pack .*windows-x64.* this machine is linux-x64.*Fix: fetch"):
        with pylibs.wheel_folder(str(z), env_of(tmp_path)):
            pass
    assert not (tmp_path / "John Smith" / "drishti-wheels").exists()


def test_the_release_and_machine_of_this_checkout_are_read():
    assert pylibs.checkout_release() == (pylibs.procs.ROOT / ".mvn" / "maven.config").read_text().split("=")[-1].strip().rsplit(".", 1)[-1]
    assert pylibs.machine(windows=True, platform_machine="AMD64", version=(3, 13)) == ("windows", "x64", "313")
    assert pylibs.machine(windows=False, platform_machine="aarch64", version=(3, 12))[1:] == ("arm64", "312")


# ---- row 28: incomplete or damaged -------------------------------------------------------------------------------------------------

def test_row28_a_cut_off_zip_is_never_opened_one_fail_and_nothing_made(tmp_path, monkeypatch):
    z = make_zip(tmp_path / "dl", name())
    z.write_bytes(z.read_bytes()[: len(z.read_bytes()) // 2])
    monkeypatch.setattr(pylibs, "machine", lambda *a, **k: ME)
    with pytest.raises(LaunchError, match=r"^\[FAIL\] wheel pack: .* is still zipped\. Fix: unzip it into "):
        with pylibs.wheel_folder(str(z), env_of(tmp_path)):
            pass
    assert not (tmp_path / "John Smith" / "drishti-wheels").exists()


def test_row28_a_wheel_missing_or_damaged_against_sha256sums_is_named(tmp_path):
    z = make_zip(tmp_path / "dl", name(), wheels=("alpha-1.2-py3-none-any.whl", "beta-2.0-py3-none-any.whl"))
    folder = tmp_path / "w"
    with zipfile.ZipFile(z) as f:                     # the person unzips the pack (setup never does)
        f.extractall(folder)
    (folder / "beta-2.0-py3-none-any.whl").unlink()
    with pytest.raises(LaunchError, match=r"lacks beta-2\.0-py3-none-any\.whl"):
        with pylibs.wheel_folder(str(folder)):
            pass
    (folder / "beta-2.0-py3-none-any.whl").write_bytes(b"not the wheel")
    with pytest.raises(LaunchError, match=r"damaged beta-2\.0-py3-none-any\.whl"):
        with pylibs.wheel_folder(str(folder)):
            pass


# ---- S5 (rows 69, D10): pip from the pack is isolated and offline; the Windows trust store only with an index -----------------------------

def fake_home(h: Path) -> Path:
    (h / "drishti-console").mkdir(parents=True)
    (h / "drishti-console" / "requirements.txt").write_text("alpha>=1\n", encoding="utf-8")
    (h / "lib").mkdir()
    (h / "lib" / "lake-wheel.pin").write_text("version=0.9.1\n", encoding="utf-8")
    return h


def recorder(have: dict | None = None, pip="pip 24.0 from x"):
    calls = []

    def run(cmd, **kw):
        calls.append(cmd)
        if cmd[1] == "-c" and "PYVER" in cmd[2]:
            return subprocess.CompletedProcess(cmd, 0, "PYVER 3.13\n", "")
        if cmd[1] == "-c":
            return subprocess.CompletedProcess(cmd, 0, json.dumps({n: (have or {}).get(n) for n in cmd[3:]}) + "\n", "")
        if cmd[1:4] == ["-m", "pip", "--version"]:
            return subprocess.CompletedProcess(cmd, 0, pip, "")
        return subprocess.CompletedProcess(cmd, 0, "", "")
    run.calls = calls
    return run


# ---- row 30: the libraries are already there: no pack needed, pip never runs --------------------------------------------------------------

# ---- row 31: some libraries in other versions: each one is named ------------------------------------------------------------------------

def test_row31_other_versions_are_named_one_by_one(tmp_path):
    h = fake_home(tmp_path / "h")
    (h / "drishti-console" / "requirements.txt").write_text("alpha>=1\nbeta==2.0\n", encoding="utf-8")
    run = recorder({"alpha": "0.5", "beta": "2.0", "drishti_lake": "0.9.1"})
    bad = pylibs.verify("py", h, run=run)
    assert bad == ["alpha 0.5 is installed, the requirement is alpha>=1  [drishti-console/requirements.txt]"]
