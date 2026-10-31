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

"""Documentation rot (DOC-20): what the documents name still exists.

The documents are every Markdown file a reader meets: ``docs/**`` (the help centre renders these as they are), the
top-level ``*.md``, every README (packs, plugins, connector templates, the stack, external_lib), the pack guides and
the console's own help guides. For each one the checks below compare a name the text uses with the thing it names:

* a relative link or image goes to a file, and its ``#anchor`` to a heading of that file (GitHub's slugs, explicit
  ``{#id}`` and ``<a id>`` anchors);
* a repository path written in backticks exists;
* a ``drishti.py`` command and its sub-commands exist in the CLI's argparse tree;
* a ``DRISHTI_*`` environment variable, a ``drishti.*`` server setting and a console ``application.yaml`` key exist;
* a ``DRS-NNNN`` code is one the code has, and every code the code has is in the one error-code table (API_GUIDE's *Error code
  table*, status and meaning; TROUBLESHOOTING keeps cause and fix only where needed) or RACHANA_REFERENCE;
* a command word typed with ``<GO>`` is a pack's mnemonic, a pack code or a reserved word;
* a kind named as a kind (``the `trade` kind``, ``kind `trade```, ``--kind trade``, ``/v/trade/``) is a kind of some pack.

History is exempt from the "still exists" checks, not from the link check: the CHANGELOG, the release notes and the
dated QA logs (``docs/qa``) record what was true then, and every link in them, as in every document, goes to a file that
exists, whatever the line around it says. An obsolete design is deleted, not archived: git keeps its history, and a
document that still mentions it names the release tag where it last lived, as text, not as a link. Run this file
directly to print every finding at once.
"""
from __future__ import annotations

import functools
import importlib.util
import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "drishti-console"))
from core.guides import GithubSlugger  # noqa: E402

# ---- the corpus ----------------------------------------------------------------------------------------------------

# Markdown that is not documentation a reader opens: vendored libraries' notes, test resources, the generator's
# README fragments (they are pasted into the packs' READMEs, which are checked) and the parallel gleif build.
NOT_DOCS = ("drishti-console/web/static/vendor/", "drishti-console/web/elements/package/", "plugins/drishti-plugin-feeds/src/test/",
            "tools/packgen/", "tools/elements-demo/", "data/", "config/packs/gleif/")
# History: names that were true when written. Links in these must still resolve; the names in them need not exist.
HISTORY = ("CHANGELOG.md", "RELEASE_NOTES.md", "docs/qa/")


@functools.lru_cache(maxsize=None)
def tracked() -> tuple[str, ...]:
    """The repository's files (git's list, so other worktrees and build output stay out)."""
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return tuple(line for line in out.splitlines() if line)


@functools.lru_cache(maxsize=None)
def documents() -> tuple[str, ...]:
    return tuple(p for p in tracked() if p.endswith(".md") and not p.startswith(NOT_DOCS) and (ROOT / p).is_file())


# "analysis and product description": a lens document (docs/lenses/) whose built parts are cited as built and the rest are proposals.
_PLANNED = re.compile(r"Status:\W*(design, not built|proposed|target design|design note, proposed|analysis and product description)", re.I)


def history(rel: str) -> bool:
    """History, or a design not built yet (its status line says so): what it names is past or planned, not current."""
    return rel.startswith(HISTORY) or _PLANNED.search("\n".join(read(rel).splitlines()[:40])) is not None


@functools.lru_cache(maxsize=None)
def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8", errors="replace")


_FENCE = re.compile(r"^(\s*)(```|~~~)")


def split_code(text: str) -> tuple[str, str]:
    """(prose, code): the fenced blocks' lines go to code, every other line to prose (blank in the other, so line
    numbers stay)."""
    prose, code, fence = [], [], None
    for line in text.splitlines():
        m = _FENCE.match(line)
        if fence is None and m:
            fence = m.group(2)
            prose.append(""), code.append("")
            continue
        if fence is not None:
            if m and m.group(2) == fence and line.strip().startswith(fence) and line.strip().strip(fence[0]) == "":
                fence = None
                prose.append(""), code.append("")
                continue
            prose.append(""), code.append(line)
        else:
            prose.append(line), code.append("")
    return "\n".join(prose), "\n".join(code)


_SPAN = re.compile(r"(`+)(.+?)\1", re.S)


def spans(prose: str) -> list[tuple[int, str]]:
    """Inline code spans with their line numbers."""
    out = []
    for m in _SPAN.finditer(prose):
        out.append((prose.count("\n", 0, m.start()) + 1, m.group(2).strip()))
    return out


def line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


# ---- anchors --------------------------------------------------------------------------------------------------------

_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
_ATTR_ID = re.compile(r"\s*\{[^}]*#([\w-]+)[^}]*\}\s*$")
_HTML_ID = re.compile(r"<a\s+(?:id|name)=\"([^\"]+)\"", re.I)
_MD_LINK_TEXT = re.compile(r"!?\[([^\]]*)\]\([^)]*\)")


@functools.lru_cache(maxsize=None)
def anchors(rel: str) -> frozenset[str]:
    """Every anchor a document has: GitHub's slug of each heading (repeats numbered), an explicit ``{#id}`` (the help
    centre's attr_list) and ``<a id="...">``."""
    prose, _ = split_code(read(rel))
    slug, found = GithubSlugger(), set()
    for line in prose.splitlines():
        found |= set(_HTML_ID.findall(line))
        m = _HEADING.match(line)
        if not m:
            continue
        title = m.group(2)
        explicit = _ATTR_ID.search(title)
        if explicit:
            found.add(explicit.group(1))
            title = title[:explicit.start()]
        title = _MD_LINK_TEXT.sub(r"\1", title).replace("`", "").replace("*", "")
        found.add(slug(title))
    return frozenset(found)


# ---- 1. links -------------------------------------------------------------------------------------------------------

_LINK = re.compile(r"!?\[(?:[^\[\]]|\[[^\]]*\])*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
_HREF = re.compile(r"<(?:a|img)\b[^>]*\b(?:href|src)=\"([^\"]+)\"", re.I)
_EXTERNAL = re.compile(r"^[a-z][a-z0-9+.-]*:", re.I)        # https:, mailto:, data: ...


def links(rel: str, at_target: bool = False) -> list[tuple[int, str]]:
    """(line, href) of every link; the line of the link's start, or with ``at_target`` the line its target is on (a link's
    text may span lines)."""
    prose, _ = split_code(read(rel))
    prose = _SPAN.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), prose)    # a link written as code is an example, not a link (lines kept)
    g = 1 if at_target else 0
    found = [(line_of(prose, m.start(g)), m.group(1)) for m in _LINK.finditer(prose)]
    found += [(line_of(prose, m.start(g)), m.group(1)) for m in _HREF.finditer(prose)]
    return found


def link_problems() -> list[str]:
    bad = []
    for rel in documents():
        for line, href in links(rel):
            # In-app pages (/help/<slug>, /static/..., /admin ...) are the console's routes, checked by test_help_links;
            # a placeholder (<pack>, {id}) is an example.
            if _EXTERNAL.match(href) or href.startswith("/") or "<" in href or "{" in href:
                continue
            target, _, anchor = href.partition("#")
            if target:
                path = (ROOT / rel).parent / target.split("?")[0]
                if not path.exists():
                    bad.append(f"{rel}:{line}: {href} (no such file)")
                    continue
                path = path.resolve()
                if path.is_dir():
                    path = path / "README.md"
                if not anchor:
                    continue
                try:
                    trel = str(path.relative_to(ROOT.resolve()))
                except ValueError:
                    continue
            else:
                trel = rel
            if anchor and trel.endswith(".md") and (ROOT / trel).is_file() and anchor not in anchors(trel):
                bad.append(f"{rel}:{line}: {href} (no such heading)")
    return bad


# ---- 2. repository paths --------------------------------------------------------------------------------------------

_PATH = re.compile(r"^\.?/?([A-Za-z0-9_.@{},-]+/)+[A-Za-z0-9_.@{},:-]*/?$")
# Paths under the data folder are made at run time; ~ and absolute paths are the reader's machine.
RUNTIME = ("data/", "build/", "target/")


@functools.lru_cache(maxsize=None)
def top_level() -> frozenset[str]:
    return frozenset(p.split("/", 1)[0] for p in tracked() if "/" in p)


@functools.lru_cache(maxsize=None)
def tracked_set() -> frozenset[str]:
    names = set(tracked())
    for p in tracked():
        parts = p.split("/")
        names |= {"/".join(parts[:i]) for i in range(1, len(parts))}
    return frozenset(names)


def _pack_roots(rel: str) -> list[str]:
    """Where a pack-relative path (``sutras/x.sutra.yaml``, ``config/formats.yaml``) may live for this document."""
    m = re.match(r"((?:config|examples)/packs/[^/]+)/", rel)
    return [m.group(1)] if m else []


@functools.lru_cache(maxsize=None)
def pack_roots_all() -> tuple[str, ...]:
    return tuple(sorted({re.match(r"((?:config|examples)/packs/[^/]+)/", p).group(1) for p in tracked()
                         if re.match(r"(?:config|examples)/packs/[^/]+/", p)}))


def ignored(paths: list[str]) -> set[str]:
    """The paths git ignores: files a person or the running system makes (config/connectors/*.yaml, config/net/,
    application.local.yaml, .venv), which a document names on purpose although the repository has none."""
    if not paths:
        return set()
    r = subprocess.run(["git", "check-ignore", "--no-index", "--stdin"], cwd=ROOT, input="\n".join(p for path in paths for p in (path, path + "/")), capture_output=True, text=True)
    return {p.rstrip("/") for p in r.stdout.split()}


# Files a worked example asks the reader to create (and another project's files the text points to), by document.
EXAMPLE_PATHS = {
    "docs/guides/DEVELOPER_GUIDE.md": ("MnemonicController.java", "MnemonicApiTest.java", "/Notice", "/db/NoticeEntity.java", "source_routes.py",
                                       "sources.html", "sources.js", "test_sources.py"),
    "docs/guides/PACK_DEVELOPER_GUIDE.md": ("config/packs/helpdesk", "config/packs/library", "tools/packgen/library", "config/packs/my-bank"),
    "docs/connectors/CONNECTORS.md": ("config/packs/equity-desk",),
    "docs/guides/CLI_GUIDE.md": ("config/packs/curves-demo",),
    "docs/guides/DATA_LOADS.md": ("docs/BATCH_FILE_PROCESSING.md", "docs/HEADLESS_AND_ORCHESTRATION.md", "docs/TUTORIAL_delegate_connectors.md"),
}


def _expand(token: str) -> list[str]:
    """``drishti-server/.../cli/{SutraCli,ExpectFile}.java`` is two paths."""
    m = re.search(r"\{([^{}]+)\}", token)
    return [t for part in m.group(1).split(",") for t in _expand(token[:m.start()] + part + token[m.end():])] if m else [token]


def path_exists(token: str, rel: str) -> bool:
    if any(e in token for e in EXAMPLE_PATHS.get(rel, ())):
        return True
    if "{" in token:
        return all(path_exists(t, rel) for t in _expand(token))
    token = re.sub(r"(:\d+)+$", "", token.split("#")[0]).removeprefix("./").rstrip("/")
    token = re.sub(r"(\.\w+):[a-zA-Z_]\w*$", r"\1", token)                   # Binder.java:bind, a method of the file
    here = str(Path(rel).parent)
    for base in ["", here] + _pack_roots(rel) + list(pack_roots_all()):
        cand = f"{base}/{token}".strip("/") if base and base != "." else token
        if "..." in cand or "*" in cand:                                 # drishti-engine/.../view/PanelData.java: any depth
            pattern = re.escape(cand).replace(r"\.\.\.", ".*").replace(r"\*", "[^/]*")
            if any(re.fullmatch(pattern, p) for p in tracked_set()):
                return True
        elif cand in tracked_set() or (ROOT / cand).exists():
            return True
    return False


# MCP's JSON-RPC method names look like paths.
NOT_PATHS = {"tools/list", "tools/call", "resources/list", "resources/read", "prompts/list", "prompts/get"}


def path_problems() -> list[str]:
    missing = []
    for rel in documents():
        if history(rel):
            continue
        prose, _ = split_code(read(rel))
        for line, token in spans(prose):
            token = token.strip()
            if (not _PATH.match(token) and "..." not in token) or " " in token or token.startswith(("~", "/")) or "//" in token:
                continue
            bare = token.removeprefix("./")
            if bare.split("/", 1)[0] not in top_level() or bare.startswith(RUNTIME) or bare in NOT_PATHS:
                continue
            if not path_exists(token, rel) and bare.rstrip("/") not in code_text():   # or a path the code uses (a default file)
                missing.append((rel, line, token))
    gone = ignored(sorted({t.removeprefix("./").rstrip("/") for _, _, t in missing}))
    return [f"{rel}:{line}: `{t}`" for rel, line, t in missing if t.removeprefix("./").rstrip("/") not in gone]


# ---- 3. drishti.py commands -----------------------------------------------------------------------------------------

@functools.lru_cache(maxsize=None)
def cli_tree() -> dict:
    """The CLI's commands as nested dicts: {group: {command: {...}}} (an empty dict: takes no sub-command)."""
    import argparse

    spec = importlib.util.spec_from_file_location("drishti_cli_for_docs", ROOT / "tools" / "drishti.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod                                   # the CLI hands itself to its plug-in modules by name
    sys.path.insert(0, str(ROOT / "tools"))
    spec.loader.exec_module(mod)

    def walk(parser) -> dict:
        for action in parser._actions:
            if isinstance(action, argparse._SubParsersAction):
                return {name: walk(sub) for name, sub in action.choices.items()}
        return {}

    return walk(mod.build_parser())


_COMMAND = re.compile(r"\bdrishti(?:\.py)?((?:[ \t]+[a-z][a-z0-9-]*)+)")


def command_problems() -> list[str]:
    bad = []
    tree = cli_tree()
    for rel in documents():
        if history(rel):
            continue
        text = read(rel)
        for m in _COMMAND.finditer(text):
            if not m.group(0).startswith("drishti.py") and not re.match(r"drishti[ \t]+(?:" + "|".join(map(re.escape, tree)) + r")\b", m.group(0)):
                continue                                                   # "drishti mode": a word, not the command
            node, said = tree, ["drishti.py"]
            for word in m.group(1).split():
                if not node:
                    break
                if word not in node:
                    bad.append(f"{rel}:{line_of(text, m.start())}: `{' '.join(said + [word])}` (choices: {', '.join(sorted(node))})")
                    break
                said.append(word)
                node = node[word]
    return bad


# ---- 4. settings and environment variables --------------------------------------------------------------------------

_ENV = re.compile(r"\bDRISHTI_[A-Z0-9_]*[A-Z0-9]\b")
_SETTING = re.compile(r"^drishti\.([a-z][a-z0-9-]*(?:\.[a-z0-9-]+|\.\*|\.<[a-z-]+>)+)(?:=.*|:.*)?$")
CODE_SUFFIXES = (".java", ".py", ".js", ".ts", ".yaml", ".yml", ".sh", ".ps1", ".bat", ".cmd", ".properties", ".toml", ".html",
                 ".json", ".xml", ".env", ".service", ".tf", ".txt", ".cfg", ".conf", ".mjs", ".kts")
# Variables a document makes up for the reader: a CI secret, the placeholder form of the console's variables.
ENV_EXAMPLES = {"DRISHTI_PG_PASSWORD", "DRISHTI_CI_TOKEN", "DRISHTI_CONSOLE__SECTION__KEY",
                "DRISHTI_CONSOLE"}   # tutorial 15: a shell variable holding the console's address, beside DRISHTI_SERVER
# Dotted words that look like console keys and are not: a report's YAML path quoted in an error message (REPORTS.md).
NOT_SETTINGS = {"layouts.phone.desk.order"}


@functools.lru_cache(maxsize=None)
def code_text() -> str:
    """Every tracked file of code or configuration, as one text (what "exists in the code" is looked for in)."""
    parts = []
    for p in tracked():
        name = p.rsplit("/", 1)[-1]
        if p.startswith(("drishti-console/web/static/vendor/", "data/")) or "/src/test/" in p or "/tests/" in p or p.startswith("tools/test_"):
            continue
        if p.endswith(CODE_SUFFIXES) or name in ("Dockerfile", "mvnw", "drill.sh") or "." not in name:
            try:
                parts.append((ROOT / p).read_text(encoding="utf-8", errors="ignore"))
            except (OSError, IsADirectoryError):
                pass
    return "\n".join(parts)


def _flat(node, prefix=""):
    if isinstance(node, dict):
        for k, v in node.items():
            yield f"{prefix}{k}"
            yield from _flat(v, f"{prefix}{k}.")


@functools.lru_cache(maxsize=None)
def yaml_keys(*rels: str) -> frozenset[str]:
    keys = set()
    for rel in rels:
        for doc in yaml.safe_load_all(read(rel)):
            keys |= set(_flat(doc or {}))
    return frozenset(keys)


def _camel(s: str) -> str:
    return re.sub(r"-([a-z0-9])", lambda m: m.group(1).upper(), s)


@functools.lru_cache(maxsize=None)
def server_keys() -> frozenset[str]:
    rels = [p for p in tracked() if p.startswith("drishti-server/src/main/resources/application") and p.endswith(".yaml")]
    return yaml_keys(*rels)


@functools.lru_cache(maxsize=None)
def properties_prefixes() -> dict[str, str]:
    """prefix -> the text of every @ConfigurationProperties class bound to it."""
    out: dict[str, str] = {}
    for p in tracked():
        if p.endswith(".java") and "/src/main/" in p:
            text = read(p)
            for m in re.finditer(r"@ConfigurationProperties\(\s*(?:prefix\s*=\s*)?\"([^\"]+)\"", text):
                out[m.group(1)] = out.get(m.group(1), "") + text
    return out


@functools.lru_cache(maxsize=None)
def code_words() -> frozenset[str]:
    """Every identifier and every quoted kebab-case word of the code, lower case and without hyphens: a setting's last
    segment is "read" when a field or a map key of that name exists (``shareEnabled``, ``"share-enabled"``)."""
    code = code_text()
    words = {w.lower() for w in re.findall(r"[A-Za-z][A-Za-z0-9]*", code)}
    words |= {w.replace("-", "").lower() for w in re.findall(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)+", code)}
    return frozenset(words)


@functools.lru_cache(maxsize=None)
def areas() -> frozenset[str]:
    """The setting areas the server has: ``drishti.<area>`` from the application*.yaml files and the
    @ConfigurationProperties prefixes."""
    found = {".".join(k.split(".")[:2]) for k in server_keys() if k.startswith("drishti.")}
    found |= {".".join(p.split(".")[:2]) for p in properties_prefixes() if p.startswith("drishti.")}
    return frozenset(found)


def setting_exists(key: str) -> bool:
    """``drishti.a.b.c``: in an application*.yaml or written in the code (a @Value, a plugin's settings catalogue), or
    its area ``drishti.a`` exists and its last segment is a field or map key the code has (a @ConfigurationProperties
    record's ``maxStreams``, a per-pack map's ``"share-enabled"``). ``*`` and ``<name>`` stand for any segment."""
    while re.search(r"\.(\*|<[a-z-]+>)$", key):
        key = re.sub(r"\.(\*|<[a-z-]+>)$", "", key)
    key = re.sub(r"\.(\*|<[a-z-]+>)\.", ".x.", key)
    if key in server_keys() or key in code_text():
        return True
    parts = key.split(".")
    return ".".join(parts[:2]) in areas() and (len(parts) == 2 or parts[-1].replace("-", "") in code_words())


@functools.lru_cache(maxsize=None)
def env_in_code() -> frozenset[str]:
    return frozenset(_ENV.findall(code_text()))


def env_exists(name: str, doc: str) -> bool:
    """A ``DRISHTI_*`` variable is read when the code names it; when it is Spring's relaxed name of a server setting
    (``DRISHTI_SOURCES_FETCHTIMEOUT`` is ``drishti.sources.fetch-timeout``); when it is the console's
    ``DRISHTI_CONSOLE__SECTION__KEY`` form of a console key; or when the document itself makes it up as a placeholder
    (``${DRISHTI_TRADES_URL}`` in an example connector file)."""
    if name in env_in_code() or name in ENV_EXAMPLES or "${" + name in doc or re.search(rf"(?:^|\s|export\s+){name}=", doc, re.M):
        return True
    if name.startswith("DRISHTI_CONSOLE__"):
        return name[len("DRISHTI_CONSOLE__"):].lower().replace("__", ".") in console_keys()
    tokens = name[len("DRISHTI_"):].lower().split("_")
    relaxed_areas = {a.split(".")[1].replace("-", ""): a for a in areas()}
    for n in (1, 2, 3):                                            # DRISHTI_BUSINESS_DATE_... is drishti.business-date
        if "".join(tokens[:n]) in relaxed_areas:
            return len(tokens) == n or tokens[-1] in code_words()
    return False


@functools.lru_cache(maxsize=None)
def console_keys() -> frozenset[str]:
    return yaml_keys("drishti-console/config/application.yaml")


def setting_problems() -> list[str]:
    bad = []
    code = code_text()
    console_top = {k for k in console_keys() if "." not in k} - {"server", "packs"}   # Spring's server.*; per-pack maps of server areas
    for rel in documents():
        if history(rel):
            continue
        text = read(rel)
        for m in _ENV.finditer(text):
            if not env_exists(m.group(0), text):
                bad.append(f"{rel}:{line_of(text, m.start())}: {m.group(0)} (no code reads it)")
        prose, _ = split_code(text)
        for line, token in spans(prose):
            m = _SETTING.match(token)
            if m and not re.search(r"\.(py|ya?ml|md|jar|json|java|sh)$", token.split("=")[0].split(":")[0]):
                key = "drishti." + m.group(1)
                if not setting_exists(key) and key not in EXAMPLE_NAMES.get(rel, ()):
                    bad.append(f"{rel}:{line}: `{key}` (no such server setting)")
                continue
            m = re.match(r"^([a-z_]+)\.([a-z_][a-z0-9_.-]*[a-z0-9_])(?:[:=].*)?$", token)
            if m and m.group(1) in console_top and not re.search(r"\.(py|ya?ml|md|js|html|json|example)$", token):
                key = f"{m.group(1)}.{m.group(2)}"
                parts = key.split(".")
                if key not in console_keys() and key not in code and key not in NOT_SETTINGS and not (".".join(parts[:2]) in console_keys() and parts[-1].replace("-", "").replace("_", "") in code_words()):
                    bad.append(f"{rel}:{line}: `{key}` (no such console setting)")
    return bad


# ---- 5. error codes -------------------------------------------------------------------------------------------------

_CODE = re.compile(r"\bDRS-\d{4}\b")


@functools.lru_cache(maxsize=None)
def codes_in_code() -> frozenset[str]:
    found = set()
    for p in tracked():
        # tools/packgen/genomics makes up drug ids that look like codes (DRS-4471); generators raise no codes
        if "/src/test/" in p or "/tests/" in p or p.startswith(("tools/test_", "tools/packgen/", "docs/", "drishti-console/web/static/vendor/")) or p.endswith(".md"):
            continue
        if p.endswith((".java", ".py", ".js", ".html", ".yaml")):
            found |= set(_CODE.findall(read(p)))
    return frozenset(found)


def _ranges(text: str) -> set[str]:
    codes = set(_CODE.findall(text))
    for a, b in re.findall(r"DRS-(\d{4})`?\s*(?:[–-]|to|\.\.)\s*`?(?:DRS-)?(\d{4})", text):
        codes |= {f"DRS-{n}" for n in range(int(a), int(b) + 1)}
    return codes


# Codes the documents name as reserved or retired on purpose (the error code table says which).
RESERVED_CODES = {"DRS-3001"}
# What a worked example adds: DEVELOPER_GUIDE section 5 builds an endpoint answering DRS-4005 and a drishti.notices setting.
EXAMPLE_NAMES = {"docs/guides/DEVELOPER_GUIDE.md": {"DRS-4005", "drishti.notices.max-length"}}


def code_problems() -> list[str]:
    bad = []
    known = codes_in_code() | RESERVED_CODES
    for rel in documents():
        if history(rel):
            continue
        text = read(rel)
        for m in _CODE.finditer(text):
            if m.group(0) not in known and m.group(0) not in EXAMPLE_NAMES.get(rel, ()):
                bad.append(f"{rel}:{line_of(text, m.start())}: {m.group(0)} (not in the code)")
    api = read("docs/guides/API_GUIDE.md")
    table = api.split("\n## Error code table", 1)[1].split("\n## ", 1)[0]
    listed = _ranges(table) | _ranges(read("docs/guides/RACHANA_REFERENCE.md"))
    bad += [f"{c} is in the code and neither in API_GUIDE's error code table nor in RACHANA_REFERENCE" for c in sorted(codes_in_code() - listed)]
    return bad


# ---- 6. command words and kinds -------------------------------------------------------------------------------------

RESERVED_WORDS = {"BI", "RUPAKA", "FORM", "FUNC", "VAR", "WHAT", "DATAMAP"}    # routes/terminal_routes.py (VAR: var_routes.py, the explorer): no pack may define these


@functools.lru_cache(maxsize=None)
def packs() -> list[dict]:
    out = []
    for p in tracked():
        if re.match(r"(config/|examples/|docs/guides/examples/)packs?/[^/]+/pack\.yaml$", p) or re.match(r"drishti-[a-z-]+/src/test/resources/.*packs/[^/]+/pack\.yaml$", p):
            try:
                out.append(yaml.safe_load(read(p)) or {})
            except yaml.YAMLError:
                pass
    return out


@functools.lru_cache(maxsize=None)
def command_words() -> frozenset[str]:
    words = set(RESERVED_WORDS)
    for p in packs():
        words |= set((p.get("mnemonics") or {}).keys())
        if p.get("code"):
            words.add(str(p["code"]))
    return frozenset(words)


@functools.lru_cache(maxsize=None)
def kinds() -> frozenset[str]:
    found = set()
    for p in packs():
        for k in p.get("kinds") or []:
            found.add(k if isinstance(k, str) else (k.get("kind") or k.get("name") or ""))
        for m in (p.get("mnemonics") or {}).values():
            if isinstance(m, dict) and m.get("kind"):
                found.add(m["kind"])
    for p in tracked():
        if p.endswith(".sutra.yaml"):
            m = re.search(r"^kind:\s*([\w-]+)", read(p), re.M)
            if m:
                found.add(m.group(1))
    return frozenset(found)


_GO = re.compile(r"(?:^[ \t>*-]*|[`\"“(])([A-Z][A-Z0-9]{1,9})(?:[ \t]+[^\s`<|]+){0,4}[ \t]+<GO>", re.M)
_KIND_SAID = re.compile(r"(?:\bthe `([a-z][a-z0-9-]*)` kind\b|\bdrishti\.py [^\n`]*--kind[ =]([a-z][a-z0-9-]*)\b|\s/v/([a-z][a-z0-9-]*)/)")
_KIND_DEFINED = re.compile(r"^\s*-?\s*kind:\s*([a-z][a-z0-9-]*)\s*$", re.M)
# Words a document uses as made-up examples of what an author would add (a pack being written in a tutorial).
EXAMPLE_WORDS = {"ORD",          # CONNECTORS (Combining connectors): the illustrative equity-desk pack
                 "DEA",          # SCHEMA_TO_PACK: the pack a schema turns into
                 "MYBOOK"}       # USER_GUIDE: a saved command a user names
EXAMPLE_KINDS = {"deals", "desk-quote",                          # SCHEMA_TO_PACK's generated pack; tutorial 4 adds desk-quote in the page
                 "board", "model", "report", "dataset", "python", "notebook"}   # Rupaka item kinds, not entity kinds


def word_problems() -> list[str]:
    bad = []
    for rel in documents():
        if history(rel):
            continue
        text = read(rel)
        defined = set(re.findall(r"^\s{2,}([A-Z][A-Z0-9]{1,9}):\s*$", text, re.M))      # a mnemonic the document's own YAML adds
        prose, _ = split_code(text)
        for m in _GO.finditer(prose):
            w = m.group(1)
            if w not in command_words() and w not in defined and w not in EXAMPLE_WORDS:
                bad.append(f"{rel}:{line_of(prose, m.start(1))}: {w} <GO> (no pack defines {w})")
        own = set(_KIND_DEFINED.findall(text))                                        # kinds the document's own YAML makes
        for m in _KIND_SAID.finditer(text):
            k = next(g for g in m.groups() if g)
            if k not in kinds() and k not in own and k not in EXAMPLE_KINDS:
                bad.append(f"{rel}:{line_of(text, m.start())}: kind {k} (no pack has it)")
    return bad


# ---- the tests ------------------------------------------------------------------------------------------------------

# A line that says a thing is gone names it on purpose ("`bi.poc_enabled` was removed in 2.2", "`MKTW` is now `NWMK`").
_PAST = re.compile(r"\b(removed|gone|no longer|retired|deleted|renamed|replaced|was|were|formerly|older|used to|instead of|deprecated|is now|are now|planned|not built|to build|not yet built)\b", re.I)


def present(found: list[str]) -> list[str]:
    """The findings whose line does not speak of the past."""
    out = []
    for f in found:
        m = re.match(r"([^:]+):(\d+):", f)
        if m and _PAST.search(read(m.group(1)).splitlines()[int(m.group(2)) - 1]):
            continue
        out.append(f)
    return out


def _fail(found: list[str], what: str) -> None:
    found = present(found)
    assert not found, f"{len(found)} {what}:\n" + "\n".join(found)


def test_every_relative_link_and_anchor_resolves():
    # Not filtered by present(): a link to a deleted document is broken even on a line that says the document was deleted.
    found = link_problems()
    assert not found, f"{len(found)} links that go nowhere:\n" + "\n".join(found)


def test_every_repository_path_named_exists():
    _fail(path_problems(), "paths that do not exist")


def test_every_drishti_py_command_named_exists():
    _fail(command_problems(), "CLI commands the CLI does not have")


def test_every_setting_and_environment_variable_named_exists():
    _fail(setting_problems(), "settings nothing reads")


def test_every_error_code_named_exists_and_every_code_is_documented():
    _fail(code_problems(), "error-code mismatches")


def test_every_command_word_and_kind_named_exists():
    _fail(word_problems(), "command words or kinds no pack has")


# ---- obsolete designs ------------------------------------------------------------------------------------------------

def test_obsolete_designs_are_deleted_not_archived():
    """An obsolete design is deleted (git keeps its history; its live parts move into the current document first), so
    there is no folder of documents that read as current and are not."""
    assert not [p for p in tracked() if p.startswith("docs/archive/")], "docs/archive/ is back: delete an obsolete design instead"


def test_the_corpus_is_the_whole_documentation():
    docs = documents()
    assert "README.md" in docs and "drishti-stack/README.md" in docs
    assert any(d.startswith("config/packs/") and d.endswith("/README.md") for d in docs)
    assert any(d.startswith("plugins/") for d in docs) and any(d.startswith("docs/tutorials/") for d in docs)
    assert len(docs) > 200


if __name__ == "__main__":
    for name, fn in (("links", link_problems), ("paths", path_problems), ("commands", command_problems),
                     ("settings", setting_problems), ("codes", code_problems), ("words", word_problems)):
        found = fn() if name == "links" else present(fn())
        print(f"== {name}: {len(found)}")
        for f in found:
            print("  " + f)
