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

"""Editions and lenses, the console side (docs/architecture/EDITIONS.md).

An *edition* is one setting on the servers (``drishti.edition``) naming ``config/editions/<name>.yaml``; the console learns the name from
the server's about answer (:meth:`Editions.learn`), reads the same file and puts its presets as a layer under the site's own configuration
(:meth:`core.config.Settings.layer`), so the site still wins key by key. A *lens* is the same file opened by one person on a full instance
("Open Drishti Risk"): kept in that person's settings (``lens``), it changes only what they see, never what they may read. A lens hides;
it does not secure: data access stays with roles, entitlements and masks.

:class:`Edition` answers every "is this shown?" question; :data:`Edition.NONE` (the full Drishti) answers yes to all of them, so a console
without an edition behaves exactly as before editions existed.
"""
from __future__ import annotations

import contextvars
import copy
import logging
import re
from pathlib import Path
from typing import Any

import yaml

LOG = logging.getLogger("drishti.console.edition")
LENS_HEADER = "X-Drishti-Lens"                       # the server's EditionContext.HEADER: the person's lens, on every backend call
_lens: contextvars.ContextVar[str | None] = contextvars.ContextVar("drishti_lens", default=None)

WORDS = ("BI", "RUPAKA", "FORM", "FUNC", "WHAT", "DATAMAP", "VAR", "EXP")

# The console pages each reserved word, console word and feature opens: hidden ones answer 404 (core/edition_gate.py).
WORD_PATHS = {"BI": ("/bi",), "FORM": ("/forms", "/form/"), "FUNC": ("/func",), "WHAT": ("/what",), "DATAMAP": ("/datamap",),
              "VAR": ("/var", "/api/var/"), "EXP": ("/exposure", "/api/exposure/")}
FEATURE_PATHS = {"bi": ("/bi", "/admin/bi"), "forms": ("/forms", "/form/"), "datamap": ("/datamap",), "workspaces": ("/w",),
                 "monitors": ("/m",), "alerts": ("/alerts",), "build": ("/build", "/review/"), "calc": ("/calc",), "pyconsole": ("/pyconsole",),
                 "studio": ("/studio",), "reports": ("/reports",), "lakes": ("/admin/lakes", "/admin/api/lakes"),
                 "collaboration": ("/admin/collab",)}
# (Admin -> AI and Admin -> Embedding stay reachable with their feature off, as before: they are where it is switched on; an edition
# hides them through admin.pages.)
# Admin pages by id (the edition's admin.pages); the path prefix each owns
ADMIN_PAGES = ("users", "roles", "packs", "commands", "tokens", "access", "connectors", "artifacts", "ai", "loads", "lakes", "collab",
               "embedding", "bi", "audit", "net", "edits", "compute", "health", "caches")


# The landings' page (EDITIONS.md section 7): each edition names its own (home.template, home.titles); a file that names none keeps
# Drishti Risk's, as before 2.9.7. Only templates under home/ named *_landing.html are taken.
_LANDING_TEMPLATE = re.compile(r"home/[a-z0-9_]+_landing\.html")
DEFAULT_LANDING_TEMPLATE = "home/risk_landing.html"
DEFAULT_LANDING_TITLES = {"desk": "Risk desk", "command": "Command first", "map": "Risk map"}


def set_lens(name: str | None) -> None:
    _lens.set(name or None)


def current_lens() -> str | None:
    """The lens this request is seen through (set per request by the console's middleware), or None."""
    return _lens.get()


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
    """One edition (or lens) as the console reads it: the file's keys, with the site's ``edition:`` overrides for the instance's own."""

    def __init__(self, name: str = "", spec: dict | None = None, lens: bool = False):
        self.name = (name or "").strip()
        self.spec = spec or {}
        self.lens = lens

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
        allow = self._get("commands.allow") or []
        allow = list(allow) + list(self._get("commands.also-allow") or [])
        return {str(c).strip().upper() for c in allow if str(c).strip()}

    def command_shown(self, code: str | None) -> bool:
        allow = self.commands() if self.active else set()
        return not allow or not code or code.strip().upper() in allow

    def typed_shown(self, text: str) -> bool:
        """Whether a command as typed (``VAR BANK``, ``VRES DESK:RATES``) is offered: a reserved or console word that is on, or a shown
        command (EXP is both: the word decides)."""
        head = str(text or "").split()[0].upper() if str(text or "").split() else ""
        return self.word_on(head) if head in WORDS else self.command_shown(head)

    def word_on(self, word: str) -> bool:
        if not self.active:
            return True
        words = {str(k).upper(): v for k, v in (self._get("commands.words") or {}).items()}
        return _on(words.get(word.upper())) is not False

    # -- features ----------------------------------------------------------------------------------------------------
    def feature(self, key: str) -> bool | None:
        """The edition's preset of a feature (True/False), or None when it does not name it."""
        return _on((self._get("features") or {}).get(key)) if self.active else None

    # -- menus, Home, help, Admin ------------------------------------------------------------------------------------
    def menu(self, group: str) -> list[str] | None:
        items = (self._get("menus") or {}).get(group) if self.active else None
        return [str(i) for i in items] if isinstance(items, list) else None

    def menu_shown(self, group: str, item: str) -> bool:
        listed = self.menu(group)
        return listed is None or item in listed

    @property
    def placeholder(self) -> str | None:
        return (self._get("menus.placeholder") or None) if self.active else None

    def home_section(self, section: str) -> bool:
        return not self.active or _on((self._get("home.sections") or {}).get(section)) is not False

    @property
    def home_cards(self) -> list | None:
        cards = self._get("home.cards") if self.active else None
        return cards if isinstance(cards, list) else None

    def landings(self) -> list[str]:
        out = self._get("home.landings") if self.active else None
        return [str(x) for x in out] if isinstance(out, list) else []

    @property
    def landing(self) -> str:
        return str(self._get("home.landing") or "today") if self.active else "today"

    @property
    def landing_template(self) -> str:
        """The page that draws the edition's landings (``home.template``, e.g. ``home/world_landing.html``; it names its own script and
        style). Only a ``home/<name>_landing.html`` template is taken; a file that names none (written before 2.9.7) keeps the Drishti
        Risk page."""
        t = str(self._get("home.template") or "") if self.active else ""
        return t if _LANDING_TEMPLATE.fullmatch(t) else DEFAULT_LANDING_TEMPLATE

    def landing_titles(self) -> dict[str, str]:
        """Each offered landing's title on Home's switch (``home.titles``); a landing the file does not title: the Drishti Risk titles
        of before, else its id."""
        named = self._get("home.titles") if self.active else None
        named = {str(k): str(v) for k, v in named.items()} if isinstance(named, dict) else {}
        return {x: named.get(x) or DEFAULT_LANDING_TITLES.get(x) or x.replace("-", " ").capitalize() for x in self.landings()}

    def landing_by_role(self) -> dict[str, str]:
        """``home.landing-by-role`` ({role: landing}): read, wired to role-based access later (EDITIONS_GUIDE.md)."""
        m = self._get("home.landing-by-role") if self.active else None
        return {str(k): str(v) for k, v in m.items()} if isinstance(m, dict) else {}

    def help_categories(self) -> list[str] | None:
        c = self._get("help.categories") if self.active else None
        return [str(x) for x in c] if isinstance(c, list) else None

    def knowledge_packs(self) -> list[str] | None:
        c = self._get("help.knowledge") if self.active else None
        return [str(x) for x in c] if isinstance(c, list) else None

    @property
    def refuse_unlisted(self) -> bool:
        return self.active and str(self._get("help.unlisted-slugs") or "").lower() == "refuse"

    def admin_page_shown(self, page: str) -> bool:
        pages = self._get("admin.pages") if self.active else None
        return not isinstance(pages, list) or page in [str(p) for p in pages]

    def branding(self, key: str) -> str:
        return str((self._get("branding") or {}).get(key) or "") if self.active else ""

    def about(self) -> dict | None:
        return {"name": self.name, "title": self.title, "lens": self.lens} if self.active else None

    def presets(self, mapping: dict[str, list[str]]) -> dict:
        """The console settings the edition presets (a layer under the site's configuration): each named feature's console keys,
        ``ui.product`` and ``ui.tagline`` from its branding, Home's quick cards, and the file's raw ``console:`` block."""
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


class Editions:
    """The console's editions: the instance's (learned from the server) and the lenses the site offers (``drishti.lenses`` on the server,
    learned the same way). One per console process; :meth:`for_request` says which one a request is seen through."""

    def __init__(self, settings, console_dir: Path, on_change=None):
        d = Path(str(settings.get("editions.dir", "../config/editions") or "../config/editions"))
        self.dir = d if d.is_absolute() else (console_dir / d).resolve()
        self.settings = settings
        self.instance: Edition = Edition.NONE
        self.lenses: dict[str, Edition] = {}
        self.on_change = on_change
        self._server_brand: dict = {}

    def default_packs(self) -> list[str]:
        """The one default pack list (config/editions/default-packs.yaml), as the server reads it from its jar."""
        try:
            return [str(p) for p in (yaml.safe_load((self.dir / "default-packs.yaml").read_text(encoding="utf-8")) or {}).get("packs") or []]
        except (OSError, yaml.YAMLError):
            return []

    def mapping(self) -> dict[str, list[str]]:
        """Which console settings each feature presets (config/editions/features.yaml, its ``console`` keys)."""
        try:
            raw = (yaml.safe_load((self.dir / "features.yaml").read_text(encoding="utf-8")) or {}).get("features") or {}
        except (OSError, yaml.YAMLError):
            return {}
        return {str(k): [str(x) for x in (v or {}).get("console") or []] for k, v in raw.items()}

    def load(self, name: str, lens: bool = False) -> Edition:
        """The edition file; the instance's own gets the site's ``edition:`` overrides (application.local.yaml, environment)."""
        if not name or not name.replace("-", "").isalnum() or name != name.lower():
            return Edition.NONE
        f = self.dir / f"{name}.yaml"
        try:
            spec = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError) as e:
            LOG.warning("edition %s: cannot read %s (%s); the console shows the full Drishti", name, f, e)
            return Edition.NONE
        if not lens:
            _merge(spec, copy.deepcopy(self.settings.site("edition") or {}))
        return Edition(name, spec, lens)

    def learn(self, about: dict) -> bool:
        """What the server's about answer says: its edition, the lenses it offers, its product name and tagline (one branding source).
        True when anything changed (the settings layer and the pages follow at once)."""
        if not isinstance(about, dict):
            return False
        name = str((about.get("edition") or {}).get("name") or "")
        lenses = [str(n) for n in about.get("lenses") or []] if not name else []
        brand = {k: about.get(k) for k in ("product", "tagline") if about.get(k)}
        changed = name != self.instance.name or sorted(lenses) != sorted(self.lenses) or brand != self._server_brand
        if not changed:
            return False
        if name != self.instance.name:
            LOG.warning("the server runs the edition %s: the console follows it", name or "(none: the full Drishti)")
        self.instance = self.load(name) if name else Edition.NONE
        self.lenses = {n: e for n, e in ((n, self.load(n, lens=True)) for n in lenses) if e.active}
        self._server_brand = brand
        server_layer: dict = {}
        if brand and not self.settings.site("ui.product"):          # the server's branding, under the console's own site settings
            server_layer = {"ui": {k: v for k, v in brand.items()}}
        self.settings.layer("server", server_layer or None)
        self.settings.layer("edition", self.instance.presets(self.mapping()) if self.instance.active else None)
        if self.on_change is not None:
            self.on_change()
        return True

    def for_request(self, lens: str | None) -> Edition:
        """The instance's edition; on a full instance, the person's lens when the site offers it; else the full Drishti."""
        if self.instance.active:
            return self.instance
        return self.lenses.get(lens or "", Edition.NONE)
