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

"""The distribution's filter and the lenses, the console side (docs/guides/ADMIN_GUIDE.md, the three layers of switches).

What exists is the *distribution's* (``config/distributions/drishti.<name>.yaml``, core/distribution.py): its filter is the instance's
:class:`Edition`, and its feature presets are a layer under the site's own configuration (:meth:`core.config.Settings.layer`), so the site
still wins key by key. A *lens* (``config/lenses/<module>/<lens>.yaml``, "Open Drishti Risk") is a workspace one person works in, kept in
their settings (``lens``): it arranges (the starting Home, menu emphasis, default screens) and never hides anything; every "is this
shown?" question inside a lens is the distribution's answer.

:class:`Edition` answers every "is this shown?" question; :data:`Edition.NONE` (Drishti All) answers yes to all of them.
"""
from __future__ import annotations

import contextvars
import copy
import logging
import os
import re
from pathlib import Path
from typing import Any

import yaml

from core import engines as _engines
from core import modules as _modules

LOG = logging.getLogger("drishti.console.edition")
LENS_HEADER = "X-Drishti-Lens"                       # the server's EditionContext.HEADER: the person's lens, on every backend call
_lens: contextvars.ContextVar[str | None] = contextvars.ContextVar("drishti_lens", default=None)

WORDS = ("BI", "RUPAKA", "FORM", "FUNC", "WHAT", "DATAMAP", "DATA", "EXPORT", "PIVOT")
#: A command word and the switch it shares: EXPORT (Export data) is switched by an edition's DATA, its older name, kept as an alias.
WORD_SWITCH = {"EXPORT": "DATA"}

# The console pages each reserved word, console word and feature opens: hidden ones answer 404 (core/edition_gate.py).
WORD_PATHS = {"BI": ("/bi",), "FORM": ("/forms", "/form/"), "FUNC": ("/func",), "WHAT": ("/what",), "DATAMAP": ("/datamap",), "DATA": ("/data", "/api/data/"),
              "PIVOT": ("/pivot", "/api/pivot/")}
FEATURE_PATHS = {"bi": ("/bi", "/admin/bi"), "forms": ("/forms", "/form/"), "datamap": ("/datamap",), "data": ("/data", "/api/data/"), "workspaces": ("/w",),
                 "monitors": ("/m",), "alerts": ("/alerts",), "build": ("/build", "/review/", "/studio", "/elements"), "calc": ("/calc",),
                 "reports": ("/reports",), "lakes": ("/admin/lakes", "/admin/api/lakes"),
                 "collaboration": ("/admin/collab",)}
# (Admin -> AI and Admin -> Embedding stay reachable with their feature off, as before: they are where it is switched on.)
# Admin pages by id (a filter's admin.pages, a site's edition.admin.pages); the path prefix each owns
ADMIN_PAGES = ("users", "roles", "packs", "commands", "tokens", "access", "connectors", "artifacts", "ai", "loads", "lakes", "collab",
               "embedding", "bi", "audit", "net", "edits", "compute", "engines", "health", "caches", "capacity", "scopes", "data-out",
               "configuration", "bindings", "white-label")


# The landings' page (LENSES.md › The design): each edition names its own (home.template, home.titles); a file that names none keeps
# Drishti Risk's, as before 2.9.7. Only templates under home/ named *_landing.html are taken.
_LANDING_TEMPLATE = re.compile(r"(?:home/|[a-z0-9-]+:)[a-z0-9_]+_landing\.html")
DEFAULT_LANDING_TEMPLATE = "home/risk_landing.html"
DEFAULT_LANDING_TITLES = {"desk": "Risk desk", "command": "Command first", "map": "Risk map"}


def words() -> set[str]:
    """Every reserved word: the kernel's and those the modules register (core/plugins.py)."""
    from core import plugins as _plugins
    return set(WORDS) | {w.word for w in _plugins.words()}


def word_paths() -> dict[str, tuple[str, ...]]:
    """Each word's console pages: the kernel's and the modules' (core/edition_gate.py answers 404 for one switched off)."""
    from core import plugins as _plugins
    out = dict(WORD_PATHS)
    for w in _plugins.words():
        out[w.word] = out.get(w.word, ()) + w.paths
    return out


def mnemonics() -> set[str]:
    """The registered words that are also a pack's mnemonic: run as the pack's where the edition switches the word off."""
    from core import plugins as _plugins
    return {w.word for w in _plugins.words() if w.mnemonic}


def landing_refused() -> set[str]:
    """Words a landing's read refuses: the kernel's own words (not PIVOT) and the modules' that are not also mnemonics."""
    from core import plugins as _plugins
    return (set(WORDS) - {"PIVOT"}) | {w.word for w in _plugins.words() if not w.mnemonic}


def set_lens(name: str | None) -> None:
    _lens.set(name or None)


def current_lens() -> str | None:
    """The lens this request is seen through (set per request by the console's middleware), or None."""
    return _lens.get()


def _kinds_word(kind: str) -> str:
    """``trade`` -> ``trades``, ``netting-set`` -> ``netting sets``, ``company`` -> ``companies``: the kind as a person says it."""
    words = str(kind or "").replace("-", " ").replace("_", " ").strip()
    if not words:
        return "this kind"
    if words.endswith("y") and not words.endswith(("ay", "ey", "oy", "uy")):
        return words[:-1] + "ies"
    return words if words.endswith("s") else words + "s"


def hidden_hint(edition, kind: str = "") -> str:
    """What to do about ``DRS-4023`` (a view refused because the distribution leaves its kind out), said plainly (EMBED_GUIDE.md 10): never
    "check the kind and id", which sends a person hunting for a typo that is not there. A lens never hides, so it is never the reason."""
    what = _kinds_word(kind)
    outer = getattr(edition, "outer", None) if getattr(edition, "lens", False) else edition
    if outer is not None and getattr(outer, "active", False):
        return f"{what[:1].upper() + what[1:]} are not in {outer.title}: choose an entity it shows."
    return f"This server does not include {what}: choose an entity it shows, or ask an administrator."


def lens_of(edition) -> dict | None:
    """The person's lens as the renderer and the playground show it: ``{name, title}``, or None outside a lens."""
    if edition is not None and getattr(edition, "active", False) and getattr(edition, "lens", False):
        return {"name": edition.name, "title": edition.title}
    return None


def headers() -> dict[str, str]:
    """The header that tells the server which lens the person looks through (its command list, type-ahead and BI follow it)."""
    v = _lens.get()
    return {LENS_HEADER: v} if v else {}


def _on(v: Any) -> bool | None:
    """A switch as the file writes it (on/off, true/false, yes/no; PyYAML already reads them as booleans); None when unset."""
    if v is None or v == "":
        return None
    if isinstance(v, bool):
        return v
    t = str(v).strip().lower()
    return t not in ("off", "false", "no", "0")


def _merge(base: dict, over: dict) -> dict:
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            _merge(base[k], v)
        else:
            base[k] = v
    return base


class Edition:
    """The distribution's filter, or a lens (a workspace: ``lens=True``) as the console reads it. A lens answers only how things are arranged
    (Home, the menus' order, the placeholder); every visibility question it hands to its distribution (``outer``), or answers "shown"."""

    def __init__(self, name: str = "", spec: dict | None = None, lens: bool = False, outer: "Edition | None" = None):
        self.name = (name or "").strip()
        self.spec = spec or {}
        self.lens = lens
        self.outer = outer                     # the distribution a lens works inside (Risk's Trading): it decides what exists

    @property
    def distribution(self) -> bool:
        """True for a distribution's filter (docs/architecture/DISTRIBUTIONS.md): its id is ``drishti.<name>``."""
        return self.name.startswith("drishti.") or bool(self.outer is not None and self.outer.distribution)

    @property
    def filtering(self) -> "Edition":
        """Who answers "is this shown?": the distribution itself, or for a lens the distribution it works inside (none: everything)."""
        if not self.lens:
            return self
        return self.outer if self.outer is not None else Edition.NONE

    def help_domains(self) -> list[str] | None:
        """The domain modules whose Help a distribution keeps (its modules', today); None: no such filter."""
        f = self.filtering
        if f is not self:
            return f.help_domains()
        d = self._get("help.domains") if self.active else None
        return [str(x) for x in d] if isinstance(d, list) else None

    @property
    def active(self) -> bool:
        return bool(self.name)

    @property
    def title(self) -> str:
        return str(self.spec.get("title") or self.name)

    def _get(self, dotted: str, default: Any = None) -> Any:
        node: Any = self.spec
        for part in dotted.split("."):
            if not isinstance(node, dict) or part not in node:
                return default
            node = node[part]
        return node

    # -- commands and words ------------------------------------------------------------------------------------------
    def commands(self) -> set[str]:
        if self.filtering is not self:
            return self.filtering.commands()
        allow = self._get("commands.allow") or []
        allow = list(allow) + list(self._get("commands.also-allow") or [])
        return {str(c).strip().upper() for c in allow if str(c).strip()}

    def command_shown(self, code: str | None) -> bool:
        if code and _engines.word_hidden(code):                  # a word of a compute engine that runs nowhere (ADMIN_GUIDE.md 5.2)
            return False
        if code and _modules.word_refusal(code) is not None:     # its module is off, or the function is off or not the person's (ACCESS_MODEL.md)
            return False
        if self.filtering is not self:                             # a lens: the distribution's answer
            return self.filtering.command_shown(code)
        allow = self.commands() if self.active else set()
        return not allow or not code or code.strip().upper() in allow

    def typed_shown(self, text: str) -> bool:
        """Whether a command as typed (``VAR BANK``, ``VRES DESK:RATES``) is offered: a reserved or console word that is on, or a shown
        command (EXP is both: the word decides)."""
        if _engines.typed_engine(text) is not None:               # PLAY FRTB with FRTB-SA running nowhere
            return False
        if _modules.typed_refusal(text) is not None:              # PLAY VAR with banking off; VRES switched off or not granted
            return False
        head = str(text or "").split()[0].upper() if str(text or "").split() else ""
        return self.word_on(head) if head in words() else self.command_shown(head)

    def word_on(self, word: str) -> bool:
        if _engines.word_hidden(word):                           # the engine runs on no server of the cluster: gone, whatever the edition
            return False
        if _modules.word_refusal(word) is not None:              # its module is off, or the function is off for the person: gone too
            return False
        if self.filtering is not self:
            return self.filtering.word_on(word)
        if not self.active:
            return True
        words = {str(k).upper(): v for k, v in (self._get("commands.words") or {}).items()}
        return _on(words.get(WORD_SWITCH.get(word.upper(), word.upper()))) is not False

    # -- features ----------------------------------------------------------------------------------------------------
    def feature(self, key: str) -> bool | None:
        """The distribution's preset of a feature (True/False), or None when it does not name it; a lens presets nothing."""
        if self.filtering is not self:
            return self.filtering.feature(key)
        return _on((self._get("features") or {}).get(key)) if self.active else None

    # -- menus, Home, help, Admin ------------------------------------------------------------------------------------
    def menu(self, group: str) -> list[str] | None:
        """The items (or, for ``top``, the menus) the file names for a menu, in order; None when it names none. A distribution's list is
        what that menu holds; a lens's list is only the order it puts first (:meth:`arranged`)."""
        items = (self._get("menus") or {}).get(group) if self.active else None
        return [str(i) for i in items] if isinstance(items, list) else None

    def menu_shown(self, group: str, item: str) -> bool:
        if self.filtering is not self:                             # a lens arranges a menu; it never takes an item out
            return self.filtering.menu_shown(group, item)
        listed = self.menu(group)
        return listed is None or item in listed

    def arranged(self, group: str, ids: list[str]) -> list[str]:
        """``ids`` in the order this lens puts first (its ``menus.<group>``: those it names, in its order, then the rest as they were);
        unchanged outside a lens. Nothing is left out."""
        named = self.menu(group) if self.lens else None
        if not named:
            return list(ids)
        first = [i for i in named if i in ids]
        return first + [i for i in ids if i not in first]

    @property
    def placeholder(self) -> str | None:
        return (self._get("menus.placeholder") or None) if self.active else None

    def home_section(self, section: str) -> bool:
        return not self.active or _on((self._get("home.sections") or {}).get(section)) is not False

    @property
    def home_cards(self) -> list | None:
        """Home's quick cards: the lens's (the registry's lens carries them, ``cards:``; the server says them per person), else a site's
        ``edition: {home: {cards: ...}}`` override."""
        if not self.active:
            return None
        cards = self._get("home.cards")
        if isinstance(cards, list):
            return cards
        return _modules.lens_cards(self.name)

    @property
    def home_chips(self) -> list:
        """Home's chips: the lens's (the registry's lens carries them, ``chips:``), else a site's ``edition: {home: {chips: ...}}``."""
        if not self.active:
            return []
        chips = self._get("home.chips")
        if isinstance(chips, list):
            return chips
        return _modules.lens_chips(self.name) or []

    def landings(self) -> list[str]:
        out = self._get("home.landings") if self.active else None
        return [str(x) for x in out] if isinstance(out, list) else []

    @property
    def landing(self) -> str:
        return str(self._get("home.landing") or "today") if self.active else "today"

    @property
    def landing_template(self) -> str:
        """The page that draws the edition's landings (``home.template``: the console's ``home/<name>_landing.html``, or a hosted module's
        ``<module>:<name>_landing.html``, e.g. ``economy:world_landing.html``; it names its own script and style). Anything else, or a file
        that names none (written before 2.9.7), keeps the Drishti Risk page."""
        t = str(self._get("home.template") or "") if self.active else ""
        return t if _LANDING_TEMPLATE.fullmatch(t) else DEFAULT_LANDING_TEMPLATE

    def landing_titles(self) -> dict[str, str]:
        """Each offered landing's title on Home's switch (``home.titles``); a landing the file does not title: the Drishti Risk titles
        of before, else its id."""
        named = self._get("home.titles") if self.active else None
        named = {str(k): str(v) for k, v in named.items()} if isinstance(named, dict) else {}
        return {x: named.get(x) or DEFAULT_LANDING_TITLES.get(x) or x.replace("-", " ").capitalize() for x in self.landings()}

    def landing_by_role(self, key: str = "home.landing-by-role") -> dict[str, str]:
        """``home.landing-by-role`` ({role: landing}): read, wired to role-based access later (LENSES.md). The same shape under
        another key: Drishti Trading's ``trading.landing-by-role``, for the person's trading role (LENSES.md › The design)."""
        m = self._get(key) if self.active else None
        return {str(k): str(v) for k, v in m.items()} if isinstance(m, dict) else {}

    def help_categories(self) -> list[str] | None:
        if self.filtering is not self:
            return self.filtering.help_categories()
        c = self._get("help.categories") if self.active else None
        return [str(x) for x in c] if isinstance(c, list) else None

    def knowledge_packs(self) -> list[str] | None:
        if self.filtering is not self:
            return self.filtering.knowledge_packs()
        c = self._get("help.knowledge") if self.active else None
        return [str(x) for x in c] if isinstance(c, list) else None

    @property
    def refuse_unlisted(self) -> bool:
        if self.filtering is not self:
            return self.filtering.refuse_unlisted
        return self.active and str(self._get("help.unlisted-slugs") or "").lower() == "refuse"

    def admin_page_shown(self, page: str) -> bool:
        if self.filtering is not self:
            return self.filtering.admin_page_shown(page)
        pages = self._get("admin.pages") if self.active else None
        return not isinstance(pages, list) or page in [str(p) for p in pages]

    def branding(self, key: str) -> str:
        if self.filtering is not self:                             # a lens never renames the product
            return self.filtering.branding(key)
        return str((self._get("branding") or {}).get(key) or "") if self.active else ""

    def about(self) -> dict | None:
        return {"name": self.name, "title": self.title, "lens": self.lens} if self.active else None

    def presets(self, mapping: dict[str, list[str]]) -> dict:
        """The console settings the distribution presets (a layer under the site's configuration): each named feature's console keys
        (``mapping``: kernel.yaml's ``features:``), ``ui.product`` and ``ui.tagline`` from its branding, Home's quick cards, and the raw
        ``console:`` block. A lens presets nothing."""
        out: dict = {}

        def put(dotted: str, value: Any) -> None:
            node = out
            parts = dotted.split(".")
            for p in parts[:-1]:
                node = node.setdefault(p, {})
            node[parts[-1]] = value

        for feature, value in (self._get("features") or {}).items():
            on = _on(value)
            for key in mapping.get(str(feature), []):
                put(key, bool(on))
        for k, to in (("product", "ui.product"), ("tagline", "ui.tagline")):
            if self.branding(k):
                put(to, self.branding(k))
        if self.home_cards is not None:
            put("ui.home_cards", self.home_cards)
        if self.placeholder:
            put("ui.placeholder", self.placeholder)
        _merge(out, copy.deepcopy(self._get("console") or {}))
        return out


Edition.NONE = Edition()


def by_module(packs) -> list[str]:
    """A pack list by module (``{core: [core], banking: [trading, ...]}``, in order) or plain, flattened; each pack once."""
    groups = packs.values() if isinstance(packs, dict) else [packs or []]
    out: list[str] = []
    for g in groups:
        for p in g or []:
            if str(p) not in out:
                out.append(str(p))
    return out


class Editions:
    """The console's editions: the instance's (learned from the server) and the lenses the site offers (``drishti.lenses`` on the server,
    learned the same way). One per console process; :meth:`for_request` says which one a request is seen through."""

    def __init__(self, settings, console_dir: Path, on_change=None):
        d = Path(str(settings.get("lens.dir", "../config/lenses") or "../config/lenses"))   # config/lenses/<module>/<lens>.yaml
        self.dir = d if d.is_absolute() else (console_dir / d).resolve()
        p = Path(str(settings.get("packs.dir", "../config/packs") or "../config/packs"))
        self.packs_dir = p if p.is_absolute() else (console_dir / p).resolve()
        md = Path(str(settings.get("module_manifests_dir", "../modules") or "../modules"))   # the modules' own manifests (DISTRIBUTIONS.md)
        self.modules_dir = md if md.is_absolute() else (console_dir / md).resolve()
        self.settings = settings
        self.instance: Edition = Edition.NONE
        self.lenses: dict[str, Edition] = {}
        self.on_change = on_change
        self._server_brand: dict = {}
        self.engines = _engines.new_state()                      # what runs, learned from this app's server as the edition is: its own,
                                                                 # never another app's of the same process (core/engines.py State)
        self.modules = _modules.new_state()                      # the modules loaded on the server, the same way (core/modules.py State)
        from core import distribution as _dist
        self.console_dir = console_dir
        self.own_distribution = _dist.selected(settings)[1]     # --distribution on this console: it decides, whatever the server runs
        self.distribution = _dist.builtin_all()
        self.server_modules = None                               # the server's module list (/public/modules/manifests); None: not yet
        self._manifests = None                                   # its fingerprint, from /public/about
        self._dist_name: str | None = None
        self._use_distribution(None)                             # Drishti All when none is named: nothing changes

    def default_packs(self) -> list[str]:
        """The one default pack list (config/packs/default-packs.yaml, by module), as the server reads it from its jar."""
        try:
            return by_module((yaml.safe_load((self.packs_dir / "default-packs.yaml").read_text(encoding="utf-8")) or {}).get("packs"))
        except (OSError, yaml.YAMLError):
            return []

    def mapping(self) -> dict[str, list[str]]:
        """Which console settings each feature presets: ``features:`` of kernel.yaml beside the distribution files, its ``console`` keys."""
        from core import distribution as _dist
        d = Path(str(self.settings.get("distributions_dir", "../config/distributions") or "../config/distributions"))
        f = (d if d.is_absolute() else (self.console_dir / d).resolve()) / _dist.KERNEL
        try:
            raw = (yaml.safe_load(f.read_text(encoding="utf-8")) or {}).get("features") or {}
        except (OSError, yaml.YAMLError):
            return {}
        return {str(k): [str(x) for x in (v or {}).get("console") or []] for k, v in raw.items()}

    def load(self, name: str) -> Edition:
        """A lens's file (``config/lenses/<module>/<lens>.yaml``), as a workspace: it arranges and never hides."""
        if not name or not name.replace("-", "").isalnum() or name != name.lower():
            return Edition.NONE
        f = self.dir / f"{name}.yaml"                        # a lens of no module, else in its module's folder (config/lenses/<module>/)
        if not f.is_file():
            f = next((d / f"{name}.yaml" for d in sorted(self.dir.iterdir()) if d.is_dir() and (d / f"{name}.yaml").is_file()), f) \
                if self.dir.is_dir() else f
        try:
            spec = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError) as e:
            LOG.warning("lens %s: cannot read %s (%s); it is not offered", name, f, e)
            return Edition.NONE
        return Edition(name, spec, lens=True)

    def learn(self, about: dict) -> bool:
        """What the server's about answer says: its edition, the lenses it offers, its product name and tagline (one branding source).
        True when anything changed (the settings layer and the pages follow at once)."""
        if not isinstance(about, dict):
            return False
        engines_changed = _engines.learn(about.get("engines"), self.engines)   # the engines running in the cluster (ADMIN_GUIDE.md 5.2)
        engines_changed = _modules.learn(about.get("modules"), self.modules) or engines_changed   # the modules loaded (ACCESS_MODEL.md)
        if self.own_distribution:                                   # this console's own --distribution: the server's edition and brand
            if engines_changed and self.on_change is not None:      # do not change it (a Finance console over a Drishti All cluster)
                self.on_change()
            return engines_changed
        name = str((about.get("edition") or {}).get("name") or "")
        if not name.startswith("drishti."):                         # only a distribution says what exists; a lens never filters
            name = ""
        lenses = [str(n) for n in about.get("lenses") or []]
        brand = {k: about.get(k) for k in ("product", "tagline") if about.get(k)}
        changed = name != self.instance.name or sorted(lenses) != sorted(self.lenses) or brand != self._server_brand
        if not changed:
            if engines_changed and self.on_change is not None:
                self.on_change()
            return engines_changed
        if name != self.instance.name:
            LOG.warning("the server runs %s: the console follows it", name or "(none: the full Drishti)")
        if name:                                                    # the server's distribution: the same file, read here
            self._use_distribution(name)
        else:
            self.instance = Edition.NONE
        outer = self.instance if self.instance.distribution else None
        self.lenses = {n: e for n, e in ((n, self._lens(n, outer)) for n in lenses) if e.active}
        self._server_brand = brand
        server_layer: dict = {}
        if brand and not self.settings.site("ui.product"):          # the server's branding, under the console's own site settings
            server_layer = {"ui": {k: v for k, v in brand.items()}}
        self.settings.layer("server", server_layer or None)
        self.settings.layer("edition", self.instance.presets(self.mapping()) if self.instance.active else None)
        if self.on_change is not None:
            self.on_change()
        return True

    async def follow_modules(self, about: dict, fetch) -> bool:
        """When the server's module registry changed (``about["manifests"]``), reads its module list (``await fetch()``: the answer of
        ``/public/modules/manifests``) and the distribution again: a ``.dmod`` installed after the start joins it, a removed one leaves.
        True when the distribution was read again; a list that does not read, or a distribution it refuses, keeps the last one."""
        fp = about.get("manifests") if isinstance(about, dict) else None
        if not fp or fp == self._manifests:
            return False
        from core import distribution as _dist
        try:
            known = _dist.entries(await fetch())
        except Exception as e:  # noqa: BLE001 - the server's list is asked again at its next change
            LOG.warning("the server's module list did not read (%s); the distribution stays as it is", e)
            return False
        self._manifests, before = fp, self.server_modules
        self.server_modules = known
        try:
            self._use_distribution(self._dist_name)
        except ValueError as e:
            LOG.warning("the distribution stays as it was: %s", e)
            self.server_modules = before
            return False
        if self.on_change is not None:
            self.on_change()
        return True

    @property
    def default_lens(self) -> str:
        """The distribution's starting workspace (``lenses.default``) when it is offered here; blank: none (Drishti All)."""
        d = self.distribution.default_lens
        return d if d in self.lenses else ""

    def for_request(self, lens: str | None) -> Edition:
        """The person's lens when the site offers it, else the distribution's starting one (a workspace inside the distribution: it
        arranges, never hides), else the distribution's filter itself."""
        return self.lenses.get(lens or self.default_lens, self.instance)

    # -- distributions (docs/architecture/DISTRIBUTIONS.md) --------------------------------------------------------------
    def _lens(self, name: str, outer: Edition | None) -> Edition:
        e = self.load(name)
        if e.active and outer is not None:
            e.outer = outer
        return e

    def _use_distribution(self, name: str | None) -> None:
        """Reads the distribution (``name``, else the console's own choice) and puts its brand layer and visibility filter in place."""
        from core import distribution as _dist
        # a listed module neither the server nor the folder knows (not installed yet, or removed) is left out with a warning
        d = _dist.load(self.settings, self.console_dir, name, self.server_modules, strict=False)
        self._dist_name = name
        self.distribution = d
        layer = d.settings_layer()
        if os.environ.get("DRISHTI_PRODUCT"):                       # the old variable, read for one release (Health says so)
            layer["ui"].pop("product", None)
        self.settings.layer("distribution", layer)
        self.instance = Edition(d.id, d.edition_spec) if not d.all else Edition.NONE
        if not d.all:
            outer = self.instance
            self.lenses = {n: e for n, e in ((n, self._lens(n, outer)) for n in d.lenses) if e.active}
        self.settings.layer("edition", self.instance.presets(self.mapping()) if self.instance.active else None)
