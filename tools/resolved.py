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

"""Where everything is, for the Python tools: the server's one resolver's answer, read (docs/architecture/THIN_CONSOLE_DESIGN.md 4.2, step S5).

The Java resolver (``ConfigResolution``, ``PackLoader``, ``ConnectorLayers``) answers through the kernel jar with no server running:
``java -jar <home>/lib/drishti-server-*-exec.jar resolve --json [--root DIR | --site DIR] [--connectors DIR]``. This module runs it, keeps
the document under the data folder (``<data>/tmp/resolved/``, or ``DRISHTI_RESOLVED_CACHE``) by a key of its inputs' modification times, and gives the tools data classes
over it. It holds **no rule**: no layering, no ``replaces``, no on or off, no connector merge; a question the document does not answer is
asked of the Java resolver by a new field, never worked out here. A checkout with no built jar says so in one line with the command that
builds it (``python tools/drishti.py build jar``).

    from resolved import pack_dir, pack_dirs, manifests, repository, current

``repository()`` is the checkout with every module on (the *all* distribution: what a generator means); ``current()`` this process's own
settings (``DRISHTI_*`` variables, the switch files), as a loader or a command line resolves; ``site(folder)`` a folder of packs on its own.
"""
from __future__ import annotations

import contextlib
import hashlib
import json
import os
import re
import subprocess
import threading
import zipfile
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import javacmd

ROOT = Path(__file__).resolve().parent.parent
REPO_PACKS = ROOT / "config" / "packs"
SITE, MODULE, KERNEL = "site", "module", "kernel"         # a pack's layer, as the resolver names it
_LOCK = threading.Lock()
_MEMO: dict[tuple, tuple[tuple, "Resolution"]] = {}


class Unbuilt(RuntimeError):
    """No kernel jar to ask (an unbuilt checkout), or the resolver refused: said in words."""


@dataclass(frozen=True)
class Pack:
    name: str
    folder: Path
    manifest: Path
    layer: str
    module: str | None
    origin: str

    @property
    def dir(self) -> Path:
        return self.folder


@dataclass(frozen=True)
class Module:
    id: str
    home: Path
    folder: Path | None
    packs: tuple
    source: str
    connectors_dir: Path | None
    dataload_file: Path | None
    requires: tuple
    on: bool


@dataclass(frozen=True)
class Template:
    name: str
    owner: str                                   # pack trading, module source-endur
    definition: dict


@dataclass(frozen=True)
class Connector:
    """A connector as the server runs it: its template under the site's file, as the Java resolver merged them."""
    name: str
    plugin: str
    enabled: str
    kinds: tuple
    description: str
    settings: dict                               # dotted key -> text; secrets as their ${...} references
    layer: str                                   # template, override or site
    origins: dict
    superseded: bool
    template: str | None
    file: Path | None
    writes: Any = None


class Packs(Mapping):
    """The packs read, by name (a mapping), and the questions the tools ask of them."""

    def __init__(self, packs: dict[str, Pack]):
        self._p = dict(sorted(packs.items()))

    def __getitem__(self, name):
        return self._p[name]

    def __iter__(self):
        return iter(self._p)

    def __len__(self):
        return len(self._p)

    def find(self, name: str) -> Pack | None:
        return self._p.get(name)

    def dir(self, name: str) -> Path | None:
        p = self._p.get(name)
        return p.folder if p else None

    def names(self) -> list[str]:
        return list(self._p)

    def all(self) -> list[Pack]:
        return list(self._p.values())

    def of_origin(self, layer: str) -> list[Pack]:
        return [p for p in self._p.values() if p.layer == layer]

    def manifests(self) -> list[Path]:
        return [p.manifest for p in self._p.values()]


class Connectors:
    """The effective connectors, as the Java resolver merged them for one connectors folder."""

    def __init__(self, folder: Path | None, templates: dict[str, Template], found: dict[str, Connector], unused: dict[str, str]):
        self.dir = folder
        self.templates = templates
        self._found = found
        self.unused = unused

    def find(self, name: str) -> Connector | None:
        return self._found.get(name)

    def all(self) -> dict[str, Connector]:
        return dict(self._found)

    def serving(self, kind: str) -> list[Connector]:
        return [c for c in self._found.values() if kind in c.kinds]


@dataclass(frozen=True)
class Layout:
    site: Path | None
    kernel: Path | None
    modules: Path | None
    store: Path | None
    installed: Path | None
    extra: tuple = field(default_factory=tuple)


class Resolution:
    """One answer of the Java resolver: ``packs``, ``off``, ``modules``, ``connectors``, ``rules``, ``problems``, ``layout``."""

    def __init__(self, doc: dict):
        self.doc = doc
        self.version = doc.get("version")
        lay = doc.get("layout") or {}
        self.layout = Layout(*(_path(lay.get(k)) for k in ("site", "kernel", "modules", "store", "installed")),
                             tuple(Path(x) for x in lay.get("extra") or []))
        self.packs = Packs({n: Pack(n, Path(p["dir"]), Path(p["manifest"]), p.get("layer") or "", p.get("module"), p.get("origin") or "")
                            for n, p in (doc.get("packs") or {}).items()})
        self.off = dict(doc.get("off") or {})
        self.modules = {i: Module(i, Path(m["home"]), _path(m.get("folder")), tuple(m.get("packs") or ()), m.get("source") or "",
                                  _path(m.get("connectors")), _path(m.get("dataload")), tuple(m.get("requires") or ()), bool(m.get("on")))
                        for i, m in (doc.get("modules") or {}).items()}
        templates = {n: Template(n, t.get("owner") or "", dict(t.get("definition") or {})) for n, t in (doc.get("templates") or {}).items()}
        found = {n: Connector(n, c.get("plugin") or "", str(c.get("enabled") or ""), tuple(c.get("kinds") or ()), c.get("description") or "",
                              dict(c.get("settings") or {}), c.get("layer") or "", dict(c.get("origins") or {}), bool(c.get("superseded")),
                              c.get("template"), _path(c.get("file")), c.get("writes"))
                 for n, c in (doc.get("connectors") or {}).items()}
        self.connectors = Connectors(_path(doc.get("connectorsDir")), templates, found, dict(doc.get("unused") or {}))
        self.rules = list(doc.get("rules") or [])
        self.problems = [str(p) for p in doc.get("problems") or []]

    @property
    def site_dir(self) -> Path | None:
        return self.layout.site

    @property
    def connectors_dir(self) -> Path | None:
        return self.connectors.dir

    def dir(self, name: str) -> Path | None:
        return self.packs.dir(name)

    def modules_on(self) -> list[Module]:
        return [m for m in self.modules.values() if m.on]


def _path(v) -> Path | None:
    return Path(v) if v else None


# ---- asking the jar -------------------------------------------------------------------------------------------------------------
def _stamp(paths: list[Path]) -> tuple:
    """What changes when an input changes: each folder's and file's modification time (a folder's changes when an entry comes or goes)."""
    out = []
    for p in paths:
        try:
            out.append(p.stat().st_mtime_ns)
        except OSError:
            out.append(None)
    return tuple(out)


def _inputs(args: tuple) -> list[Path]:
    """The folders and files the answer depends on: the packs and modules folders (each module's manifest), the switch files, the
    connectors folder and its files, the jar."""
    named = [Path(a) for a in args[1::2] if a and not a.startswith("--")]
    home = javacmd.find_home()[0]
    roots = []
    for r in named + [home]:                                  # a checkout or site named, and this install, each with its layers
        roots += [r, r / "config" / "packs", r / "modules", r / "config" / "modules", r / "config" / "connectors"]
    out: list[Path] = []
    for b in dict.fromkeys(roots):
        out.append(b)
        if b.is_dir():
            out += sorted(b.glob("*"))
            out += sorted(b.glob("*/module.yaml")) + sorted(b.glob("*/*/module.yaml"))
            out += sorted(b.glob("*/packs")) + sorted(b.glob("*/packs/*"))   # a module's pack folder made or removed changes the answer
    if not args:                                              # this process's own resolution: the site's overrides of module files
        for d in _override_dirs(home):
            out.append(d)
            if d.is_dir():
                out += sorted(p for p in d.rglob("*"))
    try:
        out.append(javacmd.server_jar(home=home))
    except javacmd.JarError:
        pass
    return out


def _override_setting() -> str:
    """The site's override folders of module files (``DRISHTI_OVERRIDES_DIR``, else ``drishti.overrides.dir`` of the settings file),
    comma-separated as the server reads them (KERNEL_AND_MODULES.md section 18.10)."""
    return (os.environ.get("DRISHTI_OVERRIDES_DIR") or javacmd.settings()[1].get("drishti.overrides.dir") or "").strip()


def _override_dirs(home: Path) -> list[Path]:
    """The override folders, each absolute or under the install folder: inputs of the answer (a file changed there changes it)."""
    return [(Path(x.strip()) if Path(x.strip()).is_absolute() else home / x.strip()).resolve()
            for x in _override_setting().split(",") if x.strip()]


def _identity(manifest: str, dir_name: str, content_hash: str | None) -> str:
    """One module's identity line as ``ModuleHomes.Home.identity()`` writes it: ``<id> <version> <content hash | development>``."""
    import yaml
    m = yaml.safe_load(manifest) or {}
    m = m if isinstance(m, dict) else {}
    mid = str(m.get("id") or dir_name).strip().lower()
    version = str(m.get("version") if m.get("version") is not None else "0.0.0").strip()
    return f"{mid} {version} {content_hash or 'development'}"


def modules_fingerprint(folder: Path) -> str:
    """``ModuleHomes.fingerprint()`` of what the module boot takes from a modules folder (KERNEL_AND_MODULES.md section 4.2.1), worked out
    without the jar so it can key the cache: its packages (``*.dmod``, each ``<id> <version> <SHA-256 of its META-INF/DMOD.MF>``) when it
    holds any, else its source folders (``<id> <version> development``); the SHA-256 of those lines in id order, each ending in a newline.
    A rebuilt module (new content) or a changed manifest changes it; ``ModuleHomesTest`` holds the same vector as ``test_resolved.py``."""
    folder = Path(folder)
    packages = sorted(folder.glob("*.dmod")) if folder.is_dir() else []
    files = packages or (sorted(p / "module.yaml" for p in folder.iterdir() if (p / "module.yaml").is_file()) if folder.is_dir() else [])
    key = (str(folder), tuple((str(f), *_stat(f)) for f in files))
    with _LOCK:
        hit = _FINGERPRINTS.get(key)
    if hit:
        return hit
    lines: dict[str, str] = {}
    for f in packages:
        try:
            with zipfile.ZipFile(f) as z:
                line = _identity(z.read("module.yaml").decode("utf-8"), f.stem, hashlib.sha256(z.read("META-INF/DMOD.MF")).hexdigest())
        except (OSError, KeyError, zipfile.BadZipFile, ValueError):
            line = f"{f.name} unreadable {f.stat().st_size if f.exists() else 0}"
        lines.setdefault(line.split(" ", 1)[0], line)
    if not packages and folder.is_dir():
        for d in sorted(p for p in folder.iterdir() if (p / "module.yaml").is_file()):
            try:
                line = _identity((d / "module.yaml").read_text(encoding="utf-8"), d.name, None)
            except (OSError, ValueError):
                continue
            lines.setdefault(line.split(" ", 1)[0], line)
    text = "".join(lines[k] + "\n" for k in sorted(lines))
    out = hashlib.sha256(text.encode("utf-8")).hexdigest()
    with _LOCK:
        if len(_FINGERPRINTS) > 64:
            _FINGERPRINTS.clear()
        _FINGERPRINTS[key] = out
    return out


_FINGERPRINTS: dict[tuple, str] = {}


def _stat(f: Path) -> tuple:
    try:
        st = f.stat()
        return st.st_mtime_ns, st.st_size
    except OSError:
        return None, None


def _fingerprints(args: tuple) -> list[str]:
    """The modules' fingerprint of every modules folder the answer reads: a checkout named (source mode) and this install's."""
    named = [Path(a) for a in args[1::2] if a and not a.startswith("--")]
    return [modules_fingerprint(r / "modules") for r in dict.fromkeys(named + [javacmd.find_home()[0]])]


def _cache_dir() -> Path:
    """``DRISHTI_RESOLVED_CACHE`` (a test run's own folder: the test harness sets it, so a run never writes under the data folder),
    else ``<data>/tmp/resolved``."""
    given = (os.environ.get("DRISHTI_RESOLVED_CACHE") or "").strip()
    return Path(given).expanduser().resolve() if given else javacmd.launch_roots()["data"][0] / "tmp" / "resolved"


def _ask(args: tuple) -> dict:
    try:
        jar = javacmd.server_jar()
    except javacmd.JarError as e:
        raise Unbuilt(f"the tools read where everything is from the kernel jar, and there is none: {e}") from e
    stamp = _stamp(_inputs(args))
    env_key = (sorted((k, v) for k, v in os.environ.items() if k.startswith("DRISHTI_")) + [("overrides", _override_setting())]) if not args else []
    key = hashlib.sha256(json.dumps([list(args), list(stamp), env_key, str(jar), _fingerprints(args)], default=str).encode()).hexdigest()[:32]
    cached = _cache_dir() / f"{key}.json"
    try:
        return json.loads(cached.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        pass
    java = javacmd.find_java()
    named = _override_setting() if not args else ""            # the overrides apply to this process's resolution (ResolveCli)
    flags = [f"-Ddrishti.overrides.dir={named}"] if named and not os.environ.get("DRISHTI_OVERRIDES_DIR") else []
    cmd = javacmd.java_cmd(java, [*flags, "-jar", str(jar), "resolve", "--json", *args], cwd=javacmd.find_home()[0])   # the launch roots named
    run = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if run.returncode != 0:
        raise Unbuilt(f"the resolver refused ({run.returncode}): {(run.stderr or run.stdout).strip()[:400]}")
    text = run.stdout[run.stdout.find("\n{") + 1:] if not run.stdout.startswith("{") else run.stdout   # anything printed before the document
    doc = json.loads(text)
    tmp = cached.with_name(f"{cached.name}.{os.getpid()}-{threading.get_ident()}.writing")   # each writer its own: workers share the folder
    try:
        cached.parent.mkdir(parents=True, exist_ok=True)
        tmp.write_text(json.dumps(doc), encoding="utf-8")
        tmp.replace(cached)
    except OSError:
        with contextlib.suppress(OSError):
            tmp.unlink(missing_ok=True)
    return doc


def _resolution(args: tuple) -> Resolution:
    stamp = _stamp(_inputs(args)) + tuple(_fingerprints(args))
    with _LOCK:
        hit = _MEMO.get(args)
        if hit and hit[0] == stamp:
            return hit[1]
    r = Resolution(_ask(args))
    with _LOCK:
        if len(_MEMO) > 32:
            _MEMO.clear()
        _MEMO[args] = (stamp, r)
    return r


def repository(root: Path | str | None = None, connectors: Path | str | None = None) -> Resolution:
    """The checkout with every module on (the *all* distribution): what a generator or a test of the shipped files means."""
    args = ("--root", str(Path(root).resolve() if root else ROOT))
    return _resolution(args + (("--connectors", str(Path(connectors).resolve())) if connectors else ()))


def current(connectors: Path | str | None = None) -> Resolution:
    """This process's own settings (its ``DRISHTI_*`` variables, the switch files): a loader's or a command line's resolution."""
    return _resolution(("--connectors", str(Path(connectors).resolve())) if connectors else ())


def site(folder: Path | str) -> Resolution:
    """A folder of packs read as a site of its own (its packs and group folders, its ``core`` as the kernel's), with no modules."""
    return _resolution(("--site", str(Path(folder).resolve())))


def _for_root(root: Path | str | None) -> Resolution:
    if root is None or Path(root).resolve() == REPO_PACKS.resolve():
        return repository()
    return site(root)


def pack_dirs(root: Path | str | None = None) -> dict[str, Path]:
    """Every pack's folder by name, in name order (a name two places hold is in neither: :func:`duplicates`)."""
    return {n: p.folder for n, p in _for_root(root).packs.items()}


def pack_dir(name: str, root: Path | str | None = None) -> Path:
    """A pack's folder; ``<site>/<name>`` (not there) when no layer has it. ValueError naming both places when two hold it."""
    r = _for_root(root)
    p = r.packs.find(name)
    if p is not None:
        return p.folder
    twice = next((x for x in r.problems if f"pack '{name}' is in two places" in x), None)
    if twice is not None:
        raise ValueError(twice)
    return (r.layout.site or REPO_PACKS) / name


def manifests(root: Path | str | None = None) -> list[Path]:
    """Every pack's ``pack.yaml``, in pack-name order."""
    return _for_root(root).packs.manifests()


def duplicates(root: Path | str | None = None) -> dict[str, str]:
    """The pack names two places hold, each with the problem naming both (such a name is never read)."""
    out = {}
    for x in _for_root(root).problems:
        m = re.match(r"pack '([^']+)' is in two places", x)
        if m:
            out[m.group(1)] = x
    return out


def module_list() -> dict[str, Module]:
    """Every module of the checkout, by id."""
    return repository().modules


# ---- writing a site file over a template (the tools that write overrides) ---------------------------------------------------------
def flatten(settings: Mapping | None, prefix: str = "") -> dict[str, Any]:
    """Nested settings to their dotted keys (``set: {BOOK: B1}`` -> ``set.BOOK``), as a connector file's ``settings:`` is written; a null
    value stays None (it removes a key)."""
    out: dict[str, Any] = {}
    for k, v in (settings or {}).items():
        if isinstance(v, Mapping) and v:
            out.update(flatten(v, f"{prefix}{k}."))
        else:
            out[f"{prefix}{k}"] = v
    return out


def text_of(v: Any) -> str:
    """A setting's value as the resolver's document gives it: true/false, numbers as they are, lists comma-joined, null as 'null'."""
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return "null"
    if isinstance(v, list):
        return ",".join(text_of(x) for x in v)
    return str(v)
