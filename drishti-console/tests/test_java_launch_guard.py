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

"""The guard on how the tests and the tools start Java: every JVM gets ``-Djava.io.tmpdir=<a Drishti folder>`` on its command line.

Since JDK 17 the temp folder is read once, at JVM start: without the flag DuckDB (60 MB), RocksDB and CatBoost unpack their native
code into the system /tmp, and every JVM killed without a clean exit leaves its copy there (590 files, 22 GB, once; /tmp has a per-user
quota and Chromium crashed). So a Python file under drishti-console/tests or tools builds a java command only through
``java_cmd`` (tools/javacmd.py; the console tests load it through java_launch.py), and a shell or PowerShell script, or a script
text a tool writes, passes ``-Djava.io.tmpdir=`` on the line that starts java. ``java -version`` runs are exempt: they unpack nothing."""
from __future__ import annotations

import ast
import importlib.util
import re
from pathlib import Path

TESTS = Path(__file__).resolve().parent
ROOT = TESTS.parents[1]
SKIP = {".venv", "venv", "node_modules", "__pycache__", "vendor", "vendor-js", "static", "fixtures"}
SCRIPT_LINE = re.compile(r'^\s*(exec\s+|&\s+|env\s+.*?)?("?\$\{?[A-Za-z_0-9]*(JAVA|java|JDK[0-9]*)[A-Za-z_0-9]*\}?(/bin/java)?"?)\s+.*-(jar|cp)\b')


def _files(suffixes) -> list:
    out = []
    for top in (TESTS, ROOT / "tools"):
        for p in top.rglob("*"):
            if p.suffix in suffixes and p.is_file() and not (SKIP & set(p.relative_to(top).parts)):
                out.append(p)
    return sorted(out)


def _java_ish(node) -> bool:
    """An expression that names the java executable: java, JAVA, java_bin, a.java, find_java(...), str(JAVA), java or "java", "java"."""
    if isinstance(node, ast.Name):
        return "java" in node.id.lower()
    if isinstance(node, ast.Attribute):
        return "java" in node.attr.lower()
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value == "java" or node.value.endswith(("/bin/java", "\\bin\\java.exe", "/bin/java.exe"))
    if isinstance(node, ast.Call):
        name = node.func.id if isinstance(node.func, ast.Name) else node.func.attr if isinstance(node.func, ast.Attribute) else ""
        return name.endswith("find_java") or (name == "str" and bool(node.args) and _java_ish(node.args[0]))
    if isinstance(node, ast.BoolOp):
        return any(_java_ish(v) for v in node.values)
    if isinstance(node, ast.BinOp):
        return _java_ish(node.right) or _java_ish(node.left)
    return False


def _exempt(lst: ast.List) -> bool:
    return any(isinstance(e, ast.Constant) and isinstance(e.value, str) and (e.value == "-version" or e.value.startswith("-XshowSettings"))
               for e in lst.elts)


def violations_in_python(path: Path, text: str) -> list:
    out = []
    tree = ast.parse(text, filename=str(path))
    for n in ast.walk(tree):
        if isinstance(n, ast.List) and len(n.elts) >= 2 and _java_ish(n.elts[0]) and not _exempt(n):
            out.append(f"{path.relative_to(ROOT)}:{n.lineno}: a java command built by hand; use java_cmd(java, [...]) (tools/javacmd.py)")
        if isinstance(n, ast.Constant) and isinstance(n.value, str) and "\n" in n.value:      # a script a tool writes (run-server.sh, .ps1)
            for i, line in enumerate(n.value.splitlines()):
                if SCRIPT_LINE.match(line) and "java.io.tmpdir" not in line:
                    out.append(f"{path.relative_to(ROOT)}:{n.lineno + i}: a written script starts java without -Djava.io.tmpdir=")
    return out


def violations_in_script(path: Path, text: str) -> list:
    out = []
    for i, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith("#"):
            continue
        if (SCRIPT_LINE.match(line) or re.search(r"(/bin/java|java\.exe|-FilePath \$?java)\b.*-(jar|cp)\b", line)) and "java.io.tmpdir" not in line:
            out.append(f"{path.relative_to(ROOT)}:{i}: starts java without -Djava.io.tmpdir=")
    return out


def test_no_test_or_tool_starts_java_without_the_temp_folder_flag():
    bad = []
    for p in _files({".py"}):
        if p.name not in ("javacmd.py", "test_java_launch_guard.py"):         # the helper itself; this file's examples
            bad += violations_in_python(p, p.read_text(encoding="utf-8"))
    for p in _files({".sh", ".ps1"}):
        bad += violations_in_script(p, p.read_text(encoding="utf-8"))
    assert not bad, "start java through tools/javacmd.py (java_cmd) or pass -Djava.io.tmpdir= on the line:\n" + "\n".join(bad)


def test_the_guard_catches_what_it_should():
    here = TESTS / "x.py"
    assert violations_in_python(here, 'subprocess.Popen([java, "-Xmx768m", "-jar", jar])')
    assert violations_in_python(here, 'cmd = [find_java(a.java), "-jar", jar, "sutra"]')
    assert violations_in_python(here, 'subprocess.Popen([str(JAVA), "-jar", jar])')
    assert violations_in_python(here, 'p = [java or "java", "-cp", cp, "Main"]')
    assert violations_in_python(here, 'S = """\\nexec "$JAVA" -jar "$JAR" "$@"\\n"""')
    assert not violations_in_python(here, 'subprocess.Popen(java_cmd(java, ["-Xmx768m", "-jar", jar], tmp=t))')
    assert not violations_in_python(here, 'subprocess.run([java, "-XshowSettings:properties", "-version"])')
    assert not violations_in_python(here, 'args = ["--java", self.java, "--jar", self.jar]')
    assert not violations_in_python(here, 'S = """\\nexec "$JAVA" -Djava.io.tmpdir="$T" -jar "$JAR"\\n"""')
    sh = ROOT / "tools" / "x.sh"
    assert violations_in_script(sh, '"$JAVA_HOME/bin/java" -cp "$CP" com.x.Loader a b')
    assert violations_in_script(sh, 'env "${ENV[@]}" "$JAVA_HOME/bin/java" -Xmx2g -jar "$JAR" &')
    assert violations_in_script(sh, '& $java -jar $jar @args')
    assert not violations_in_script(sh, '"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$JTMP" -cp "$CP" com.x.Loader')
    assert not violations_in_script(sh, '"$JAVA_HOME/bin/java" -version 2>&1 | grep -q 25')


def test_java_cmd_puts_the_flag_first_and_makes_the_folder(tmp_path):
    from java_launch import FLAG, java_cmd, server_tmp
    import java_launch
    java_launch._mod._VERSIONS.update({"java": 21, "/jdk/bin/java": 21})      # Java 21: no compact headers (2.9.7 adds them on 25)
    cache = "-Djdk.nio.maxCachedBufferSize=262144"          # the JDK's per-thread temporary direct buffers cached up to 256 KB only
    cmd = java_cmd("/jdk/bin/java", ["-Xmx1g", "-jar", "s.jar", "--server.port=1"], tmp=tmp_path / "jvm")
    assert cmd == ["/jdk/bin/java", f"{FLAG}{tmp_path / 'jvm'}", cache, "-Xmx1g", "-jar", "s.jar", "--server.port=1"]
    assert (tmp_path / "jvm").is_dir()
    assert java_cmd("java", [f"{FLAG}/elsewhere", "-jar", "s.jar"], tmp=tmp_path / "no") == ["java", cache, f"{FLAG}/elsewhere", "-jar", "s.jar"]
    assert java_cmd("java", ["-Djdk.nio.maxCachedBufferSize=0", "-jar", "s.jar"], tmp=tmp_path / "jvm")[2:] == ["-Djdk.nio.maxCachedBufferSize=0", "-jar", "s.jar"]
    assert not (tmp_path / "no").exists()
    # the default: the server's own temp base for that working folder and environment, as TempFiles.configuredBase computes it
    assert server_tmp(tmp_path, {}) == (tmp_path / "data" / "tmp" / "server").resolve()
    assert server_tmp(tmp_path, {"DRISHTI_DATA_DIR": "d"}) == (tmp_path / "d" / "tmp" / "server").resolve()
    assert server_tmp(tmp_path, {"DRISHTI_TMP_DIR": str(tmp_path / "t"), "DRISHTI_DATA_DIR": "d"}) == (tmp_path / "t").resolve()
    work = tmp_path / "work"
    assert java_cmd("java", ["-jar", "s.jar"], cwd=work, env={})[1] == FLAG + str((work / "data" / "tmp" / "server").resolve())
    assert (work / "data" / "tmp" / "server").is_dir()


def test_the_tools_and_the_console_tests_share_one_helper():
    spec = importlib.util.spec_from_file_location("javacmd_direct", ROOT / "tools" / "javacmd.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    import java_launch
    assert java_launch.FLAG == mod.FLAG == "-Djava.io.tmpdir="
    assert Path(java_launch.java_cmd.__code__.co_filename).resolve() == (ROOT / "tools" / "javacmd.py").resolve()
