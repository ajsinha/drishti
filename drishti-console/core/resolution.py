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

"""Configuration resolution, the Python twin (docs/architecture/KERNEL_AND_MODULES.md section 18): where every pack is, which modules
are on, the effective connectors, the bindings and the inbox rules. The console and every tool ask this, never a folder; the Java twin is
``com.ash.drishti.api.resolution`` (the server, the loader and the command line). Both pass the shared fixtures
``kernel/drishti-api/src/test/resources/pack-resolution/cases.yaml`` (packs) and
``kernel/drishti-packs/src/test/resources/connector-layers/cases.yaml`` (connectors); ``tools/test_config_readers_guard.py`` holds every
other Python file to it.

Three layers, read top down for packs and inbox rules, layered bottom up for connectors:

1. **the site**: ``drishti.packs.installed-dir``, then ``drishti.packs.site-dir`` (``config/packs``; ``<dir>/<pack>`` or one group folder
   down), ``config/connectors/<name>.yaml`` (only the keys the site changes), ``config/bindings.yaml``, ``config/dataload.yaml``;
2. **the modules that are on**: each manifest's ``packs: {folder, list}`` (only listed packs count), ``connectors: {folder}`` (each file
   directly in it a template; ``examples/`` are not) and ``dataload:`` rules. A module's folder is ``<modules home>/<dir>`` (its
   ``module.yaml``, ``dmod/module.yaml`` or ``src/dmod/module.yaml``) or its installed ``.dmod`` (``<store>/installed/<id>/<version>``,
   named by ``<store>/installed/active.yaml``, which wins). A module that is off brings nothing;
3. **the kernel**: ``drishti.kernel.packs-dir`` (``config/packs/core``: core, gleif, compute-demo).

Pure: files are only read, never written.

    from core.resolution import Settings, build, repository
    r = build(Settings.from_env(), modules=server_modules)   # the console (the server's module list); repository(): every module on
    r.packs.find("trading"); r.packs.loaded(); r.connectors.find("trade-endur"); r.bindings.read("trade"); r.inbox_rules(); r.problems
"""
from __future__ import annotations

import fnmatch
import hashlib
import os
import re
import threading
from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from functools import cached_property
from pathlib import Path
from typing import Any, Callable, Iterable

import yaml

REPO = Path(__file__).resolve().parents[2]
#: The repository's site packs folder (``config/packs``).
REPO_PACKS = REPO / "config" / "packs"

SITE, MODULE, KERNEL = "SITE", "MODULE", "KERNEL"
LOADED, AVAILABLE, MODULE_OFF, NOT_READ = "LOADED", "AVAILABLE", "MODULE_OFF", "NOT_READ"
#: Every module on (``build(..., modules=ALL)``): the repository's view, what a generator means.
ALL = "all"

_LOADER = getattr(yaml, "CSafeLoader", yaml.SafeLoader)
_TOP = re.compile(r"^(pack|version|replaces):[ \t]*(.*?)[ \t]*(?:[ \t]#.*)?$", re.M)


def _yaml(path: Path) -> Any:
    """A YAML file (None when empty); raises on a missing or broken one."""
    return yaml.load(Path(path).read_text(encoding="utf-8"), Loader=_LOADER)


def _map(path: Path) -> dict:
    v = _yaml(path)
    if v is None:
        return {}
    if not isinstance(v, dict):
        raise ValueError(f"{path} is not a YAML mapping")
    return v


def _norm(p: Path | str) -> Path:
    """Absolute and normalised, symbolic links kept (as the Java twin's ``toAbsolutePath().normalize()``)."""
    return Path(os.path.normpath(os.path.abspath(str(p))))


def _inside(child: Path, parent: Path) -> bool:
    return child == parent or parent in child.parents


def _dirs(root: Path | None) -> list[Path]:
    if root is None or not root.is_dir():
        return []
    try:
        return sorted(_norm(p) for p in root.iterdir() if p.is_dir())
    except OSError:
        return []                                                    # an unreadable folder has nothing to offer


def _children(root: Path | None) -> list[Path]:
    return [d for d in _dirs(root) if (d / "pack.yaml").is_file()]


def _unquote(v: str) -> str:
    v = v.strip()
    return v[1:-1] if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"" else v


def _head(pack_yaml: Path) -> dict[str, str]:
    """The top-level ``pack:``, ``version:`` and ``replaces:`` of a manifest without parsing the whole file (a large pack's YAML takes
    a second to parse); the full parse when the scan does not find ``pack:``. Raises when the file cannot be read."""
    text = pack_yaml.read_text(encoding="utf-8")
    out = {m.group(1): _unquote(m.group(2)) for m in _TOP.finditer(text) if m.group(2).strip()}
    if "pack" not in out:
        m = yaml.load(text, Loader=_LOADER) or {}
        if not isinstance(m, dict):
            raise ValueError(f"{pack_yaml} is not a YAML mapping")
        out = {k: ("null" if m.get(k) is None else str(m.get(k))) for k in ("pack", "version", "replaces") if k in m or k == "pack"}
    return out


@dataclass(frozen=True)
class Problem:
    """What is wrong, in words naming the places; ``code`` is the DRS message number."""
    code: str
    message: str
    places: tuple = ()

    def __str__(self) -> str:
        return self.message


# --------------------------------------------------------------------------------------------------------------------------------- packs

@dataclass(frozen=True)
class Layout:
    """Where the layers are: the site's packs folder, the kernel's, the modules' development tree, the module store and the site's
    installed packs (deployed from Admin or a registry)."""
    site: Path
    kernel: Path
    modules: Path | None = None
    store: Path | None = None
    installed: Path | None = None
    #: a test's or a tool's own folders of packs (``drishti.packs.extra-dirs``, the retired example packs): part of the site, after it
    extras: tuple = ()

    @staticmethod
    def of(site: Path | str, modules: Path | str | None = None, store: Path | str | None = None, installed: Path | str | None = None,
           kernel: Path | str | None = None, extras: Iterable[Path | str] = ()) -> "Layout":
        s = _norm(site)
        return Layout(s, _norm(kernel) if kernel else s / "core", _norm(modules) if modules else None, _norm(store) if store else None,
                      _norm(installed) if installed else None, tuple(_norm(e) for e in extras))


@dataclass(frozen=True)
class Module:
    """A module found on disk: its id, the folder of its ``module.yaml``, its packs folder and listed packs, in words where it is."""
    id: str
    home: Path
    folder: Path | None
    packs: tuple[str, ...]
    source: str
    version: str = ""
    requires: tuple[str, ...] = ()
    title: str = ""
    manifest: dict = field(default_factory=dict, compare=False, repr=False)

    @property
    def connectors_dir(self) -> Path | None:
        """The folder of its connector templates (``connectors: {folder}``), inside the module's folder."""
        c = self.manifest.get("connectors")
        name = str(c.get("folder") or "connectors") if isinstance(c, dict) else (str(c) if isinstance(c, str) else "")
        if not name:
            return None
        d = _norm(self.home / name)
        return d if _inside(d, self.home) else None

    @property
    def dataload_file(self) -> Path | None:
        """Its inbox rules file (``dataload:`` in the manifest), inside the module's folder."""
        v = self.manifest.get("dataload")
        if not isinstance(v, str) or not v.strip():
            return None
        f = _norm(self.home / v.strip())
        return f if _inside(f, self.home) else None


@dataclass(frozen=True)
class Pack:
    """One pack, found: ``origin`` SITE, MODULE or KERNEL; ``state`` LOADED, AVAILABLE, MODULE_OFF or NOT_READ."""
    name: str
    folder: Path | None
    origin: str
    module: str | None = None
    state: str = LOADED
    version: str = ""
    replaces: str = ""
    reason: str = ""

    @property
    def dir(self) -> Path | None:
        return self.folder

    @property
    def layer(self) -> str:
        return self.origin.lower()

    @property
    def label(self) -> str:
        """In words for Admin: ``site``, ``module:trading``, ``kernel`` (the Java twin's ``origin()``)."""
        return f"module:{self.module}" if self.origin == MODULE else self.origin.lower()

    @property
    def manifest(self) -> Path | None:
        return self.folder / "pack.yaml" if self.folder else None


#: The Java twin's name for a found pack.
Located = Pack


class PackSet(Mapping):
    """The packs read, by name in name order (a mapping of name -> :class:`Pack`), with the ones not read beside them."""

    def __init__(self, read: dict[str, Pack], off: dict[str, str], unread: list[Pack], off_packs: dict[str, Pack]):
        self._read = read
        self.off = off                                               # a pack of a module that is off -> that module
        self.unread = unread                                         # the folders not read (a clash, two places, a wrong name)
        self._off = off_packs

    def __getitem__(self, name):
        return self._read[name]

    def __iter__(self):
        return iter(self._read)

    def __len__(self):
        return len(self._read)

    def find(self, name: str) -> Pack | None:
        """The pack read under ``name``; else the entry that says why not (MODULE_OFF, NOT_READ); else None."""
        return self._read.get(name) or self._off.get(name) or next((p for p in self.unread if p.name == name), None)

    def require(self, name: str) -> Pack:
        """The pack read under ``name``, or LookupError in words (DRS-1043 its module is off, DRS-1041 not found)."""
        p = self._read.get(name)
        if p is not None:
            return p
        if name in self.off:
            raise LookupError(f"DRS-1043: pack '{name}' belongs to module {self.off[name]}, which is off. Switch it on in Admin → Modules.")
        raise LookupError(f"DRS-1041: no pack '{name}'")

    def dir(self, name: str) -> Path | None:
        p = self._read.get(name)
        return p.folder if p else None

    def names(self) -> list[str]:
        return list(self._read)

    def all(self) -> list[Pack]:
        return list(self._read.values())

    def loaded(self) -> list[Pack]:
        return [p for p in self._read.values() if p.state == LOADED]

    def of_origin(self, origin: str) -> list[Pack]:
        return [p for p in self._read.values() if p.origin == origin.upper()]

    def manifests(self) -> list[Path]:
        return [p.folder / "pack.yaml" for p in self._read.values()]


def modules(layout: Layout, problems: list | None = None) -> dict[str, Module]:
    """Every module on disk, by id: the development tree's (``<modules>/<dir>/module.yaml``, else ``dmod/module.yaml``, else
    ``src/dmod/module.yaml``), then the module store's active installs, which win an id."""
    problems = [] if problems is None else problems
    out: dict[str, Module] = {}
    if layout.modules is not None and layout.modules.is_dir():
        for d in _dirs(layout.modules):
            home = next((h for h in (d, d / "dmod", d / "src" / "dmod") if (h / "module.yaml").is_file()), None)
            if home is not None:
                _module(home, d.relative_to(layout.modules).as_posix(), problems, out)
    installed = layout.store / "installed" if layout.store is not None else None
    if installed is not None and (installed / "active.yaml").is_file():
        try:
            active = _map(installed / "active.yaml").get("modules")
            for mid, ver in (active.items() if isinstance(active, dict) else []):
                home = _norm(installed / str(mid) / str(ver))
                if _inside(home, installed) and (home / "module.yaml").is_file():
                    _module(home, f"the module store's {mid} {ver}", problems, out)
        except (OSError, ValueError, yaml.YAMLError) as e:
            problems.append(Problem("DRS-1048", f"{installed / 'active.yaml'} cannot be read: {e}", (str(installed / "active.yaml"),)))
    return out


#: :func:`modules` under a name the parameters of :func:`build` do not hide.
find_modules = modules


def _module(home: Path, source: str, problems: list, out: dict) -> None:
    try:
        m = _map(home / "module.yaml")
    except (OSError, ValueError, yaml.YAMLError) as e:
        problems.append(Problem("DRS-1048", f"{home / 'module.yaml'} cannot be read: {e}", (str(home / "module.yaml"),)))
        return
    mid = str(m.get("id") or home.name).strip().lower()
    packs = m.get("packs")
    folder_name, names = "packs", []
    if isinstance(packs, dict):
        folder_name = "packs" if packs.get("folder") is None else str(packs.get("folder"))
        names = [str(x) for x in packs.get("list")] if isinstance(packs.get("list"), list) else []
    elif isinstance(packs, list):
        names = [str(x) for x in packs]
    folder = None
    if names:
        folder = _norm(home / folder_name)
        if not _inside(folder, home):
            problems.append(Problem("DRS-1047", f"the module {mid}: its packs folder {folder_name} is outside the module's folder {home}; "
                                                "none of its packs is read", (str(home),)))
            folder, names = None, []
    req = m.get("requires")
    requires = [str(k) for k in req if k != "optional"] if isinstance(req, dict) else [str(x) for x in req] if isinstance(req, list) else []
    out[mid] = Module(mid, home, folder, tuple(names), source, str(m.get("version") or ""), tuple(requires), str(m.get("title") or ""), m)


def _layer(found: dict[str, list[Path]], where: str, owner: Callable[[Path], str | None], origin: str, into: dict, problems: list,
           unread: list) -> None:
    for name, at in sorted(found.items()):
        if len(at) > 1:
            msg = (f"pack '{name}' is in two places of {where}: {_describe(at[0], owner)} and {_describe(at[1], owner)}; neither is read "
                   "(keep one, rename or remove the other)")
            problems.append(Problem("DRS-1042", msg, tuple(str(p) for p in at)))
            unread += [Pack(name, p, origin, owner(p), NOT_READ, reason=msg) for p in at]
            continue
        d = at[0]
        try:
            head = _head(d / "pack.yaml")
        except (OSError, ValueError, yaml.YAMLError):
            head = {"pack": name}                                    # a broken manifest: the pack loader says what is wrong with it
        declared = head.get("pack", "null")
        if declared != name:
            msg = f"{d / 'pack.yaml'} declares pack '{declared}', expected '{name}'; it is not read"
            problems.append(Problem("DRS-1041", msg, (str(d),)))
            unread.append(Pack(name, d, origin, owner(d), NOT_READ, reason=msg))
            continue
        into[name] = Pack(name, d, origin, owner(d), LOADED, head.get("version", ""), head.get("replaces", "").strip())


def _describe(p: Path, owner: Callable[[Path], str | None]) -> str:
    m = owner(p)
    return f"{p}" + ("" if m is None else f" (module {m})")


def _clash(what: str, mine: Pack, shipped: Pack) -> str:
    whose = "the kernel's pack" if shipped.origin == KERNEL else f"the {shipped.label.replace(':', ' ')}'s pack"
    return (f"{what} ({mine.folder}) has the name of {whose} at {shipped.folder}: add 'replaces: {mine.name}' to its pack.yaml to replace "
            "that one, or rename it; the shipped one is read")


def _site_roots(layout: Layout) -> list[Path]:
    out = [layout.site]
    for d in _dirs(layout.site):
        if not (d / "pack.yaml").is_file() and d != layout.kernel:
            out.append(d)                                            # a group folder of the site
    out += [e for e in layout.extras if e not in out]               # the extra pack folders, after the site (the Java twin's extraDirs)
    return out


def resolve(layout: Layout, active: Callable[[str], bool] | Iterable[str], enabled: Iterable[str] | None = None) -> "Resolution":
    """Every pack (the fixture's entry point; :func:`build` adds the settings): ``active`` says whether a module (by id) is on;
    ``enabled`` narrows which packs load (the rest of the packs read are AVAILABLE; None: every pack read loads)."""
    on = active if callable(active) else (lambda ids: (lambda m: m in ids))(set(active))
    problems: list[Problem] = []
    found_modules = modules(layout, problems)
    unread: list[Pack] = []
    site: dict[str, Pack] = {}
    if layout.installed is not None:                                 # packs an administrator deployed: the site's, looked in first
        inst: dict[str, list[Path]] = {}
        for p in _children(layout.installed):
            inst.setdefault(p.name, []).append(p)
        _layer(inst, "the site's installed packs", lambda _p: None, SITE, site, problems, unread)
    site_found: dict[str, list[Path]] = {}
    for root in _site_roots(layout):
        for p in _children(root):
            site_found.setdefault(p.name, []).append(p)
    rest: dict[str, Pack] = {}
    _layer(site_found, "the site", lambda _p: None, SITE, rest, problems, unread)
    for n, p in rest.items():
        site.setdefault(n, p)
    kernel_found: dict[str, list[Path]] = {}
    for p in _children(layout.kernel):
        kernel_found.setdefault(p.name, []).append(p)
    kernel: dict[str, Pack] = {}
    _layer(kernel_found, "the kernel", lambda _p: None, KERNEL, kernel, problems, unread)
    mod_found: dict[str, list[Path]] = {}
    owner: dict[Path, str] = {}
    off: dict[str, str] = {}
    off_packs: dict[str, Pack] = {}
    for m in found_modules.values():
        if m.folder is None:
            continue
        for name in m.packs:
            d = _norm(m.folder / name)
            if not on(m.id):
                off.setdefault(name, m.id)
                off_packs.setdefault(name, Pack(name, d if (d / "pack.yaml").is_file() else None, MODULE, m.id, MODULE_OFF,
                                                reason=f"its module {m.id} is off"))
                continue
            if not _inside(d, m.folder) or not (d / "pack.yaml").is_file():
                problems.append(Problem("DRS-1041", f"the module {m.id} lists the pack '{name}', which is not at {d / 'pack.yaml'}", (str(d),)))
                continue
            mod_found.setdefault(name, []).append(d)
            owner[d] = m.id
    mods: dict[str, Pack] = {}
    _layer(mod_found, "the modules", owner.get, MODULE, mods, problems, unread)
    # first wins: the site, the modules, the kernel; an earlier layer takes a later one's name only by saying replaces:
    out: dict[str, Pack] = dict(kernel)
    for m in mods.values():
        k = out.get(m.name)
        if k is None or m.replaces == m.name:
            out[m.name] = replace(m, replaces=k.label if k else "")
        else:
            msg = _clash(f"the module {m.module}'s pack '{m.name}'", m, k)
            problems.append(Problem("DRS-1042", msg, (str(m.folder), str(k.folder))))
            unread.append(replace(m, state=NOT_READ, reason=msg))
    for s in site.values():
        shipped = out.get(s.name)
        if shipped is None or s.replaces == s.name:
            out[s.name] = replace(s, replaces=shipped.label if shipped else "")
        else:
            msg = _clash(f"the site's pack '{s.name}'", s, shipped)
            problems.append(Problem("DRS-1042", msg, (str(s.folder), str(shipped.folder))))
            unread.append(replace(s, state=NOT_READ, reason=msg))
    for n in out:
        off.pop(n, None)
        off_packs.pop(n, None)
    want = None if enabled is None else {x.strip() for x in enabled if str(x).strip()}
    if want is not None and want and "default" not in want:
        out = {n: (p if n in want or (p.origin == KERNEL and n == "core") else replace(p, state=AVAILABLE)) for n, p in out.items()}
    read = dict(sorted(out.items()))
    return Resolution(layout, None, found_modules, [m for m in found_modules if on(m)],
                      PackSet(read, dict(sorted(off.items())), unread, off_packs), problems)


# ------------------------------------------------------------------------------------------------------------------------------ settings

def _env(env: Mapping, *names: str) -> str:
    for n in names:
        v = str(env.get(n, "") or "").strip()
        if v:
            return v
    return ""


@dataclass(frozen=True)
class Settings:
    """One setting per concept (KERNEL_AND_MODULES.md 18.2), every path absolute."""
    site_dir: Path
    kernel_dir: Path
    installed_dir: Path | None
    modules_home: Path | None
    modules_path: Path | None
    switches_dir: Path
    connectors_dir: Path
    bindings_file: Path
    rules_file: Path
    distributions_dir: Path
    distribution: str = ""
    enabled: str = ""
    overrides: tuple = ()                                            # (module id, on) from the settings and DRISHTI_MODULES_<ID>_ENABLED
    data_dir: Path | None = None
    extra_dirs: tuple = ()                                           # drishti.packs.extra-dirs (DRISHTI_PACKS_EXTRA_DIRS; console packs.extra_dirs)

    @staticmethod
    def from_env(settings=None, console_dir: Path | str | None = None, env: Mapping | None = None, base: Path | str | None = None) -> "Settings":
        """The settings from the environment and, on the console, its settings (``packs.dir``, ``packs.installed_dir``,
        ``module_manifests_dir``, ``lakes.data_dir``, ``distribution``, ``distributions_dir``, ``packs.enabled``; relative to the
        console's folder). Relative environment paths are read from ``base`` (the checkout)."""
        env = os.environ if env is None else env
        root = _norm(base or REPO)
        cdir = _norm(console_dir) if console_dir else root / "drishti-console"

        def get(key: str) -> str:
            if settings is None:
                return ""
            try:
                return str(settings.get(key, "") or "").strip()
            except Exception:  # noqa: BLE001 - a settings object without the key
                return ""

        def path(value: str, rel: Path) -> Path:
            p = Path(value)
            return _norm(p if p.is_absolute() else rel / p)

        def pick(variables: tuple, key: str | None, default: Path) -> Path:
            v = _env(env, *variables)
            if v:
                return path(v, root)
            c = get(key) if key else ""
            return path(c, cdir) if c else default

        data = pick(("DRISHTI_DATA_DIR",), "lakes.data_dir", root / "data")
        site = pick(("DRISHTI_PACKS_SITE_DIR", "DRISHTI_PACKS_DIR"), "packs.dir", root / "config" / "packs")
        overrides = []
        for k, v in env.items():
            m = re.fullmatch(r"DRISHTI_MODULES_(.+)_ENABLED", k)
            if m and str(v).strip():
                overrides.append((m.group(1).lower().replace("_", "-"), str(v).strip().lower() in ("true", "on", "yes")))
        return Settings(
            site_dir=site,
            kernel_dir=pick(("DRISHTI_KERNEL_PACKS_DIR",), None, root / "config" / "packs" / "core"),
            installed_dir=pick(("DRISHTI_PACKS_INSTALLED_DIR",), "packs.installed_dir", data / "packs" / "installed"),
            modules_home=pick(("DRISHTI_MODULES_HOME", "DRISHTI_MODULE_MANIFESTS_DIR"), "module_manifests_dir", root / "modules"),
            modules_path=pick(("DRISHTI_MODULES_PATH", "DRISHTI_MODULE_DEPLOY_DIR"), None, data / "modules"),
            switches_dir=pick(("DRISHTI_MODULES_DIR",), None, root / "config" / "modules"),
            connectors_dir=pick(("DRISHTI_CONNECTORS_DIR",), None, root / "config" / "connectors"),
            bindings_file=pick(("DRISHTI_BINDINGS_FILE",), None, root / "config" / "bindings.yaml"),
            rules_file=pick(("DRISHTI_DATALOAD_RULES_FILE",), None, root / "config" / "dataload.yaml"),
            distributions_dir=pick(("DRISHTI_DISTRIBUTIONS_DIR",), "distributions_dir", root / "config" / "distributions"),
            distribution=get("distribution") or _env(env, "DRISHTI_DISTRIBUTION"),
            enabled=_env(env, "DRISHTI_PACKS") or get("packs.enabled"),
            overrides=tuple(sorted(overrides)),
            data_dir=data,
            extra_dirs=tuple(path(x.strip(), root) for x in (_env(env, "DRISHTI_PACKS_EXTRA_DIRS") or get("packs.extra_dirs")).split(",")
                             if x.strip()))

    @staticmethod
    def repository(root: Path | str | None = None) -> "Settings":
        """The checkout's own folders, no environment: what a generator reads."""
        return Settings.from_env(env={}, base=root or REPO)

    def layout(self) -> Layout:
        return Layout(self.site_dir, self.kernel_dir, self.modules_home, self.modules_path, self.installed_dir, self.extra_dirs)


# ------------------------------------------------------------------------------------------------------------------- what is on, offline

def _distribution_modules(s: Settings, found: dict[str, Module]) -> set[str] | None:
    """The modules the named distribution carries (with the ones they require), or None for Drishti All (or none named)."""
    name = (s.distribution or "all").strip()
    if "/" in name or "\\" in name or name.endswith((".yaml", ".yml")):
        f = Path(name)
    else:
        short = name[len("drishti."):] if name.startswith("drishti.") else name
        f = s.distributions_dir / f"drishti.{short}.yaml"
    try:
        spec = _map(f)
    except (OSError, ValueError, yaml.YAMLError):
        return None
    if spec.get("modules") == "all" or not isinstance(spec.get("modules"), list):
        return None
    listed = []
    for m in spec["modules"]:
        mid = str(m.get("id") if isinstance(m, dict) else m).strip()
        if mid and (not isinstance(m, dict) or m.get("enabled", True) is not False):
            listed.append(mid)
    out: set[str] = set()
    todo = list(listed)
    while todo:
        mid = todo.pop(0)
        if mid not in out:
            out.add(mid)
            todo += list(found[mid].requires) if mid in found else []
    return out


def active_modules(s: Settings, found: dict[str, Module] | None = None) -> dict[str, str]:
    """The offline decision of what is on (the twin of the access registry, licence not evaluated): every module found, by id ->
    '' when on, else the reason. A setting (``DRISHTI_MODULES_<ID>_ENABLED``) wins; else a module the distribution does not carry is
    off; else its switch file (``config/modules/<id>.yaml`` ``enabled:``); else on. A module whose required module is off is off."""
    found = modules(s.layout()) if found is None else found
    carried = _distribution_modules(s, found)
    overrides = dict(s.overrides)
    switched: dict[str, tuple[bool, str]] = {}
    for mid, m in found.items():
        if mid in overrides:
            switched[mid] = (overrides[mid], f"setting drishti.modules.{mid}.enabled")
            continue
        if carried is not None and mid not in carried:
            switched[mid] = (False, f"not in distribution {s.distribution}")
            continue
        f = s.switches_dir / f"{mid}.yaml"
        try:
            v = _map(f).get("enabled") if f.is_file() else None
        except (OSError, ValueError, yaml.YAMLError):
            v = None
        if v is not None:
            switched[mid] = (str(v).strip().lower() in ("true", "on", "yes"), f"config/modules/{mid}.yaml")
        else:
            on = m.manifest.get("enabled", True)
            switched[mid] = (on is not False and str(on).lower() != "false", "shipped")
    out: dict[str, str] = {}

    def state(mid: str, visiting: set) -> str:
        if mid in out:
            return out[mid]
        on, source = switched[mid]
        reason = "" if on else f"switched off ({source})"
        if on:
            if mid in visiting:
                reason = "its requirements form a cycle"
            else:
                for need in found[mid].requires:
                    if need == "core":
                        continue
                    r = state(need, visiting | {mid}) if need in found else None
                    if r is None or r:
                        reason = f"needs the module '{need}', which is {'not known here' if r is None else 'off'}"
                        break
        out[mid] = reason
        return reason
    for mid in sorted(found):
        state(mid, set())
    return dict(sorted(out.items()))


def build(s: Settings | None = None, modules: Iterable[str] | str | None = None, enabled: Iterable[str] | str | None = None) -> "Resolution":
    """The resolution of ``s`` (default: the environment's). ``modules``: the ids that are on (the server's list on a console), ``ALL``
    for every module found, None for the offline decision (:func:`active_modules`). ``enabled`` narrows which packs load
    (``drishti.packs.enabled``; default the settings')."""
    s = Settings.from_env() if s is None else s
    layout = s.layout()
    reasons: dict[str, str] = {}
    if modules == ALL:
        active: Callable[[str], bool] = lambda _m: True              # noqa: E731
    elif modules is None:
        reasons = active_modules(s, find_modules(layout))
        active = lambda m: reasons.get(m, "unknown") == ""           # noqa: E731
    else:
        ids = {str(m).strip().lower() for m in modules}
        active = lambda m: m in ids                                  # noqa: E731
    en = s.enabled if enabled is None else enabled
    names = [x.strip() for x in en.split(",")] if isinstance(en, str) else list(en)
    r = resolve(layout, active, names if [n for n in names if n] else None)
    r.settings = s
    r.reasons = {m: (reasons.get(m) or ("" if active(m) else "off")) for m in r.modules}
    return r


# ---------------------------------------------------------------------------------------------------------------------- the resolution

class Resolution:
    """One immutable answer: the modules found and on, the packs, the connectors, the bindings, the inbox rules, the problems."""

    def __init__(self, layout: Layout, settings: Settings | None, found: dict[str, Module], on: list[str], packs: PackSet,
                 problems: list[Problem]):
        self.layout = layout
        self.settings = settings
        self.modules = found
        self.on = tuple(on)
        self.packs = packs
        self.problems = problems
        self.reasons: dict[str, str] = {}

    @property
    def off(self) -> dict[str, str]:
        return self.packs.off

    def module_on(self, mid: str) -> bool:
        return mid in self.on

    def modules_on(self) -> list[Module]:
        return [m for i, m in self.modules.items() if i in self.on]

    def dir(self, name: str) -> Path | None:
        return self.packs.dir(name)

    def names(self) -> list[str]:
        return self.packs.names()

    @cached_property
    def connectors(self) -> "Connectors":
        return Connectors(self)

    @cached_property
    def bindings(self) -> "Bindings":
        return Bindings(self)

    def inbox_rules(self) -> list[dict]:
        """The site's rules (``config/dataload.yaml``), then each module's that is on (its manifest's ``dataload:``), in match order;
        each carries ``origin`` (``site``, ``module:<id>``). A file that cannot be read is a problem (DRS-1048), never an empty list."""
        out: list[dict] = []
        files = []
        if self.settings is not None:
            files.append(("site", self.settings.rules_file))
        files += [(f"module:{m.id}", m.dataload_file) for m in self.modules_on() if m.dataload_file is not None]
        for origin, f in files:
            if not f.is_file():
                continue
            try:
                d = _map(f)
            except (OSError, ValueError, yaml.YAMLError) as e:
                p = Problem("DRS-1048", f"The inbox rules of {origin.replace('module:', 'module ')} ({f}) cannot be read: {e}. Its files will "
                                        "not be matched.", (str(f),))
                if p not in self.problems:
                    self.problems.append(p)
                continue
            rules = (d.get("dataload") or {}).get("rules") if isinstance(d.get("dataload"), dict) else d.get("rules")
            out += [{**r, "origin": origin} for r in rules or [] if isinstance(r, dict)]
        return out

    def rule_for(self, filename: str) -> dict | None:
        """The first inbox rule whose ``match`` glob matches the file's name."""
        return next((r for r in self.inbox_rules() if fnmatch.fnmatch(filename, str(r.get("match") or ""))), None)

    def all_problems(self) -> list[Problem]:
        return [*self.problems, *self.connectors.problems, *self.bindings.problems]

    def why(self, what: str, name: str) -> list[str]:
        """Every place looked in for ``name`` (a pack, a connector or a kind), in order, and what was found there."""
        out: list[str] = []
        if what == "pack":
            lay = self.layout
            p = self.packs.find(name)
            for label, root in (("the site's installed packs", lay.installed), ("the site", lay.site)):
                if root is not None:
                    hits = [str(d) for r in ([root] if label != "the site" else _site_roots(lay)) for d in _children(r) if d.name == name]
                    out.append(f"{label} ({root}): {', '.join(hits) or 'none'}")
            for m in self.modules.values():
                if name in m.packs:
                    out.append(f"module {m.id} ({m.source}): lists it" + ("" if m.id in self.on else f"; the module is off ({self.reasons.get(m.id) or 'off'})"))
            out.append(f"the kernel ({lay.kernel}): {lay.kernel / name if (lay.kernel / name / 'pack.yaml').is_file() else 'none'}")
            out.append(f"read: {p.label} {p.folder} ({p.state}{', ' + p.reason if p.reason else ''})" if p else "read: nothing")
        elif what == "connector":
            c = self.connectors
            t = c.templates.get(name)
            out.append(f"template: {t.owner if t else 'none'}")
            f = c.file(name)
            out.append(f"site file: {f if f and f.is_file() else 'none'}")
            e = c.find(name)
            out.append(f"in force: layer {e.layer}, plugin {e.plugin}" if e else "in force: nothing")
            out += [f"not used: {u['reason']}" for u in c.unused() if u["name"] == name]
        else:
            b = self.bindings.read(name)
            out.append(f"{b['origin']}: {', '.join(b['connectors']) or 'UNBOUND'} ({b['reason']})")
        return out


# ------------------------------------------------------------------------------------------------------------------------- connectors

TEMPLATE, OVERRIDE, SITE_FILE = "template", "override", "site"
_GENERATED = re.compile(r"^# Generated at first start from the connector template of pack '([^']+)'")
_STAMP = re.compile(r"^# drishti-template: pack=(\S*) template=(\S*) text=(\S*)\s*$")
_STAMP_PREFIX = "# drishti-template: "
#: The store variants of an older source module's connectors folder: examples to copy, never templates.
VARIANTS = frozenset({"iceberg", "files", "file", "kafka", "cassandra", "s3tables", "delta-s3"})
_TOP_KEYS = ("enabled", "kinds", "description", "requires", "writes")


class Unusable(ValueError):
    """A site file that cannot be used: ``code`` DRS-1046 (its template is not here) or '' (not valid)."""

    def __init__(self, message: str, code: str = ""):
        super().__init__(message)
        self.code = code


def _text(v: Any) -> str:
    """A setting's value as the Java twin writes it: true/false, numbers as they are, lists comma-joined, null as 'null'."""
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return "null"
    if isinstance(v, list):
        return ",".join(_text(x) for x in v)
    return str(v)


def flatten(settings: Mapping | None, prefix: str = "") -> dict[str, Any]:
    """Nested settings to their dotted keys (``set: {BOOK: B1}`` -> ``set.BOOK``); a null value stays None (it removes a key)."""
    out: dict[str, Any] = {}
    for k, v in (settings or {}).items():
        if isinstance(v, Mapping) and v:
            out.update(flatten(v, f"{prefix}{k}."))
        else:
            out[f"{prefix}{k}"] = v
    return out


def etag(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def generated(text: str | None) -> bool:
    """Whether an earlier release wrote the file from a pack's template (its first line says so)."""
    return bool(text) and bool(_GENERATED.search(text))


def _unedited(text: str, marker: Path | None = None) -> bool:
    normal = text.replace("\r\n", "\n")
    at = 0
    while at < len(normal) and normal[at] == "#":
        nl = normal.find("\n", at)
        end = len(normal) if nl < 0 else nl + 1
        line = normal[at:end].strip()
        at = end
        if line.startswith(_STAMP_PREFIX):
            m = _STAMP.match(line)
            return bool(m) and m.group(3) == etag(normal[at:])
    if marker is not None and marker.is_file():
        for ln in marker.read_text(encoding="utf-8").splitlines():
            if ln.startswith("file="):
                return ln[len("file="):].strip() == etag(text)
    return False


def _enabled(v: Any) -> str:
    """``enabled:`` as text: true when not set; a quoted "true" or "false" is the same switch; a ``${VAR:true}`` placeholder as it is."""
    if v is None:
        return "true"
    t = _text(v).strip()
    return t.lower() if t.lower() in ("true", "false") else t


def _kinds(v: Any) -> list[str]:
    return [str(k) for k in v] if isinstance(v, (list, dict)) else []


@dataclass
class Effective:
    """The connector as the server runs it: ``layer`` template, override or site; ``origins`` each setting's layer; ``base_dirs`` the
    folder a relative file in each setting is read from; ``superseded`` when a generated file nobody edited stands for it."""
    name: str
    plugin: str
    enabled: str
    kinds: list
    description: str
    settings: dict
    layer: str
    origins: dict
    superseded: bool = False
    template_from: str = ""
    file: Path | None = None
    writes: Any = None
    requires: Any = None
    base_dirs: dict = field(default_factory=dict)

    def as_dict(self) -> dict:
        return {"name": self.name, "plugin": self.plugin, "enabled": self.enabled, "kinds": list(self.kinds), "description": self.description,
                "settings": dict(self.settings), "layer": self.layer, "origins": dict(self.origins), "superseded": self.superseded,
                "templateFrom": self.template_from, "file": str(self.file or ""), "writes": self.writes}


@dataclass(frozen=True)
class Template:
    name: str
    owner: str                                                       # pack trading, module source-endur
    definition: dict
    base: Path | None = None


def _whole(name: str, raw: dict, layer: str, owner: str = "", base: Path | None = None, file: Path | None = None) -> Effective:
    plugin = raw.get("plugin")
    if plugin is None or not str(plugin).strip():
        raise Unusable(f"{name}.yaml: 'plugin' is required (jdbc, delta, kafka, ...)")
    st = raw.get("settings")
    if st is not None and not isinstance(st, Mapping):
        raise Unusable(f"{name}.yaml: 'settings' is a mapping")
    settings = {k: _text(v) for k, v in flatten(st).items()}
    return Effective(name, str(plugin).strip(), _enabled(raw.get("enabled")), _kinds(raw.get("kinds")), str(raw.get("description") or ""),
                     settings, layer, {k: layer for k in settings}, False, owner, file, raw.get("writes"), raw.get("requires"),
                     {k: base for k in settings} if base else {})


def _legacy(keys: Iterable[str]) -> bool:
    ks = list(keys)
    return "lake" not in ks and any(k == "root" or k.startswith("table.") or (k.startswith("layout.") and not k.endswith(".type-ahead")) for k in ks)


def effective(name: str, text: str | None, template: Template | dict | None, file: Path | None = None, marker: Path | None = None) -> Effective:
    """The connector a site file (``text``; None: no file) and its template make (KERNEL_AND_MODULES.md 18.5). Raises
    :class:`Unusable` when the file cannot be used."""
    t = Template(name, "", template) if isinstance(template, dict) else template
    site_base = file.parent if file is not None else None
    if text is None:
        if t is None:
            raise Unusable(f"no connector '{name}'")
        return _whole(name, t.definition, TEMPLATE, t.owner, t.base)
    try:
        raw = yaml.load(text, Loader=_LOADER) if text.strip() else {}
    except yaml.YAMLError as e:
        raise Unusable(f"{name}.yaml is not valid YAML: {str(e).splitlines()[0] if str(e) else e}") from e
    raw = raw or {}
    if not isinstance(raw, dict):
        raise Unusable(f"{name}.yaml is not a YAML mapping")
    if t is None:
        if generated(text) or raw.get("template"):
            what = f"the template of {raw.get('template')}" if raw.get("template") else "a template an earlier release generated it from"
            raise Unusable(f"{file or name + '.yaml'} overrides {what}, which is not here (its module is off or removed). It is not used.",
                           "DRS-1046")
        return _whole(name, raw, SITE_FILE, "", site_base, file)
    tdef = _whole(name, t.definition, TEMPLATE, t.owner, t.base)
    if generated(text):
        if _unedited(text, marker):
            return replace(tdef, superseded=True, file=file)
        return _whole(name, raw, SITE_FILE, t.owner, site_base, file)   # an edited generated file: the whole connector, as it was
    st = raw.get("settings")
    if st is not None and not isinstance(st, Mapping):
        raise Unusable(f"{name}.yaml: 'settings' is a mapping")
    own = flatten(st)
    settings = dict(tdef.settings)
    origins = {k: TEMPLATE for k in settings}
    bases = dict(tdef.base_dirs)
    # one lake form: a file in the older form (root, domain, table.*, layout.*) does not get the template's lake keys, and a file naming
    # a lake does not keep the template's older keys
    given = [k for k, v in own.items() if v is not None]
    # a file that gives only root over a template naming a lake moves that lake's root (lake demo --lake): it keeps the lake, its root
    # wins when the lake is expanded (ConnectorLake.expand)
    moves_root = "lake" in settings and "root" in given and not any(k == "domain" or k.startswith("table.") or (k.startswith("layout.") and not k.endswith(".type-ahead")) for k in given)
    if _legacy(given) and not moves_root:
        for k in ("lake", "lake-tables"):
            settings.pop(k, None)
            origins.pop(k, None)
    elif own.get("lake") is not None:
        for k in [k for k in settings if k == "root" or k.startswith("table.") or k.startswith("layout.")]:
            settings.pop(k)
            origins.pop(k, None)
    for k, v in own.items():
        if v is None:                                                # a key set to null removes the inherited one (and what is under it)
            for x in [x for x in settings if x == k or x.startswith(k + ".")]:
                settings.pop(x)
                origins.pop(x, None)
                bases.pop(x, None)
            continue
        settings[k] = _text(v)
        origins[k] = OVERRIDE
        if site_base:
            bases[k] = site_base
    plugin = raw.get("plugin")
    out = Effective(name, str(plugin).strip() if plugin is not None and str(plugin).strip() else tdef.plugin,
                    _enabled(raw["enabled"]) if "enabled" in raw and raw["enabled"] is not None else tdef.enabled,
                    _kinds(raw["kinds"]) if raw.get("kinds") is not None else tdef.kinds,
                    str(raw["description"]) if raw.get("description") is not None else tdef.description,
                    settings, OVERRIDE, origins, False, t.owner, file,
                    raw["writes"] if raw.get("writes") is not None else tdef.writes,
                    raw["requires"] if raw.get("requires") is not None else tdef.requires, bases)
    return out


class Connectors:
    """The effective connectors of a resolution: the templates of the packs read and the modules that are on, each site file merged
    over its template, computed in memory."""

    def __init__(self, r: Resolution):
        self.r = r
        self.problems: list[Problem] = []
        self.dir = r.settings.connectors_dir if r.settings is not None else None
        self.state = (r.settings.data_dir / "connectors") if r.settings is not None and r.settings.data_dir is not None else None
        self.templates = self._templates()
        self._cache: dict[str, Effective | None] = {}
        self._unused: dict[str, dict] = {}

    def _templates(self) -> dict[str, Template]:
        found: dict[str, list[Template]] = {}
        for p in self.r.packs.all():
            try:
                text = p.manifest.read_text(encoding="utf-8")
                if not re.search(r"^connectors:", text, re.M):
                    continue
                conns = (yaml.load(text, Loader=_LOADER) or {}).get("connectors")
            except (OSError, yaml.YAMLError):
                continue
            for n, d in (conns.items() if isinstance(conns, dict) else []):
                if isinstance(d, dict):
                    found.setdefault(str(n), []).append(Template(str(n), f"pack {p.name}", d, p.folder))
        for m in self.r.modules_on():
            folder = m.connectors_dir
            if folder is None or not folder.is_dir():
                continue
            for f in sorted(folder.glob("*.yaml")):                  # examples/ (a folder) holds examples, never templates
                try:
                    d = _map(f)
                except (OSError, ValueError, yaml.YAMLError) as e:
                    self.problems.append(Problem("DRS-1048", f"the connector template {f} of module {m.id} cannot be read: {e}", (str(f),)))
                    continue
                kinds = _kinds(d.get("kinds"))
                n = str(d.get("name") or "").strip()
                if not n:
                    if f.stem in VARIANTS:
                        continue
                    n = f"{kinds[0]}-{m.id[len('source-'):]}" if f.stem == "delta" and m.id.startswith("source-") and kinds else f.stem
                found.setdefault(n, []).append(Template(n, f"module {m.id}", {k: v for k, v in d.items() if k != "name"}, m.home))
        out: dict[str, Template] = {}
        for n, ts in found.items():
            distinct = [t for i, t in enumerate(ts) if all(t.definition != u.definition for u in ts[:i])]
            if len(distinct) > 1:
                self.problems.append(Problem("DRS-1045", f"Connector '{n}' is declared by {distinct[0].owner} and by {distinct[1].owner}. "
                                                         "Neither is used. Remove one declaration.", tuple(t.owner for t in distinct)))
                continue
            out[n] = ts[0]
        return dict(sorted(out.items()))

    def file(self, name: str) -> Path | None:
        return self.dir / f"{name}.yaml" if self.dir is not None else None

    def files(self) -> list[str]:
        """The names of the site's connector files."""
        if self.dir is None or not self.dir.is_dir():
            return []
        return sorted(f.stem for f in self.dir.glob("*.yaml") if f.is_file())

    def _marker(self, name: str) -> Path | None:
        for d in ([self.state / "generated"] if self.state else []) + ([self.dir / "generated", self.dir / ".generated"] if self.dir else []):
            if (d / f"{name}.properties").is_file():
                return d / f"{name}.properties"
        return None

    def find(self, name: str) -> Effective | None:
        """The connector in force under ``name`` (a template, an override, a site connector), or None (none, or its file not used)."""
        if name in self._cache:
            return self._cache[name]
        f = self.file(name)
        text = f.read_text(encoding="utf-8") if f is not None and f.is_file() else None
        e = None
        try:
            if text is not None or name in self.templates:
                e = effective(name, text, self.templates.get(name), f, self._marker(name))
        except Unusable as x:
            self._unused[name] = {"name": name, "file": str(f), "reason": str(x), "code": x.code or "DRS-1046"}
            self.problems.append(Problem(x.code or "DRS-1046", str(x), (str(f),)))
        self._cache[name] = e
        return e

    def all(self) -> dict[str, Effective]:
        """Every connector in force, by name."""
        out = {}
        for n in sorted(set(self.templates) | set(self.files())):
            e = self.find(n)
            if e is not None:
                out[n] = e
        return out

    def unused(self) -> list[dict]:
        """The site files that are not read, each with its reason: superseded (safe to delete) or not usable."""
        self.all()
        out = [dict(u) for u in self._unused.values()]
        out += [{"name": n, "file": str(self.file(n)), "reason": "generated by an earlier release and never edited: its template is in force; "
                                                          "safe to delete", "code": ""} for n in self.superseded]
        return sorted(out, key=lambda u: u["name"])

    @property
    def superseded(self) -> list[str]:
        return [n for n, e in self.all().items() if e.superseded]

    def serving(self, kind: str) -> list[Effective]:
        """The connectors in force whose definition names ``kind`` (a template's ``kinds:``), by name."""
        return [e for e in self.all().values() if kind in e.kinds]


# --------------------------------------------------------------------------------------------------------------------------- bindings

#: The plugins that push (a kind's live connectors, never its read ones).
STREAMS = frozenset({"kafka", "nats", "pulsar", "websocket", "mqtt"})

class Bindings:
    """How a kind finds its connectors (KERNEL_AND_MODULES.md 18.6): the site's binding, else what the templates and the packs' routes
    declare, else demo samples only when the site binds ``demo: true``, else UNBOUND."""

    def __init__(self, r: Resolution):
        self.r = r
        self.problems: list[Problem] = []
        self.site: dict = {}
        f = r.settings.bindings_file if r.settings is not None else None
        if f is not None and f.is_file():
            try:
                b = _map(f).get("bindings")
                self.site = b if isinstance(b, dict) else {}
            except (OSError, ValueError, yaml.YAMLError) as e:
                self.problems.append(Problem("DRS-1048", f"The bindings ({f}) cannot be read: {e}", (str(f),)))

    @cached_property
    def routes(self) -> dict[str, list[str]]:
        """Each kind's routed connectors, from the packs read (``routes: {kind: connector}``)."""
        out: dict[str, list[str]] = {}
        for p in self.r.packs.all():
            try:
                text = p.manifest.read_text(encoding="utf-8")
                if not re.search(r"^routes:", text, re.M):
                    continue
                routes = (yaml.load(text, Loader=_LOADER) or {}).get("routes")
            except (OSError, yaml.YAMLError):
                continue
            for k, c in (routes.items() if isinstance(routes, dict) else []):
                if isinstance(c, str) and c not in out.setdefault(str(k), []):
                    out[str(k)].append(c)
        return out

    def read(self, kind: str) -> dict:
        """``{kind, connectors, union, origin, state, reason, set_aside}``: origin site, declared, demo or none (state UNBOUND)."""
        conns = self.r.connectors.all()
        b = self.site.get(kind) if isinstance(self.site.get(kind), dict) else {}
        listed = b.get("read") if isinstance(b.get("read"), list) else ([b["read"]] if isinstance(b.get("read"), str) else [])
        listed = [str(x) for x in listed]
        known = [n for n in listed if n in conns]
        aside = [n for n in listed if n not in conns]
        if known:
            return {"kind": kind, "connectors": known, "union": bool(b.get("union", len(known) > 1)), "origin": "site", "state": "BOUND",
                    "reason": "config/bindings.yaml", "set_aside": aside}
        routed = [c for c in self.routes.get(kind, []) if c in conns]
        named = [n for n, e in conns.items() if kind in e.kinds and n not in routed and e.plugin not in STREAMS]
        declared = routed + named
        if declared:
            union = any(conns[n].settings.get("union") == "true" for n in declared) or len(declared) > 1
            return {"kind": kind, "connectors": declared, "union": union, "origin": "declared", "state": "BOUND",
                    "reason": "declared by " + ", ".join(dict.fromkeys(conns[n].template_from or "the site" for n in declared)), "set_aside": aside}
        if b.get("demo") is True:
            return {"kind": kind, "connectors": ["demo"], "union": False, "origin": "demo", "state": "DEMO", "reason": "the site binds demo: true",
                    "set_aside": aside}
        return {"kind": kind, "connectors": [], "union": False, "origin": "none", "state": "UNBOUND", "reason": "nothing serves it",
                "set_aside": aside}

    def live(self, kind: str) -> list[str]:
        b = self.site.get(kind) if isinstance(self.site.get(kind), dict) else {}
        v = b.get("live")
        if v:
            return [str(x) for x in (v if isinstance(v, list) else [v])]
        return [n for n, e in self.r.connectors.all().items() if kind in e.kinds and e.plugin in STREAMS]

    def write(self, kind: str, system: str | None = None) -> str:
        """The connector a correction of ``kind`` (from ``system``) is written to; LookupError DRS-9308 (none) or DRS-9309 (several)."""
        b = self.site.get(kind) if isinstance(self.site.get(kind), dict) else {}
        w = b.get("write") if isinstance(b.get("write"), dict) else {}
        if w.get("connector"):
            return str(w["connector"])
        by = w.get("connectors") if isinstance(w.get("connectors"), dict) else {}
        if system and by.get(system):
            return str(by[system])
        cands = [e for e in self.r.connectors.all().values() if isinstance(e.writes, dict)
                 and any(isinstance(x, dict) and x.get("kind") == kind for x in e.writes.values())]
        if system:
            cands = [e for e in cands if e.settings.get("set.SOURCE_SYSTEM", "").upper() == system.upper()
                     or system.lower() in e.name.lower()] or cands
        if not cands:
            raise LookupError(f"DRS-9308: kind '{kind}'{f' (source {system})' if system else ''} has no write path: no connector that serves it "
                              "offers writes. Bind one in Admin → Data bindings (write).")
        if len(cands) > 1:
            raise LookupError(f"DRS-9309: kind '{kind}' has {len(cands)} write paths, {', '.join(e.name for e in cands)}, and the record names "
                              "no source system. Name one in Admin → Data bindings (write).")
        return cands[0].name


# ------------------------------------------------------------------------------------------------------------- the cached answers

_LOCK = threading.Lock()
_CACHE: dict[tuple, tuple[tuple, Resolution]] = {}


def _stamp(s: Settings) -> tuple:
    """What changes when a layer changes: the folders' and files' modification times (a folder's changes when an entry comes or goes)."""
    out = []
    paths = [s.site_dir, s.kernel_dir, s.modules_home, s.switches_dir, s.connectors_dir, s.bindings_file, s.rules_file, s.installed_dir,
             (s.modules_path / "installed" / "active.yaml") if s.modules_path else None, s.distributions_dir]
    if s.modules_home is not None and s.modules_home.is_dir():
        paths += [d / f for d in _dirs(s.modules_home) for f in ("module.yaml", "dmod/module.yaml", "src/dmod/module.yaml")]
    if s.switches_dir.is_dir():
        paths += sorted(s.switches_dir.glob("*.yaml"))
    for p in paths:
        try:
            st = p.stat() if p is not None else None
            out.append(st.st_mtime_ns if st else None)
        except OSError:
            out.append(None)
    return tuple(out)


def _cached(s: Settings, modules: Any) -> Resolution:
    key = (s, tuple(sorted(modules)) if modules not in (None, ALL) else modules)
    stamp = _stamp(s)
    with _LOCK:
        hit = _CACHE.get(key)
        if hit and hit[0] == stamp:
            return hit[1]
    r = build(s, modules)
    with _LOCK:
        if len(_CACHE) > 16:
            _CACHE.clear()
        _CACHE[key] = (stamp, r)
    return r


def current(settings=None, console_dir: Path | str | None = None, modules: Iterable[str] | None = None) -> Resolution:
    """The site's resolution (settings and environment; on the console its settings), read again when a layer's files change.
    ``modules``: the server's list of modules that are on; None decides offline."""
    return _cached(Settings.from_env(settings, console_dir), modules)


def repository(root: Path | str | None = None) -> Resolution:
    """The checkout's resolution with every module on (the *all* distribution): what a generator or a test of the shipped files means."""
    return _cached(Settings.repository(root), ALL)


def refresh() -> None:
    """Forget every cached answer (the next one reads the files again)."""
    with _LOCK:
        _CACHE.clear()


def everything(settings=None, console_dir: Path | str | None = None) -> Resolution:
    """The site's resolution with every module found on: what any module could bring (a distribution's universe of commands)."""
    return _cached(Settings.from_env(settings, console_dir), ALL)


# ------------------------------------------------------------------------------- folder helpers for the tools (all through the layers)

def _for_root(root: Path | str | None) -> Resolution:
    """The repository's resolution (every module on) for no root or the checkout's ``config/packs``; any other folder is read as a site
    folder of its own (its packs and group folders, its ``core`` as the kernel's), with no modules."""
    if root is None or _norm(root) == _norm(REPO_PACKS):
        return repository()
    r = _norm(root)
    s = _ROOTS.get(r)
    if s is None:
        s = resolve(Layout.of(r), lambda _m: False)
        if len(_ROOTS) > 32:
            _ROOTS.clear()
        _ROOTS[r] = s
    return s


_ROOTS: dict[Path, Resolution] = {}


def pack_dirs(root: Path | str | None = None) -> dict[str, Path]:
    """Every pack's folder by name, in name order (a name two places hold is in neither: :func:`duplicates`)."""
    return {n: p.folder for n, p in _for_root(root).packs.items()}


def pack_dir(name: str, root: Path | str | None = None) -> Path:
    """A pack's folder; ``<site>/<name>`` (not there) when no layer has it. ValueError naming both places when two hold it."""
    r = _for_root(root)
    p = r.packs.get(name)
    if p is not None:
        return p.folder
    twice = next((x for x in r.problems if x.code == "DRS-1042" and f"pack '{name}' is in two places" in x.message), None)
    if twice is not None:
        raise ValueError(twice.message)
    return r.layout.site / name


def manifests(root: Path | str | None = None) -> list[Path]:
    """Every pack's ``pack.yaml``, in pack-name order."""
    return _for_root(root).packs.manifests()


def duplicates(root: Path | str | None = None) -> dict[str, str]:
    """The pack names two places hold, each with the problem naming both (such a name is never read)."""
    out = {}
    for x in _for_root(root).problems:
        m = re.match(r"pack '([^']+)' is in two places", x.message)
        if m:
            out[m.group(1)] = x.message
    return out


def module_list(root: Path | str | None = None) -> dict[str, Module]:
    """Every module of the checkout (``root``: another modules folder), by id."""
    if root is None:
        return repository().modules
    return modules(Layout.of(REPO_PACKS, modules=root))
