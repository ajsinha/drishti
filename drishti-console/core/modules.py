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

"""Modules in the console (docs/architecture/ACCESS_MODEL.md section 2, DOMAIN_MODULES.md section 8): a module that is off is gone from
every door the console draws, and so is a function the person may not use.

Two answers from the server, never the access model itself (the console and the browser ask the server, which asks the registry):

* **the installation's**: ``modules`` of the public about answer (polled by core/backend_watch.py with the edition and the engines): the
  modules loaded at the server's start. Kept per app (:class:`State`, one per :class:`core.edition.Editions`, never shared between the
  apps of one process). Until the server has said (an older server, no answer yet) nothing is hidden.
* **the person's**: ``GET /api/v1/modules`` as the person (:class:`Me`, cached per person for ``ui.user_cache_seconds`` and dropped on a
  change through any worker): the modules not loaded with why, the lenses open to them (with the modules each needs) and the functions
  they may not use, each with its module and the words to say. Set per request (:func:`use_person`); nothing per person is kept in shared
  state.

What a module brings to the console is its plugin manifest, ``drishti-console/plugins/<module>/plugin.yaml``: its routers and the files
that register its hooks (core/plugins.py), its compute engines (whose words, pages, menus, Help and tiles the engine catalogue lists),
its own words, console paths, static assets (globs under ``web/static``) and the core screens it adds to. One helper per door is built
here; the doors (core/edition.py, core/edition_gate.py, core/menus.py, routes/help_routes.py, core/search.py, routes/terminal_routes.py,
the static gate in core/app.py and the Elements loader) ask them.
"""
from __future__ import annotations

import fnmatch
import logging
import time
from contextvars import ContextVar
from dataclasses import dataclass, field
from pathlib import Path

import yaml

LOG = logging.getLogger("drishti.console.modules")
CONSOLE = Path(__file__).resolve().parent.parent
PLUGINS = CONSOLE / "plugins"
CORE = "core"


@dataclass(frozen=True)
class Plugin:
    """One module's console part, as its ``plugin.yaml`` declares it."""
    id: str
    title: str
    routers: tuple[str, ...] = ()
    registers: tuple[str, ...] = ()
    code: tuple[str, ...] = ()
    engines: tuple[str, ...] = ()
    words: tuple[str, ...] = ()
    paths: tuple[str, ...] = ()
    assets: tuple[str, ...] = ()
    templates: tuple[str, ...] = ()
    screens: dict = field(default_factory=dict, hash=False, compare=False)
    help: dict = field(default_factory=dict, hash=False, compare=False)   # its Help: help-sections (heads) and sections (pages)


_plugins: list[Plugin] | None = None


def _tuple(v) -> tuple[str, ...]:
    return tuple(str(x) for x in (v or []))


def _help(folder: Path, name) -> dict:
    """A plugin's Help file (``help:`` in its plugin.yaml, beside it): ``help-sections`` (heads, each with ``after:``) and ``sections``."""
    if not name:
        return {}
    try:
        return dict(yaml.safe_load((folder / str(name)).read_text(encoding="utf-8")) or {})
    except (OSError, yaml.YAMLError) as e:
        LOG.warning("modules: cannot read %s's Help %s (%s)", folder.name, name, e)
        return {}


def help_heads(heads: list[dict]) -> list[dict]:
    """Help's section heads with every module's added, each after the section its ``after:`` names (else at the end), its
    ``domain:`` the module's."""
    out = [dict(h) for h in heads]
    for p in plugins():
        for h in p.help.get("help-sections") or []:
            if not isinstance(h, dict) or not h.get("id") or any(x.get("id") == h["id"] for x in out):
                continue
            head = {k: v for k, v in h.items() if k != "after"}
            head.setdefault("domain", p.id)
            at = next((i + 1 for i, x in enumerate(out) if x.get("id") == h.get("after")), len(out))
            out.insert(at, head)
    hosted = state().parts                                    # the hosted modules' Help (core/module_parts.py): at the end, theirs
    for h in hosted.help_heads() if hosted is not None else []:
        if not any(x.get("id") == h["id"] for x in out):
            out.append(dict(h))
    return out


def help_pages() -> list[dict]:
    """Every module's Help pages (``sections:`` of its Help file), the hosted modules' too."""
    hosted = state().parts
    return [dict(s) for p in plugins() for s in p.help.get("sections") or [] if isinstance(s, dict) and s.get("id")] + (
        [dict(s) for s in hosted.help_pages()] if hosted is not None else [])


def parts_fingerprint() -> str:
    """What the hosted modules are now (a Help library is built per value: a module that comes or goes gives a new one)."""
    hosted = state().parts
    return hosted.snapshot.fingerprint if hosted is not None else ""


def plugins() -> list[Plugin]:
    """Every module's console part, in the order of their folders (read once)."""
    global _plugins
    if _plugins is None:
        out = []
        for f in sorted(PLUGINS.glob("*/plugin.yaml")):
            try:
                raw = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
            except (OSError, yaml.YAMLError) as e:
                LOG.warning("modules: cannot read %s (%s); that module's console part is not loaded", f, e)
                continue
            mid = str(raw.get("module") or f.parent.name)
            out.append(Plugin(mid, str(raw.get("title") or mid), _tuple(raw.get("routers")), _tuple(raw.get("registers")),
                              _tuple(raw.get("code")), _tuple(raw.get("engines")), tuple(w.upper() for w in _tuple(raw.get("words"))), _tuple(raw.get("paths")),
                              _tuple(raw.get("assets")), _tuple(raw.get("templates")),
                              dict(raw.get("screens") or {}), _help(f.parent, raw.get("help"))))
        _plugins = out
    return _plugins


def plugin(module_id: str) -> Plugin | None:
    return next((p for p in plugins() if p.id == module_id), None)


# ---- the installation's answer, per app --------------------------------------------------------------------------------------------
class State:
    """What one console app has learned from its server: the modules loaded (None: not learned, nothing hidden)."""

    __slots__ = ("loaded", "parts")

    def __init__(self) -> None:
        self.loaded: frozenset[str] | None = None
        self.parts = None                          # the hosted modules' console parts (core/module_parts.py ModuleParts), this app's


_default = State()
_current: ContextVar[State | None] = ContextVar("drishti_console_modules", default=None)
_person: ContextVar[dict | None] = ContextVar("drishti_console_modules_person", default=None)


def new_state() -> State:
    """A new app's state; it becomes the process default (the newest app), a request reads its own app's."""
    global _default
    _default = State()
    return _default


def use(state: State | None):
    """Requests of the app whose state this is read it (core/app.py, around every request); returns the token to reset."""
    return _current.set(state)


def reset_use(token) -> None:
    _current.reset(token)


def state() -> State:
    s = _current.get()
    return s if s is not None else _default


def learn(modules, into: State | None = None) -> bool:
    """The about answer's ``modules`` (ids loaded) into an app's state; True when it changed. ``None`` (an older server): nothing hidden."""
    s = into if into is not None else state()
    now = None if modules is None else frozenset(str(m) for m in modules)
    if now == s.loaded:
        return False
    if s.loaded is not None or now is not None:
        LOG.warning("modules: loaded on the server %s", sorted(now) if now is not None else "(not said)")
    s.loaded = now
    return True


# ---- the person's answer, per request ----------------------------------------------------------------------------------------------
def person() -> dict | None:
    """The person's answer for this request (:func:`use_person`), or None (not signed in, not asked, or an older server)."""
    return _person.get()


def use_person(answer: dict | None):
    return _person.set(_shape(answer) if answer else None)


def reset_person(token) -> None:
    _person.reset(token)


def _shape(answer: dict) -> dict:
    """``GET /api/v1/modules`` as the doors read it: ``off`` {module: reason}, ``lenses`` {id: modules}, ``functions`` {WORD: text},
    ``loaded`` and their ``titles`` {module: title} (Home's house, core/house.py)."""
    off = {str(m.get("id")): str(m.get("reason") or "") for m in answer.get("off") or [] if isinstance(m, dict) and m.get("id")}
    lenses = {str(l.get("id")): frozenset(str(x) for x in l.get("modules") or []) for l in answer.get("lenses") or []
              if isinstance(l, dict) and l.get("id")}
    funcs = {}
    for f in ((answer.get("functions") or {}).get("off") or []):
        if isinstance(f, dict) and f.get("word"):
            w = str(f["word"]).upper()
            funcs[w] = {"module": str(f.get("module") or CORE), "reason": str(f.get("reason") or ""), "gate": str(f.get("gate") or ""),
                        "text": str(f.get("text") or f"{w} is not available to you: ask your administrator")}
    cards = {str(l.get("id")): [dict(c) for c in l.get("cards") or [] if isinstance(c, dict) and c.get("cmd")]
             for l in answer.get("lenses") or [] if isinstance(l, dict) and l.get("id") and l.get("cards")}
    chips = {str(l.get("id")): [str(c) for c in l.get("chips") or [] if str(c).strip()]
             for l in answer.get("lenses") or [] if isinstance(l, dict) and l.get("id") and l.get("chips")}
    return {"off": off, "lenses": lenses, "functions": funcs, "cards": cards, "chips": chips,
            "loaded": frozenset(str(m.get("id")) for m in answer.get("modules") or [] if isinstance(m, dict) and m.get("id")),
            "titles": {str(m.get("id")): str(m.get("title") or "") for m in answer.get("modules") or [] if isinstance(m, dict) and m.get("id")}}


def lens_chips(lens: str | None) -> list | None:
    """A lens's Home chips, as the registry's lens carries them (``chips:``, in the person's answer); None when not said."""
    p = person()
    if not lens or p is None:
        return None
    got = (p.get("chips") or {}).get(lens)
    return list(got) if got else None


def lens_cards(lens: str | None) -> list | None:
    """A lens's Home cards, as the registry's lens carries them (``cards:`` of its lenses.yaml, in the person's answer); None when not said."""
    p = person()
    if not lens or p is None:
        return None
    got = (p.get("cards") or {}).get(lens)
    return list(got) if got else None


class Me:
    """The person's answer, cached per person (session isolation: keyed by the person, never shared), dropped on a change seen by any
    worker (core/user_stamps.py). A server that cannot say (an older one, a test's stand-in) gives None: nothing hidden for the person."""

    def __init__(self, ttl: float = 10.0, stamps=None):
        from core.user_stamps import NONE

        self.ttl = float(ttl)
        self.stamps = stamps or NONE
        self._cache: dict[str, tuple[float, dict | None, float]] = {}

    async def get(self, backend, ident) -> dict | None:
        if ident is None:
            return None
        from core.servers import scoped

        key = scoped(ident.user)
        hit = self._cache.get(key)
        if hit and time.monotonic() - hit[0] < self.ttl and self.stamps.fresh(key, hit[2]):
            return hit[1]
        began = time.time()
        ask = getattr(backend, "modules", None)
        try:
            answer = await ask(ident) if ask is not None else None
        except Exception:  # noqa: BLE001 - the person's answer narrows what is drawn; a page never fails for it
            answer = None
        answer = answer if isinstance(answer, dict) else None
        if len(self._cache) > 4096:
            self._cache.clear()
        self._cache[key] = (time.monotonic(), answer, began)
        return answer

    def forget_all(self) -> None:
        self._cache.clear()


# ---- the questions every door asks -------------------------------------------------------------------------------------------------
def module_on(module_id: str | None) -> bool:
    """Whether a module is on here (the core always is). Off: the installation's answer does not list it, or the person's says so."""
    m = str(module_id or CORE)
    if m == CORE:
        return True
    p = person()
    if p is not None and m in p["off"]:
        return False
    hosted = state().parts                       # a deployed module that is off or removed (core/module_parts.py), or one hosted now
    if hosted is not None and hosted.gone(m) is not None:
        return False
    if hosted is not None and hosted.part(m) is not None and hosted.part(m).source == "server":
        return True
    loaded = state().loaded
    return loaded is None or m in loaded


def why_off(module_id: str) -> str:
    p = person()
    reason = (p or {}).get("off", {}).get(module_id) if p else ""
    title = plugin(module_id).title if plugin(module_id) else module_id
    return f"the module '{title}' is not on here" + (f": {reason}" if reason else "") + " (config/modules/<module>.yaml or DRISHTI_MODULES_<MODULE>_ENABLED, then a restart)"


def module_of_engine(engine_id: str) -> str | None:
    return next((p.id for p in plugins() if engine_id in p.engines), None)


def _engine_words(p: Plugin) -> list[str]:
    from core import engines

    return [str(w).upper() for e in p.engines for w in ((engines.entry(e) or {}).get("words") or [])]


def module_of_word(word: str) -> str | None:
    """The module a command word (VAR, ``PLAY VAR``) belongs to by the console's own manifests (its words and its engines' words)."""
    w = " ".join(str(word or "").upper().split())
    for p in plugins():
        if w in p.words or w in _engine_words(p):
            return p.id
    return None


def word_refusal(word: str) -> str | None:
    """Why a command word may not be used by this person (its module is off, or the function is off or not granted), in the words the
    command line says; None when it may. The installation's module state and the person's answer both count."""
    w = " ".join(str(word or "").upper().split())
    if not w:
        return None
    hosted = state().parts
    if hosted is not None and (g := hosted.gone_word(w)) is not None:      # a word of a module that is off or removed: kept, said
        return f"{w} is not available: {g.text} ({g.title})"
    m = module_of_word(w)
    if m is not None and not module_on(m):
        return f"{w} is not available to you: ask your administrator ({why_off(m)})"
    p = person()
    if p is not None and w in p["functions"]:
        return p["functions"][w]["text"]
    return None


def data_need(word: str) -> dict | None:
    """A function that is here but waits for data (the server's data gate: a required need no source serves): ``{word, title, text}``,
    the text as the server says it (``EXP needs data: exposure-path is not bound; ask your data administrator``). Its pages explain
    themselves (core/edition_gate.py ``needs_data``, the needs-data page, 200) instead of answering 404; None otherwise."""
    w = " ".join(str(word or "").upper().split())
    p = person()
    f = (p or {}).get("functions", {}).get(w) if p else None
    if not f or f.get("gate") != "data":
        return None
    from core import engines as _engines

    title = next((str(e.get("title")) for e in _engines.entries() if e.get("title") and w in _engines._words(e)), "") or w
    return {"word": w, "title": title, "text": f.get("text") or f.get("reason") or ""}


def typed_refusal(text: str) -> str | None:
    """The refusal of a command as typed (``VRES DESK:RATES``, ``PLAY VAR BANK``): its first two words, then its first, or None."""
    parts = str(text or "").replace("<GO>", "").replace("<go>", "").upper().split()
    if not parts:
        return None
    if len(parts) > 1 and (why := word_refusal(" ".join(parts[:2]))) is not None and module_of_word(" ".join(parts[:2])) is not None:
        return why
    return word_refusal(parts[0])


def _under(path: str, prefix: str) -> bool:
    exact = prefix.startswith("=")
    p = prefix.lstrip("=")
    if exact:
        return path == p.rstrip("/") or path == p
    return path == p.rstrip("/") or path.startswith(p if p.endswith("/") else p + "/") or path == p


def path_module(path: str) -> Plugin | None:
    """The module whose console page or API the path is (its own paths, then its engines' console paths), whatever its state."""
    from core import engines

    best, length = None, -1
    for p in plugins():
        prefixes = list(p.paths) + [str(x) for e in p.engines for x in ((engines.entry(e) or {}).get("console") or [])]
        for prefix in prefixes:
            if _under(path, prefix) and len(prefix.lstrip("=")) > length:
                best, length = p, len(prefix.lstrip("="))
    return best


def path_refusal(path: str) -> str | None:
    """Why a console page or API is refused (its module is off), or None."""
    p = path_module(path)
    if p is not None and not module_on(p.id):
        return why_off(p.id)
    return None


def asset_module(rel: str) -> Plugin | None:
    """The module a static file belongs to (``js/drishti_var-charts.js``, ``elements/drishti-risk/drishti_risk.js``), or None (core)."""
    rel = rel.lstrip("/")
    for p in plugins():
        if any(fnmatch.fnmatchcase(rel, g) for g in p.assets):
            return p
    return None


def lens_modules(lens: str | None) -> frozenset[str] | None:
    """The modules the person's lens needs (the registry's lens, from the person's answer); None outside a lens or when not said."""
    p = person()
    if not lens or p is None:
        return None
    return p["lenses"].get(lens)


def asset_refusal(rel: str, lens: str | None = None) -> str | None:
    """Why a static file is not served to this request: its module is off, or the person works in a lens that does not need it."""
    p = asset_module(rel)
    if p is None:
        return None
    if not module_on(p.id):
        return why_off(p.id)
    need = lens_modules(lens)
    if need is not None and p.id not in need:
        return f"the lens '{lens}' does not use the module '{p.title}'"
    return None


def modules_for_page(lens: str | None = None) -> list[str]:
    """The domain modules a page may load libraries of (the Elements loader's ``<meta name="drishti-modules">``): on, and needed by the
    person's lens when they work in one."""
    need = lens_modules(lens)
    hosted = state().parts
    ids = [p.id for p in plugins()] + sorted(hosted.snapshot.parts) if hosted is not None else [p.id for p in plugins()]
    return [m for i, m in enumerate(ids) if m not in ids[:i] and module_on(m) and (need is None or m in need)]


def tag_hidden(domain: str | None) -> bool:
    """A sitemap entry, Help section or Build type tagged with a domain (``domain: banking``) whose module is off."""
    return bool(domain) and str(domain) != CORE and not module_on(str(domain))


def screen_parts(screen: str, part: str, **ctx) -> list[str]:
    """What the modules add to a core screen (``screens:`` of a plugin.yaml): ``css``, ``js`` (static paths) or a named template slot,
    for the modules on whose ``when:`` matches the screen's context (``{lens: mr}``)."""
    out: list[str] = []
    for p in plugins():
        spec = (p.screens or {}).get(screen)
        if not isinstance(spec, dict) or not module_on(p.id):
            continue
        when = spec.get("when") or {}
        if any(str(ctx.get(k)) != str(v) for k, v in when.items()):
            continue
        out += [str(x) for x in (spec.get(part) or [])]
    return out


def allowed(items):
    """Suggestions (the type-ahead's, search's commands) without those whose command the person may not use (``mnemonic`` or the first
    words of ``complete``): a function off is not offered anywhere."""
    if not isinstance(items, list):
        return items
    out = []
    for it in items:
        if isinstance(it, dict) and not it.get("id"):
            said = str(it.get("complete") or it.get("mnemonic") or "")
            if said and typed_refusal(said) is not None:
                continue
        out.append(it)
    return out


def template_dirs() -> list[Path]:
    """Each module's templates folder (``plugins/<module>/templates``), searched after the core's ``web/templates``."""
    return [PLUGINS / p.id / "templates" for p in plugins() if (PLUGINS / p.id / "templates").is_dir()]


def static_dirs() -> list[Path]:
    """Each module's static folder (``plugins/<module>/static``), served after the core's ``web/static`` at the same ``/static/...`` paths."""
    return [PLUGINS / p.id / "static" for p in plugins() if (PLUGINS / p.id / "static").is_dir()]
