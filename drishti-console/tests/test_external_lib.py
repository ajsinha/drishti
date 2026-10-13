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
"""The external library folder (core/external_lib.py): <base>/python is importable, archives too, the base is configurable."""
import importlib
import sys
import zipfile

from core import external_lib


def test_the_base_comes_from_the_setting_then_the_environment_then_the_repository(tmp_path, monkeypatch):
    monkeypatch.delenv("DRISHTI_EXTERNAL_LIB", raising=False)
    assert external_lib.base().name == "external_lib"
    monkeypatch.setenv("DRISHTI_EXTERNAL_LIB", str(tmp_path / "env"))
    assert external_lib.base() == (tmp_path / "env").resolve()
    assert external_lib.base(str(tmp_path / "set")) == (tmp_path / "set").resolve()


def test_a_module_a_package_and_a_zip_in_python_become_importable(tmp_path, monkeypatch):
    py = tmp_path / "python"
    (py / "xl_pkg").mkdir(parents=True)
    (py / "xl_mod.py").write_text("VALUE = 41\n")
    (py / "xl_pkg" / "__init__.py").write_text("def answer():\n    return 42\n")
    with zipfile.ZipFile(py / "xl_zipped.zip", "w") as z:
        z.writestr("xl_zipped.py", "NAME = 'zipped'\n")
    before = list(sys.path)
    try:
        added = external_lib.activate(str(tmp_path))
        assert added[0] == str(py) and added[1].endswith("xl_zipped.zip")
        assert importlib.import_module("xl_mod").VALUE == 41
        assert importlib.import_module("xl_pkg").answer() == 42
        assert importlib.import_module("xl_zipped").NAME == "zipped"
        assert external_lib.paths() == added
        info = external_lib.info()
        assert info["items"] == ["xl_mod.py", "xl_pkg", "xl_zipped.zip"]
    finally:
        sys.path[:] = before
        for m in ("xl_mod", "xl_pkg", "xl_zipped"):
            sys.modules.pop(m, None)


def test_a_missing_folder_adds_nothing(tmp_path):
    assert external_lib.python_paths(tmp_path / "nowhere") == []
