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

"""No path of the product is taken from the process's working folder (docs/admin/CONFIGURATION.md, "Launch roots"; the owner's explicit-paths
rule). Every folder is an absolute setting or sits under a named root: the install folder (``drishti.home``), the configuration folder
(``drishti.config.dir``), the data folder (``drishti.data.dir``), the modules folder (``drishti.modules.home``); in Java through
``com.ash.drishti.api.Roots``, in a YAML default through ``${drishti.config.dir}``, ``${drishti.data.dir}``, ``${drishti.home}``.

The guard reads the main code of every Maven module (``*/src/main/java``) and the shipped YAML (the server's resources, ``config/``,
``modules/``) and fails, naming file:line, on:

- ``Path.of("x")``, ``Paths.get("x")``, ``new File("x")`` with a relative literal first argument;
- ``user.dir`` (the working folder by another name);
- a relative fallback of a path setting: ``getProperty("drishti.x.dir", "modules")``, ``getenv().getOrDefault("DRISHTI_X_DIR", "x")``,
  ``getOrDefault("--out", ".")``;
- ``"."`` or ``".."`` as any argument of ``Path.of``/``Paths.get``/``resolve``/``new File`` (``Path.of(x, "..", "..")`` climbs from wherever x is);
- ``Path.of(System.getProperty(..)).toAbsolutePath()`` (a relative setting would be the working folder's: use ``Roots.anchored``);
- a ``"./..."`` literal in Java code;
- a placeholder default that is relative: ``${x:./...}``, ``${x:../...}``, ``${x:.}``, ``${x:data/...}``, ``${x:config/...}``, ``${x:modules/...}``;
- a shipped YAML, JSON or properties value that is a bare relative folder of the install (``./x``, ``config/...``, ``data/...``, ``modules/...``).

ALLOWED lists the exceptions, each with its reason; a line not listed fails the gate."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ROOT = Path(__file__).resolve().parents[2]

#: file -> (pattern text that may stay there, why). A finding is allowed when its line holds the text.
ALLOWED: dict[str, list[tuple[str, str]]] = {
    # the lake files' default root is matched as this exact text by the Python lake builders (tools/lake/demo.py, stores.py, lakeconfig.py),
    # which substitute their own data folder for it; the server always has drishti.data.dir (LaunchRoots), so the default is never used there
    "config/lakes/": [("${drishti.data.dir:./data}", "the lake builders' text")],
    "config/connectors.examples/": [("${drishti.data.dir:./data}", "a copy-me example; the server resolves drishti.data.dir")],
    "config/lakes.examples/": [("${drishti.data.dir:./data}", "a copy-me example; the server resolves drishti.data.dir")],
    "config/store.examples/": [("${drishti.data.dir:./data}", "a copy-me example; the server resolves drishti.data.dir")],
    # the lake files shipped in the jars: the same text the lake builders substitute (above)
    "lake/drishti-lake-host/src/main/resources/drishti/lakes/": [("${drishti.data.dir:./data}", "the lake builders' text")],
    "modules/banking/java/banking-service/src/main/resources/drishti/lakes/": [("${drishti.data.dir:./data}", "the lake builders' text")],
    # a pack's own files (formats:, semantics:, help:) are relative to the pack folder (PackResolver), not the working folder
    "config/packs/": [(": config/", "relative to the pack folder")],
    # not paths of the server: a check that refuses '../' in an uploaded zip, and an xlsx part's relationship target
    "kernel/drishti-server/src/main/java/com/ash/drishti/server/design/PackFragment.java": [('contains("../")', "a zip-slip check")],
    "kernel/drishti-server/src/main/java/com/ash/drishti/server/dist/XlsxWriter.java": [('Target=\\"../', "an xlsx part's relative target")],
    "testing/drishti-testkit/src/main/java/com/ash/drishti/testkit/s3/MinioServer.java": [('Path.of("")', "the test kit looks for the repository from its Maven module")],
    "config/store/store.yaml": [("${drishti.data.dir:./data}", "the store file is also read by tools/storecli; the server resolves drishti.data.dir")],
    # benchmarks and the test kit run under Maven in their own module folder: their scratch is target/ there by design
    "testing/": [("Path.of(\"target\"", "a benchmark's or the test kit's scratch under its module's target/"),
                 ("Path.of(\"examples/packs", "a benchmark's fixture, run from the repository")],
    # a module's own files named relative to the module folder (module.yaml keys, packs' config) are resolved against that folder, not the
    # working folder: they are not install folders
    "modules/": [(": mappings/", "relative to the module folder"), (": console/", "relative to the module folder"),
                 (": data/", "relative to the module folder"), (": docs/", "relative to the module folder"), (": connectors/", "relative to the module folder"),
                 (": sutras/", "relative to the module folder"), (": packs/", "relative to the module folder"), (": blotters/", "relative to the module folder"),
                 (": ../", "relative to the module's help file"), (": config/", "a pack's own files, relative to the pack folder"),
                 # a connector setting's placeholder, resolved where the connector starts (ConnectorLayers, wip/i4-dailyfix's scope)
                 ("${DRISHTI_CACHE_ROOT:${drishti.data.dir:./data}/cache}", "a connector setting, resolved by the connector layers"),
                 ("${DRISHTI_FILES_ROOT:${drishti.data.dir:./data}/files}", "a copy-me connector example, resolved by the connector layers")],
    # an ES module import in the conformance check of module JavaScript, not a path of the server
    "kernel/drishti-module-runtime/src/main/java/com/ash/drishti/module/conformance/ModuleConformance.java": [("startsWith(\"./\")", "an ES import specifier")],
}

JAVA_LITERAL_PATH = re.compile(r"""\b(?:Path\.of|Paths\.get|new\s+File)\(\s*"(?!/)(?![A-Za-z]:)([^"$]*)\"""")
DOT_LITERAL = re.compile(r'"\.\.?/[^"]*"')
USER_DIR = re.compile(r"""["']user\.dir["']""")
RELATIVE_DEFAULT = re.compile(r"\$\{[A-Za-z0-9_.\-]+:(?:\.{1,2}/|\.\}|(?:data|config|modules|plugins|sutras)/)")
RELATIVE_FALLBACK = re.compile(r"""(?:getProperty|getenv|getOrDefault|requireNonNullElse)\(\s*"(?:--[^"]*|(?:drishti\.|DRISHTI_)[^"]*"""
                               r"""(?:dir|root|path|home|file|folder|lib|DIR|ROOT|PATH|HOME|FILE|FOLDER|LIB)[^"]*)"\s*,\s*"(?!/)(?![A-Za-z]:)"""
                               r"""(?!(?:none|auto|off|on|true|false|\d+)")([^"$]+)"\s*\)""")
CLIMB = re.compile(r"""(?:Path\.of|Paths\.get|\.resolve|new\s+File)\([^;()]*"\.\.?"\s*[,)]""")
ABS_SETTING = re.compile(r"""(?:Path\.of|Paths\.get)\(\s*System\.(?:getProperty|getenv)\([^;]*?\)\s*\)\s*\.toAbsolutePath""")
JAVA_RULES = (JAVA_LITERAL_PATH, DOT_LITERAL, USER_DIR, RELATIVE_DEFAULT, RELATIVE_FALLBACK, CLIMB, ABS_SETTING)
YAML_RELATIVE_VALUE = re.compile(r"^\s*[A-Za-z0-9_.\-]+:\s*(?:\./|\.\./|config/|data/|modules/)[^\s#]*", re.M)


#: folders never read: build output, environments, the drill's and the tests' scratch
SKIP = {".git", "target", "node_modules", ".venv", "data", "__pycache__", ".claude"}


def _walk(base: Path, suffixes: tuple) -> list[str]:
    out = []
    if not base.is_dir():
        return out
    for p in base.iterdir():
        if p.name in SKIP or p.is_symlink():
            continue
        if p.is_dir():
            out += _walk(p, suffixes)
        elif p.suffix in suffixes:
            out.append(p.relative_to(ROOT).as_posix())
    return out


def _files(kind: str) -> list[str]:
    """The files the guard reads, by walking the repository (a static test starts no process): main Java, or the shipped YAML."""
    if kind == "java":
        return sorted(f for f in _walk(ROOT, (".java",)) if "/src/main/java/" in f)
    return sorted(f for f in _walk(ROOT, (".yaml", ".yml", ".json", ".properties"))
                  if "/src/main/resources/" in f or f.startswith(("config/", "modules/")))


def _allowed(path: str, line: str) -> bool:
    for prefix, items in ALLOWED.items():
        if path == prefix or (prefix.endswith("/") and path.startswith(prefix)):
            if any(text in line for text, _ in items):
                return True
    return False


def _code(line: str) -> str:
    """The line without a Java comment (a doc comment's line, or what follows //)."""
    s = line.strip()
    if s.startswith(("*", "/*", "//")):
        return ""
    return re.sub(r"\s//\s.*$", "", line)


def java_findings() -> list[str]:
    out = []
    for f in _files("java"):
        for n, line in enumerate((ROOT / f).read_text(encoding="utf-8").splitlines(), 1):
            code = _code(line)
            if not code:
                continue
            hits = [m.group(0) for r in JAVA_RULES for m in r.finditer(code)]
            if hits and not _allowed(f, line):
                out.append(f"{f}:{n}: {line.strip()[:160]}")
    return out


def yaml_findings() -> list[str]:
    out = []
    for f in _files("yaml"):
        if "/src/test/" in f:
            continue
        for n, line in enumerate((ROOT / f).read_text(encoding="utf-8").splitlines(), 1):
            code = line.split(" #", 1)[0]
            if code.lstrip().startswith("#"):
                continue
            if (RELATIVE_DEFAULT.search(code) or YAML_RELATIVE_VALUE.search(code)) and not _allowed(f, line):
                out.append(f"{f}:{n}: {line.strip()[:160]}")
    return out


def test_no_main_code_takes_a_path_from_the_working_folder():
    found = java_findings()
    assert not found, ("paths relative to the working folder in main code (use com.ash.drishti.api.Roots, or an absolute setting):\n"
                       + "\n".join(found))


def test_no_shipped_yaml_defaults_a_path_to_the_working_folder():
    found = yaml_findings()
    assert not found, ("relative path defaults in shipped YAML (use ${drishti.config.dir}, ${drishti.data.dir}, ${drishti.home}):\n"
                       + "\n".join(found))


@pytest.mark.parametrize("text, hit", [
    ('Path p = Path.of("config", "x");', True),
    ('Path p = Path.of("/abs/x");', False),
    ('String d = "./data";', True),
    ('@Value("${drishti.x:./config/y}") String y', True),
    ('@Value("${drishti.x:${drishti.config.dir}/y}") String y', False),
    ('String w = System.getProperty("user.dir");', True),
    ('Path p = Path.of(Roots.config("bi"));', False),
    ('Path.of(System.getProperty("drishti.modules.home", "modules"), "banking")', True),
    ('env.getProperty("drishti.feeds.root", ".")', True),
    ('System.getenv().getOrDefault("DRISHTI_EXTERNAL_LIB", "external_lib")', True),
    ('env.getProperty("drishti.packs.watch", "auto")', False),
    ('f.getOrDefault("dir", "desc")', False),
    ('Path up = base.resolve("..");', True),
    ('Path up = Path.of(x, "..", "..");', True),
    ('Path p = Path.of(System.getProperty("drishti.x.dir")).toAbsolutePath();', True),
    ('name.startsWith(".") || p.resolve("module.yaml")', False),
])
def test_the_guard_sees_what_it_should(text, hit):
    code = _code(text)
    found = any(r.search(code) for r in JAVA_RULES)
    assert found is hit, text
