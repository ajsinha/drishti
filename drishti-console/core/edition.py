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

What exists is the *distribution's*, compiled by the server and sent in its installation answer (THIN_CONSOLE_DESIGN.md 3.2): its filter
is the instance's :class:`Edition`, and its feature presets are a layer under the site's own configuration (:meth:`core.config.Settings.layer`),
so the site still wins key by key. A *lens* ("Open Drishti Risk", its arrangement in the same answer) is a workspace one person works in, kept in
their settings (``lens``): it arranges (the starting Home, menu emphasis, default screens) and never hides anything; every "is this
shown?" question inside a lens is the distribution's answer.

:class:`Edition` answers every "is this shown?" question; :data:`Edition.NONE` (Drishti All) answers yes to all of them.
"""
from __future__ import annotations

import contextvars
import logging
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

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
# (Admin -> Embedding stays reachable with its feature off, as before: it is where it is switched on.)
# The Admin pages by id are the server's: the person's answer carries the ones their roles open (``admin-pages``, core/packs.py
# admin_pages); a filter's admin.pages keeps some of them.


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


@dataclass(frozen=True)
class Distribution:
    """The running distribution as the server compiled it (the ``distribution`` of its installation answer, THIN_CONSOLE_DESIGN.md 3.2): a
    view for drawing, nothing worked out here."""
    doc: dict

    @property
    def id(self) -> str:
        return str(self.doc.get("id") or "drishti.all")

    @property
    def name(self) -> str:
        return str(self.doc.get("name") or (self.id[len("drishti."):] if self.id.startswith("drishti.") else self.id))

    @property
    def title(self) -> str:
        return str(self.doc.get("title") or "")

    @property
    def all(self) -> bool:
        return bool(self.doc.get("all", True))

    @property
    def modules(self) -> list[str]:
        return [str(m) for m in self.doc.get("modules") or []]

    @property
    def domains(self) -> dict[str, str]:
        return {str(k): str(v) for k, v in (self.doc.get("domains") or {}).items()}

    @property
    def packs(self) -> list[str]:
        return [str(p) for p in self.doc.get("packs") or []]

    @property
    def brand(self) -> dict:
        return dict(self.doc.get("brand") or {})

    @property
    def theme(self) -> dict:
        return dict(self.doc.get("theme") or {})

    @property
    def lenses(self) -> list[str]:
        return [str(x) for x in (self.doc.get("lenses") or {}).get("offered") or []]

    @property
    def default_lens(self) -> str:
        return str((self.doc.get("lenses") or {}).get("default") or "")

    @property
    def help_title(self) -> str:
        return str(self.doc.get("help-title") or "")

    @property
    def filter(self) -> dict | None:
        f = self.doc.get("filter")
        return f if isinstance(f, dict) else None

    @property
    def home_cards(self) -> list:
        return list(((self.filter or {}).get("home") or {}).get("cards") or [])

    def about(self) -> dict:
        return {"id": self.id, "name": self.name, "title": self.title, "all": self.all, "modules": ["all"] if self.all else self.modules,
                "theme": self.theme.get("tokens", ""), "density": self.theme.get("density", ""), "mode": self.theme.get("mode", ""),
                "lenses": self.lenses, "help": self.help_title}


Distribution.ALL = Distribution({"id": "drishti.all", "name": "all", "title": "", "all": True})


def selected(settings) -> tuple[str, bool]:
    """The console's own choice of distribution (``--distribution=``, ``distribution:``, DRISHTI_DISTRIBUTION; a start-up setting) and
    whether one was named at all. The server compiles it (``GET /api/v1/console/bootstrap?distribution=``, Q3); this console reads no file."""
    v = str(settings.get("distribution", "") or "").strip() or os.environ.get("DRISHTI_DISTRIBUTION", "").strip()
    return (v or "all"), bool(v)


def deprecated(settings) -> list[str]:
    """The old keys the site still sets on this console (Health names each one): the product's name and notices are not read from them."""
    out = []
    for key in ("ui.product", "ui.tagline", "ui.copyright", "ui.owner", "ui.notice"):
        if settings.site(key) not in (None, "", {}):
            out.append(f"{key} is no longer read: set brand: in the distribution file")
    if settings.site("edition") not in (None, "", {}):
        out.append("edition is read for one release: name a distribution instead (--distribution, DRISHTI_DISTRIBUTION)")
    return out


class Editions:
    """The console's editions: the instance's filter and the lenses offered, as the server's installation answer says them
    (THIN_CONSOLE_DESIGN.md 3.2, step S2: the server compiles the distribution and reads the lens files; this console reads none). One per
    console process; :meth:`for_request` says which one a request is seen through. Before the first answer: Drishti All, no lens."""

    def __init__(self, settings, console_dir: Path, on_change=None):
        self.settings = settings
        self.console_dir = console_dir
        self.instance: Edition = Edition.NONE
        self.lenses: dict[str, Edition] = {}
        self.on_change = on_change
        self._server_brand: dict = {}
        self.engines = _engines.new_state()                      # what runs, learned from this app's server as the edition is: its own,
                                                                 # never another app's of the same process (core/engines.py State)
        self.modules = _modules.new_state()                      # the modules loaded on the server, the same way (core/modules.py State)
        self.own_name, self.own_distribution = selected(settings)   # --distribution on this console: the server compiles it (Q3)
        self.distribution = Distribution.ALL
        self.server_modules: dict[str, dict] | None = None       # the installation's modules (id -> its row), in requirement order; None: not yet
        self.answer: dict | None = None                          # the installation's answer this view was made from
        from core import under_construction as _uc
        self.under_construction = _uc.Registry([])               # the server's under-construction registry (S3); none before it answers
        self._digest: str | None = None

    def mapping(self) -> dict[str, list[str]]:
        """Which console settings each feature presets (``feature-keys`` of the installation's answer, from the server's feature mapping)."""
        keys = self.distribution.doc.get("feature-keys") or {}
        return {str(k): [str(x) for x in v or []] for k, v in keys.items()} if isinstance(keys, dict) else {}

    def known_commands(self) -> frozenset:
        """Every command and word any module or pack of the installation has (the answer's ``commands`` and each module's ``words``): what a
        distribution may leave out, so a command typed in one that lacks it answers "not in Drishti Finance" instead of becoming a search."""
        if not self.answer:
            return frozenset()
        words = {str(w).upper() for m in self.answer.get("modules") or [] if isinstance(m, dict) for w in m.get("words") or []}
        return frozenset(str(c).upper() for c in (self.answer.get("commands") or {})) | frozenset(words)

    def learn(self, about: dict) -> bool:
        """What the server's about answer says beside the installation's answer: the engines running, the modules loaded, its product name
        and tagline (one branding source). True when anything changed (the settings layer and the pages follow at once)."""
        if not isinstance(about, dict):
            return False
        changed = _engines.learn(about.get("engines"), self.engines)       # the engines running in the cluster (ADMIN_GUIDE.md 5.2)
        changed = _modules.learn(about.get("modules"), self.modules) or changed   # the modules loaded (ACCESS_MODEL.md)
        brand = {} if self.own_distribution else {k: about.get(k) for k in ("product", "tagline") if about.get(k)}
        if brand != self._server_brand:
            self._server_brand = brand
            self.settings.pin("server", {"ui": dict(brand)} if brand else None)
            changed = True
        if changed and self.on_change is not None:
            self.on_change()
        return changed

    def follow(self, answer: dict | None) -> bool:
        """The server's installation answer (``GET /api/v1/console/bootstrap``): the compiled distribution, its settings layers, the lenses
        offered with their arrangement, the modules. Swapped whole; True when it changed (the pages follow at once)."""
        if not isinstance(answer, dict) or answer.get("digest") == self._digest:
            return False
        d = Distribution(dict(answer.get("distribution") or {}))
        self.answer, self._digest = answer, answer.get("digest")
        self.distribution = d
        self.server_modules = {str(m["id"]): m for m in answer.get("modules") or [] if isinstance(m, dict) and m.get("id")}
        _modules.take(answer.get("modules"), self.modules)       # each module's words and engines: the plugin.yaml keeps no copy
        _engines.take(answer.get("engines"), self.engines)       # the engine catalogue: the console keeps no copy (S3)
        from core import under_construction as _uc
        self.under_construction = _uc.Registry([i for i in answer.get("under-construction") or [] if isinstance(i, dict)])
        name = d.id if not d.all and d.filter is not None else ""
        if name != self.instance.name:
            LOG.warning("the server runs %s: the console follows it", name or "(none: the full Drishti)")
        self.instance = Edition(name, d.filter) if name else Edition.NONE
        outer = self.instance if self.instance.distribution else None
        specs = (d.doc.get("lenses") or {}).get("specs") or {}
        self.lenses = {n: Edition(n, specs[n], lens=True, outer=outer) for n in d.lenses if isinstance(specs.get(n), dict)}
        settings = d.doc.get("settings") or {}
        self.settings.layer("distribution", settings.get("layer") or None)
        self.settings.pin("distribution", settings.get("pinned") or None)   # the names and notices: the distribution file only
        self.settings.layer("edition", settings.get("presets") or None)
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
