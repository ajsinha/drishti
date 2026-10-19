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

"""How the console's tests start a JVM: tools/javacmd.py's ``java_cmd``, loaded from its file so the tests never put tools/ on sys.path.

``java_cmd(java, [args...], tmp=<a folder under the test's tmp_path>)`` gives ``[java, -Djava.io.tmpdir=<folder>, *args]``: the native
libraries the server unpacks (DuckDB 60 MB, RocksDB, CatBoost) land in the test's own folder, not in the system /tmp."""
from __future__ import annotations

import importlib.util
from pathlib import Path

_FILE = Path(__file__).resolve().parents[2] / "tools" / "javacmd.py"
_spec = importlib.util.spec_from_file_location("drishti_tools_javacmd", _FILE)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

FLAG = _mod.FLAG
java_cmd = _mod.java_cmd
server_tmp = _mod.server_tmp
