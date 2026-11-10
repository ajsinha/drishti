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
"""The modules' console parts, hosted declaratively (docs/architecture/KERNEL_AND_MODULES.md sections 4.7, 9, 12 and 14.2.1).

A module's console part is the ``console/`` folder of its ``.dmod`` (``plugin.yaml`` format 2, templates, ``drishti_<module>-*`` statics,
menus, words, Home cards, AI tool descriptions), its ``docs/`` (Help) and its ``elements/<lib>/`` (a domain Elements library). The console
reads them and runs nothing of them: no module Python is imported, no route is registered by a module. Two sources, one way:

* the server's module registry (``GET /public/modules/console``): every module deployed on the cluster, active, off or removed; an active
  version's part arrives as a zip (``/public/modules/console/<id>/<version>``) and is kept under ``<cache>/<id>/<version>/``;
* the modules the product ships beside the console (``module_manifests_dir``, ``modules/<id>/``), each hosted while the server lists it
  loaded and no deployed version replaces it.

What a snapshot holds is swapped whole (copy-on-write, no lock on the request path) and holds nothing of any person (session isolation):
the parts that are active, the modules that are gone (off or removed, with the names they owned, so saved work says *needs module X*),
and the parts refused (a check failed: a copy of Drishti Elements or of a vendored library, a file without the ``drishti_`` prefix, code in
the console part, a template that reaches past ``kernel/`` and its own namespace). A refused part is absent, and Admin -> Modules says why.
"""
from __future__ import annotations

import asyncio
import hashlib
import io
import logging
import re
import shutil
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote

import yaml

LOG = logging.getLogger("drishti.console.module_parts")
_REF = re.compile(r"{%-?\s*(?:extends|include|import|from)\s+['\"]([^'\"]+)['\"]")
_PARAM = re.compile(r"{(\w+)}")
_CODE = (".py", ".pyc", ".jar", ".class", ".so", ".dll", ".exe", ".sh", ".ps1")
MAX_ZIP = 64 * 1048576


@dataclass(frozen=True)
class Route:
    """One declared route (``routes:`` of plugin.yaml): a composed page (``page:``) or a Sutra (``sutra:``), under ``/m/<id>/``."""
    id: str
    path: str
    rx: re.Pattern
    page: str = ""
    sutra: str = ""
    function: str = ""
    title: str = ""

    def match(self, rest: str) -> dict | None:
        m = self.rx.fullmatch("/" + rest.strip("/"))
        return m.groupdict() if m else None

    def url(self, module: str, **params) -> str:
        return f"/m/{module}" + _PARAM.sub(lambda m: quote(str(params.get(m.group(1), "")), safe=""), self.path)


@dataclass(frozen=True)
class Part:
    """An active module's console part, read from its folder."""
    id: str
    version: str
    title: str
    folder: Path
    routes: tuple = ()
    menus: tuple = ()
    cards: tuple = ()
    tools: tuple = ()
    lenses: tuple = ()
    help: dict = field(default_factory=dict, hash=False, compare=False)
    source: str = "server"
    visuals: tuple = ()          # its own visual types (``visuals:`` of plugin.yaml): kinds its domain library registers (the custom-visual SDK)

    def visual_list(self) -> list[dict]:
        """Each visual with the address of the library file that registers it (``/m/<id>/elements/<lib>/<version>/<file>``)."""
        return [dict(v, module=self.id, version=self.version, script=f"/m/{self.id}/elements/{v['library']}/{self.version}/{v['file']}")
                for v in self.visuals]

    @property
    def templates(self) -> Path:
        return self.folder / "console" / "templates"

    @property
    def pages(self) -> Path:
        return self.folder / "console" / "pages"

    def asset(self, path: str) -> str:
        return f"/m/{self.id}/static/{self.version}/{path.lstrip('/')}"

    def route(self, rid: str) -> Route | None:
        return next((r for r in self.routes if r.id == rid), None)

    def word(self, word: str) -> Route | None:
        w = word.upper()
        return next((r for r in self.routes if r.function and r.function.upper() == w), None)


@dataclass(frozen=True)
class Gone:
    """A module that is off or removed: its title, state and the names it owned (``function W``, ``kind k``, ``lens l``)."""
    id: str
    title: str
    state: str
    claims: tuple = ()

    def words(self) -> list[str]:
        return [c.split(" ", 1)[1] for c in self.claims if c.startswith("function ")]

    def lenses(self) -> list[str]:
        return [c.split(" ", 1)[1] for c in self.claims if c.startswith("lens ")]

    @property
    def text(self) -> str:
        return f"needs module {self.id}" + (" (off)" if self.state == "off" else " (not installed)")


@dataclass(frozen=True)
class Snapshot:
    parts: dict = field(default_factory=dict)
    gone: dict = field(default_factory=dict)
    refused: dict = field(default_factory=dict)
    fingerprint: str = ""


EMPTY = Snapshot()


def _routes(module: str, raw: list) -> tuple:
    out = []
    for r in raw or []:
        if not isinstance(r, dict) or not r.get("id") or not str(r.get("path") or "").startswith("/"):
            continue
        path = str(r["path"])
        rx = re.compile(_PARAM.sub(lambda m: f"(?P<{m.group(1)}>[^/]+)", re.escape(path).replace(r"\{", "{").replace(r"\}", "}")))
        out.append(Route(str(r["id"]), path, rx, str(r.get("page") or ""), str(r.get("sutra") or ""), str(r.get("function") or "").upper(),
                         str(r.get("title") or r["id"])))
    return tuple(out)


def _yaml(f: Path) -> dict:
    try:
        return dict(yaml.safe_load(f.read_text(encoding="utf-8")) or {})
    except (OSError, yaml.YAMLError, ValueError, TypeError):
        return {}


def _help(module: str, title: str, folder: Path, manifest: dict) -> dict:
    """The module's Help (``docs.help`` of its manifest): a section head per ``sections:`` entry and its pages, each read from the module's
    ``docs/`` folder (a ``file:`` relative to the Help file), tagged with the module (so they go when it goes)."""
    name = str(((manifest.get("docs") or {}).get("help")) or "")
    if not name:
        return {}
    hf = folder / name
    data = _yaml(hf)
    heads, pages = [], []
    for s in data.get("sections") or []:
        if not isinstance(s, dict) or not s.get("id"):
            continue
        sid = f"module-{module}-{s['id']}" if s["id"] != module else f"module-{module}"
        guides = []
        for i, p in enumerate(s.get("pages") or []):
            if not isinstance(p, dict) or not p.get("file"):
                continue
            f = (hf.parent / str(p["file"])).resolve()
            if folder.resolve() not in f.parents or not f.is_file():
                continue
            guides.append({"slug": f"{sid}-{i + 1}", "doc": str(f), "title": str(p.get("title") or f.stem), "domain": module,
                           "icon": "puzzle", "summary": str(p.get("summary") or "")})
        if not guides:
            continue                                            # pages the package does not carry (a module run from its sources)
        heads.append({"id": sid, "name": str(s.get("title") or title), "icon": "puzzle", "blurb": f"The {title} module", "domain": module})
        pages.append({"id": sid, "groups": [{"title": str(s.get("title") or title), "guides": guides}]})
    return {"help-sections": heads, "sections": pages}


class ModuleParts:
    """The console's hosted module parts: refreshed from the server (at most every ``ttl`` seconds, or at once when asked), swapped whole."""

    def __init__(self, cache: Path, web: Path, shipped: Path | None = None, ttl: float = 5.0):
        self.cache, self.web, self.shipped, self.ttl = Path(cache), Path(web), shipped, float(ttl)
        self.snapshot: Snapshot = EMPTY
        self._checked = 0.0
        self._lock = asyncio.Lock()
        self._envs: dict[tuple[str, str], object] = {}
        self._kernel_hashes: frozenset[str] | None = None
        self._libs = frozenset(p.name for p in (self.web / "static" / "elements").iterdir()) if (self.web / "static" / "elements").is_dir() else frozenset()

    # ---- refresh -----------------------------------------------------------------------------------------------------------------
    async def refresh(self, backend, loaded=None, force: bool = False) -> bool:
        """Asks the server what is deployed and swaps the snapshot when anything changed; True when it did. ``loaded``: the modules the
        server's about lists (its registry's), for the parts the product ships. A server that cannot say keeps the snapshot."""
        if not force and time.monotonic() - self._checked < self.ttl:
            return False
        async with self._lock:
            if not force and time.monotonic() - self._checked < self.ttl:
                return False
            self._checked = time.monotonic()
            try:
                listed = await backend.public("/public/modules/console", timeout=5.0)
            except Exception as e:  # noqa: BLE001 - an older server or one not reachable: the parts stay as they are
                LOG.debug("module parts: the server's list is not available (%s)", e)
                listed = None
            if listed is None and self.snapshot is not EMPTY:
                return False
            entries = [m for m in (listed or {}).get("modules") or [] if isinstance(m, dict) and m.get("id")]
            key = repr(sorted((str(m.get("id")), str(m.get("version")), str(m.get("state"))) for m in entries)) + "|" + repr(sorted(loaded or []))
            if key == self.snapshot.fingerprint:
                return False
            parts, gone, refused = {}, {}, {}
            for m in entries:
                mid, ver, st = str(m["id"]), str(m.get("version") or ""), str(m.get("state") or "")
                if st != "active":
                    gone[mid] = Gone(mid, str(m.get("title") or mid), st, tuple(str(c) for c in m.get("claims") or []))
                    continue
                if not m.get("console"):
                    continue
                folder = self.cache / mid / ver
                if not (folder / "module.yaml").is_file():
                    try:
                        await self._fetch(backend, mid, ver, folder)
                    except Exception as e:  # noqa: BLE001 - not handed over: absent here, said in the log
                        LOG.warning("module parts: %s %s not fetched (%s)", mid, ver, e)
                        continue
                self._read(mid, folder, "server", parts, refused)
            shipped = self._shipped()                   # the product's own modules, while the server has them loaded and nothing replaced them
            content = {m for m, f in shipped.items() if f.name != "dmod"}   # a module project (src/dmod: genomics) only when the server says it is loaded
            for mid in sorted(content if loaded is None else set(loaded) & set(shipped)):
                if mid not in parts and mid not in gone:
                    self._read(mid, shipped[mid], "shipped", parts, refused)
            self.snapshot = Snapshot(parts, gone, refused, key)
            self._envs = {k: v for k, v in self._envs.items() if k[0] in parts and parts[k[0]].version == k[1]}
            LOG.warning("module parts: hosted %s; gone %s; refused %s", sorted(parts), sorted(gone), sorted(refused))
            return True

    def _shipped(self) -> dict[str, Path]:
        """The product's own modules with a console part (``modules/<folder>/module.yaml``), by id."""
        out = {}
        found = sorted(self.shipped.glob("*/module.yaml")) + sorted(self.shipped.glob("*/src/dmod/module.yaml")) if self.shipped and self.shipped.is_dir() else []
        for f in found:                             # a content module's folder, or a module project's src/dmod (the SDK's layout)
            mid = str(_yaml(f).get("id") or "")
            if mid and (f.parent / "console" / "plugin.yaml").is_file():
                out[mid] = f.parent
        return out

    async def _fetch(self, backend, mid: str, ver: str, folder: Path) -> None:
        data = await backend.public(f"/public/modules/console/{quote(mid, safe='')}/{quote(ver, safe='')}", raw=True, timeout=60.0)
        if len(data) > MAX_ZIP:
            raise ValueError("the console part is larger than 64 MB")
        tmp = folder.with_name(folder.name + ".incoming")
        shutil.rmtree(tmp, ignore_errors=True)
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for info in z.infolist():
                target = (tmp / info.filename).resolve()
                if tmp.resolve() not in target.parents or info.is_dir():
                    continue                                    # nothing outside its own folder
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(z.read(info))
        shutil.rmtree(folder, ignore_errors=True)
        folder.parent.mkdir(parents=True, exist_ok=True)
        tmp.rename(folder)

    def _read(self, mid: str, folder: Path, source: str, parts: dict, refused: dict) -> None:
        manifest = _yaml(folder / "module.yaml")
        plugin = _yaml(folder / str(((manifest.get("console") or {}).get("plugin")) or "console/plugin.yaml"))
        problems = self.check(mid, folder, plugin)
        if problems:
            refused[mid] = problems
            LOG.warning("module parts: %s refused: %s", mid, "; ".join(problems))
            return
        title = str(manifest.get("title") or plugin.get("title") or mid)
        menus = tuple(dict(x, module=mid) for x in plugin.get("menus") or [] if isinstance(x, dict) and x.get("label"))
        cards = tuple(dict(x, module=mid) for x in (plugin.get("home") or {}).get("cards") or [] if isinstance(x, dict) and x.get("title"))
        tools = tuple(dict(x, module=mid) for x in (plugin.get("ai") or {}).get("tools") or [] if isinstance(x, dict) and x.get("name"))
        lenses = tuple(str(x) for x in plugin.get("lenses") or [])
        visuals = tuple({k: str(x.get(k) or "") for k in ("kind", "library", "file", "title", "summary")}
                        for x in plugin.get("visuals") or [] if isinstance(x, dict) and x.get("kind") and x.get("library") and x.get("file")
                        and (folder / "elements" / str(x["library"]) / str(x["file"])).is_file())
        parts[mid] = Part(mid, str(manifest.get("version") or ""), title, folder, _routes(mid, plugin.get("routes")), menus, cards, tools, lenses,
                          _help(mid, title, folder, manifest), source, visuals=visuals)

    # ---- the checks (section 11: Elements are the console's; section 14.2.1: declarative only) ------------------------------------
    def kernel_hashes(self) -> frozenset[str]:
        """SHA-256 of every file of Drishti Elements and of the vendored libraries the console serves: a module carries none of them."""
        if self._kernel_hashes is None:
            out = set()
            for sub in ("elements", "vendor"):
                root = self.web / "static" / sub
                for f in root.rglob("*") if root.is_dir() else []:
                    if f.is_file() and f.suffix in (".js", ".mjs", ".css", ".woff2", ".woff", ".ttf"):
                        out.add(hashlib.sha256(f.read_bytes()).hexdigest())
            self._kernel_hashes = frozenset(out)
        return self._kernel_hashes

    def check(self, mid: str, folder: Path, plugin: dict) -> list[str]:
        """Why a console part may not be hosted (empty: it may)."""
        out = []
        if str(plugin.get("format")) != "2":
            out.append("console/plugin.yaml must be format: 2 (every route declared, under /m/" + mid + "/)")
        hashes = self.kernel_hashes()
        for sub in ("console", "elements"):
            root = folder / sub
            for f in sorted(root.rglob("*")) if root.is_dir() else []:
                if not f.is_file():
                    continue
                rel = f.relative_to(folder).as_posix()
                if f.suffix in _CODE:
                    out.append(f"{rel}: a console part runs no code (declarative only)")
                elif "/vendor/" in rel:
                    out.append(f"{rel}: a vendored library; the console's vendored set serves every module")
                elif (rel.startswith("console/static/") or sub == "elements") and not f.name.startswith("drishti_") and f.name != "README.md":
                    out.append(f"{rel}: every static file starts with drishti_")
                elif f.suffix in (".js", ".mjs", ".css") and hashlib.sha256(f.read_bytes()).hexdigest() in hashes:
                    out.append(f"{rel}: a copy of a Drishti Elements or vendored file; the console serves Elements to every module")
                elif sub == "elements" and len(f.relative_to(root).parts) > 1 and f.relative_to(root).parts[0] in self._libs:
                    out.append(f"{rel}: the library {f.relative_to(root).parts[0]} is the console's own")
                elif f.suffix == ".html":
                    for ref in _REF.findall(f.read_text(encoding="utf-8", errors="replace")):
                        if not (ref.startswith("kernel/") or ref.startswith(mid + ":")):
                            out.append(f"{rel}: reaches {ref} (a module's template sees only kernel/ and its own {mid}: files)")
        return out

    # ---- what the doors ask ------------------------------------------------------------------------------------------------------
    def part(self, mid: str) -> Part | None:
        return self.snapshot.parts.get(mid)

    def gone(self, mid: str) -> Gone | None:
        return self.snapshot.gone.get(mid)

    def word(self, word: str):
        """The (part, route) a typed function word opens (a composed page; a Sutra route is the kernel's page of the word)."""
        w = word.upper()
        for p in self.snapshot.parts.values():
            r = p.word(w)
            if r is not None:
                return p, r
        return None

    def gone_word(self, word: str) -> Gone | None:
        w = word.upper()
        return next((g for g in self.snapshot.gone.values() if w in {x.upper() for x in g.words()}), None)

    def gone_lens(self, lens: str) -> Gone | None:
        return next((g for g in self.snapshot.gone.values() if lens in g.lenses()), None)

    def menus(self) -> list[dict]:
        return [m for p in self.snapshot.parts.values() for m in p.menus]

    def cards(self, lens: str | None = None) -> list[dict]:
        return [c for p in self.snapshot.parts.values() for c in p.cards if not c.get("lens") or c.get("lens") == lens]

    def tools(self) -> list[dict]:
        return [t for p in self.snapshot.parts.values() for t in p.tools]

    def visuals(self) -> list[dict]:
        """Every hosted module's own visuals (the Elements gallery shows them while the module is on, and only then)."""
        return [v for p in self.snapshot.parts.values() for v in p.visual_list()]

    def help_heads(self) -> list[dict]:
        return [h for p in self.snapshot.parts.values() for h in p.help.get("help-sections") or []]

    def help_pages(self) -> list[dict]:
        return [s for p in self.snapshot.parts.values() for s in p.help.get("sections") or []]

    def href(self, item: dict) -> str:
        """A menu item's or card's link: its route (``route: <id>`` with ``params:``), else ``href``, else its ``cmd`` run as typed."""
        p = self.part(str(item.get("module") or ""))
        if p is not None and item.get("route") and (r := p.route(str(item["route"]))) is not None:
            return r.url(p.id, **(item.get("params") or {}))
        if item.get("href"):
            return str(item["href"])
        return "/go?q=" + quote(f"{item.get('cmd', '')} <GO>") if item.get("cmd") else "#"

    # ---- the templates: one sandboxed environment per module (section 9.2) -------------------------------------------------------
    def env(self, part: Part, base):
        """The module's own Jinja environment: the console's filters and globals, autoescape on, a loader that reads ``<id>:<path>`` from
        the module's templates and anything else from the console's (its templates reach only ``kernel/``: :meth:`check`)."""
        key = (part.id, part.version)
        hit = self._envs.get(key)
        if hit is not None:
            return hit
        from jinja2 import BaseLoader, TemplateNotFound

        console_loader = base.loader

        class Loader(BaseLoader):
            def get_source(self, environment, template):
                if template.startswith(part.id + ":"):
                    rel = template.split(":", 1)[1].lstrip("/")
                    for root in (part.templates, part.pages):
                        f = (root / rel).resolve()
                        if root.resolve() in f.parents and f.is_file():
                            src = f.read_text(encoding="utf-8")
                            mtime = f.stat().st_mtime
                            return src, str(f), lambda: f.is_file() and f.stat().st_mtime == mtime
                    raise TemplateNotFound(template)
                return console_loader.get_source(environment, template)

        env = base.overlay(loader=Loader(), autoescape=True)
        env.globals = dict(base.globals)
        env.globals.update(asset=part.asset, module={"id": part.id, "version": part.version, "title": part.title},
                           url_for=lambda rid, **kw: (part.route(rid).url(part.id, **kw) if part.route(rid) else "#"),
                           api=lambda path, **q: f"/m/{part.id}/api/{path.lstrip('/')}")
        env.globals["elements"] = env.get_template("kernel/elements.html").module
        self._envs[key] = env
        return env
