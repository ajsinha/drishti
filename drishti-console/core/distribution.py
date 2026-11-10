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

"""Distributions, the console side (docs/architecture/DISTRIBUTIONS.md; the kernel reads the same files the same way:
drishti-packs ``Distributions``).

A distribution file (``config/distributions/drishti.<name>.yaml``) is chosen by ``--distribution <name|path>`` (the console's
``distribution`` setting), ``DRISHTI_DISTRIBUTION``, else learned from the server; none named: Drishti All, so nothing changes. On a
console it decides the presentation: the brand and theme (a settings layer under the site's own), and a visibility filter in the edition
file's shape, so commands, menus, Help, AI tools and Home cards of modules outside it do not exist here. The modules it lists are read
from their own manifests (``modules/<id>/module.yaml``, a module jar's ``META-INF/drishti/module.yaml`` in its sources); the kernel's own
packs, words and menus from ``kernel.yaml`` beside the files.
"""
from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

LOG = logging.getLogger("drishti.console.distribution")
DEFAULT = "all"
KERNEL = "kernel.yaml"
_IN_JAR = "src/main/resources/META-INF/drishti/module.yaml"
_SHORT = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")
#: The brand keys and the console settings they fill (the old keys: ui.product, ui.copyright ... read once, with a Health note).
BRAND_KEYS = {"product": "ui.product", "product_native": "ui.product_native", "tagline": "ui.tagline", "owner": "ui.owner",
              "copyright": "ui.copyright", "notice": "ui.notice", "logo": "ui.logo", "favicon": "ui.favicon", "about": "ui.about",
              "headline": "ui.headline"}


def file_of(directory: Path, name_or_path: str | None) -> Path:
    """``finance`` or ``drishti.finance`` -> ``<dir>/drishti.finance.yaml``; a path (a slash, or ``.yaml``) as it is."""
    n = (name_or_path or DEFAULT).strip() or DEFAULT
    if "/" in n or "\\" in n or n.endswith((".yaml", ".yml")):
        return Path(n)
    short = n[len("drishti."):] if n.startswith("drishti.") else n
    if not _SHORT.fullmatch(short):
        raise ValueError(f"distribution '{n}' is not a distribution name (risk, finance, genomics, all) or a file path")
    return directory / f"drishti.{short}.yaml"


def _texts(v) -> list[str]:
    return [str(x).strip() for x in v if str(x).strip()] if isinstance(v, list) else []


def _read(f: Path) -> dict:
    data = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError(f"distribution file {f} is not a YAML mapping")
    return data


@dataclass
class ModuleEntry:
    """One module as a distribution sees it (drishti-packs ``ModuleManifests.Entry``)."""
    id: str
    domain: str
    packs: list[str]
    words: list[str]
    features: list[str]
    menus: list[str]
    docs: list[str]
    requires: list[str]


def _words(functions) -> list[str]:
    out = []
    for f in functions if isinstance(functions, list) else []:
        w = str((f.get("word") if isinstance(f, dict) else f) or "").strip().upper()
        if w:
            out.append(w)
    return out


def module_manifests(directory: Path | None) -> dict[str, ModuleEntry]:
    """Every module manifest under ``directory``, by id, in requirement order (a required module before the ones that need it): a
    content module's ``<id>/module.yaml`` (the ``.dmod`` manifest; a module project made from the SDK keeps it in ``<id>/src/dmod/``) and a module jar's ``<id>/<part>/src/main/resources/META-INF/drishti/
    module.yaml``. The kernel reads the same files the same way (drishti-packs ``ModuleManifests``)."""
    by_id: dict[str, ModuleEntry] = {}
    if directory is None or not directory.is_dir():
        return by_id
    for m in sorted(p for p in directory.iterdir() if p.is_dir()):
        # a content module's folder; a module project from the SDK (src/dmod/); a module with code's dmod/ (banking)
        for dmod in (m / "module.yaml", m / "src" / "dmod" / "module.yaml", m / "dmod" / "module.yaml"):
            if not dmod.is_file():
                continue
            y = _read(dmod)
            mid = str(y.get("id") or "").strip()
            req = [k for k in (y.get("requires") or {}) if k != "optional"] if isinstance(y.get("requires"), dict) else []
            by_id[mid] = ModuleEntry(mid, str(y.get("domain") or mid), _texts((y.get("packs") or {}).get("list")), _words(y.get("functions")),
                                     [str(e.get("id")) for e in ((y.get("compute") or {}).get("engines") or []) if isinstance(e, dict)],
                                     _texts((y.get("console") or {}).get("menus")), _texts((y.get("docs") or {}).get("domains")), req)
            break
        for part in sorted(p for p in m.iterdir() if p.is_dir()):
            jar = part / _IN_JAR
            if jar.is_file():
                y = _read(jar)
                mods = ([y["module"]] if isinstance(y.get("module"), dict) else []) + [x for x in y.get("modules") or [] if isinstance(x, dict)]
                for x in mods:
                    mid, c = str(x.get("id") or "").strip(), x.get("contributes") or {}
                    by_id[mid] = ModuleEntry(mid, str(x.get("domain") or mid), _texts(c.get("packs")), _words(c.get("functions")),
                                             _texts(c.get("engines")), _texts(c.get("menus")), _texts(c.get("help")), _texts(x.get("requires")))
    ordered: dict[str, ModuleEntry] = {}

    def visit(mid: str, path: set) -> None:
        e = by_id.get(mid)
        if e is None or mid in ordered or mid in path:
            return
        for r in e.requires:
            visit(r, path | {mid})
        ordered[mid] = e
    for mid in sorted(by_id):
        visit(mid, set())
    return ordered


def with_required(listed: list[str], known: dict[str, ModuleEntry]) -> list[str]:
    """The listed modules with every module they require, transitively."""
    out: list[str] = []
    todo = list(listed)
    while todo:
        mid = todo.pop(0)
        if mid not in out:
            out.append(mid)
            todo += known[mid].requires if mid in known else []
    return out


def pack_commands(packs_dir: Path, packs: list[str]) -> list[str]:
    """Every command of the packs: each pack's code and mnemonics (its pack.yaml under the packs folder)."""
    found: dict[str, Path] = {}
    if packs_dir.is_dir():
        for f in sorted(packs_dir.glob("*/*/pack.yaml")) + sorted(packs_dir.glob("*/pack.yaml")):
            found.setdefault(f.parent.name, f)
    out: list[str] = []
    for p in packs:
        f = found.get(p)
        if f is None:
            continue
        spec = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        for c in ([spec.get("code")] if spec.get("code") else []) + list((spec.get("mnemonics") or {}).keys()):
            c = str(c).strip().upper()
            if c and c not in out:
                out.append(c)
    return out


_KNOWN: dict[str, frozenset] = {}


def known_commands(packs_dir: Path, modules_dir: Path | None = None) -> frozenset:
    """Every command and word any module has (all packs' commands, the module manifests' words): what a distribution may leave out, so a
    command typed in one that lacks it answers "not in Drishti Finance" instead of becoming a search."""
    modules_dir = modules_dir or packs_dir.parent.parent / "modules"
    key = f"{packs_dir}|{modules_dir}"
    if key not in _KNOWN:
        names = [f.parent.name for f in sorted(packs_dir.glob("*/*/pack.yaml"))] if packs_dir.is_dir() else []
        words = {w for m in module_manifests(modules_dir).values() for w in m.words}
        _KNOWN[key] = frozenset(pack_commands(packs_dir, names)) | frozenset(words)
    return _KNOWN[key]


@dataclass
class Distribution:
    id: str
    title: str
    file: Path | None
    all: bool
    spec: dict
    modules: list[str] = field(default_factory=list)
    domains: dict[str, str] = field(default_factory=dict)
    packs: list[str] = field(default_factory=list)
    words_off: list[str] = field(default_factory=list)
    features_off: list[str] = field(default_factory=list)
    edition_spec: dict | None = None

    @property
    def name(self) -> str:
        return self.id[len("drishti."):] if self.id.startswith("drishti.") else self.id

    @property
    def brand(self) -> dict:
        b = self.spec.get("brand") or {}
        return {k: v for k, v in b.items() if isinstance(v, (str, int, float)) and str(v).strip()}

    @property
    def lenses(self) -> list[str]:
        return _texts((self.spec.get("lenses") or {}).get("offered"))

    @property
    def default_lens(self) -> str:
        return str((self.spec.get("lenses") or {}).get("default") or "")

    @property
    def theme(self) -> dict:
        return self.spec.get("theme") or {}

    @property
    def help_title(self) -> str:
        return str((self.spec.get("docs") or {}).get("title") or f"{self.title} Help")

    def settings_layer(self) -> dict:
        """The console settings the file presets (under the site's own): the brand, the default theme and density, Help's title."""
        ui: dict[str, Any] = {}
        for k, key in BRAND_KEYS.items():
            v = self.brand.get(k)
            if v is not None:
                ui[key.split(".", 1)[1]] = v
        support = (self.spec.get("brand") or {}).get("support") or {}
        if isinstance(support, dict) and (support.get("email") or support.get("url")):
            ui["support"] = {"email": str(support.get("email") or ""), "url": str(support.get("url") or "")}
        if self.theme.get("tokens"):
            ui["default_theme"] = str(self.theme["tokens"])
        if self.theme.get("density"):
            ui["default_density"] = str(self.theme["density"])
        if self.theme.get("mode"):
            ui["default_mode"] = str(self.theme["mode"])
        if not self.all:                                         # Drishti All keeps Help as it was
            ui["help_title"] = self.help_title
        return {"ui": ui}

    def about(self) -> dict:
        return {"id": self.id, "name": self.name, "title": self.title, "all": self.all, "modules": ["all"] if self.all else self.modules,
                "file": str(self.file or ""), "theme": self.theme.get("tokens", ""), "density": self.theme.get("density", ""),
                "mode": self.theme.get("mode", ""), "lenses": self.lenses, "help": self.help_title}


def builtin_all() -> Distribution:
    return Distribution("drishti.all", "", None, True, {"distribution": "drishti.all"})   # no title: the product name from ui.product


def read(f: Path, kernel_file: Path, modules_dir: Path | None, packs_dir: Path) -> Distribution:
    """A distribution file, compiled against the kernel's file, the module manifests and the packs folder (as the kernel's
    Distributions.read)."""
    if not f.is_file():
        raise ValueError(f"no distribution file {f.resolve()} (name one of: risk, finance, genomics, all)")
    spec = _read(f)
    did = str(spec.get("distribution") or "").strip()
    if not did:
        raise ValueError(f"distribution file {f} has no 'distribution:' id (drishti.<name>)")
    title = str((spec.get("brand") or {}).get("product") or did)
    if spec.get("modules") == "all":
        return Distribution(did, title, f, True, spec)
    known = module_manifests(modules_dir)
    listed = []
    for m in spec.get("modules") or []:
        mid = str(m.get("id") if isinstance(m, dict) else m).strip()
        if mid and (not isinstance(m, dict) or m.get("enabled", True) is not False):
            listed.append(mid)
            if mid not in known and (not isinstance(m, dict) or m.get("required", True) is not False):
                raise ValueError(f"distribution {did} requires the module '{mid}', which has no manifest under {modules_dir} "
                                 "(modules/<id>/module.yaml)")
    modules = with_required(listed, known)                       # a listed module brings the ones it requires
    kernel = (_read(kernel_file).get("kernel") or {}) if kernel_file.is_file() else {}
    # a distribution may name the kernel's packs it takes (``kernel-packs:``; Drishti Genomics takes none: no counterparty or book anywhere)
    packs = list(_texts(spec.get("kernel-packs") if "kernel-packs" in spec else kernel.get("packs")))
    menus = list(_texts(kernel.get("menus")))
    domains: dict[str, str] = {}
    words_on = set(_texts(kernel.get("words")))
    for m, mod in known.items():                                  # requirement order: market-data before the packs that extend it
        if m in modules:
            packs += [p for p in mod.packs if p not in packs]
            menus += [x for x in mod.menus if x not in menus]
            domains[m] = mod.domain
            words_on |= set(mod.words)
    words_off, features_off = [], []
    for m, mod in known.items():
        if m not in modules:
            words_off += [w for w in mod.words if w not in words_on and w not in words_off]
            features_off += [x for x in mod.features if x not in features_off]
    words = {w: "off" for w in words_off}
    words.update({str(k).upper(): v for k, v in ((spec.get("commands") or {}).get("words") or {}).items()})
    features = {x: "off" for x in features_off}
    features.update(spec.get("features") or {})
    top = _texts((spec.get("menus") or {}).get("top"))
    ed: dict[str, Any] = {
        "edition": did, "title": title, "distribution": True,
        "packs": {"enabled": packs} if packs else {"none": True},   # a distribution of no packs (Genomics) loads none
        "commands": {"allow": pack_commands(packs_dir, packs) + [w for m in modules if m in known for w in known[m].words
                                                                if w not in pack_commands(packs_dir, packs)], "words": words},
        "features": features,
        "menus": {"top": [x for x in top if x in menus] if top else menus},
        "help": {"knowledge": packs, "domains": sorted({x for m, mod in known.items() if m in modules for x in mod.docs}),
                 "title": str((spec.get("docs") or {}).get("title") or f"{title} Help")},
        "branding": {k: (spec.get("brand") or {}).get(k) for k in ("product", "tagline") if (spec.get("brand") or {}).get(k)},
    }
    if (spec.get("menus") or {}).get("placeholder"):
        ed["menus"]["placeholder"] = spec["menus"]["placeholder"]
    if isinstance(spec.get("home"), dict):
        ed["home"] = dict(spec["home"])
    if isinstance((spec.get("docs") or {}).get("categories"), list):
        ed["help"]["categories"] = list(spec["docs"]["categories"])
    return Distribution(did, title, f, False, spec, modules, domains, packs, words_off, features_off, ed)


def selected(settings) -> tuple[str, bool]:
    """The console's own choice (``--distribution=``, ``distribution:``, DRISHTI_DISTRIBUTION) and whether one was named at all."""
    v = str(settings.get("distribution", "") or "").strip() or os.environ.get("DRISHTI_DISTRIBUTION", "").strip()
    return (v or DEFAULT), bool(v)


def load(settings, console_dir: Path, name: str | None = None) -> Distribution:
    """The distribution ``name`` (else the console's own choice); Drishti All built in when no file exists and none was named."""
    d = Path(str(settings.get("distributions_dir", "../config/distributions") or "../config/distributions"))
    directory = d if d.is_absolute() else (console_dir / d).resolve()
    p = Path(str(settings.get("packs.dir", "../config/packs") or "../config/packs"))
    packs_dir = p if p.is_absolute() else (console_dir / p).resolve()
    md = Path(str(settings.get("module_manifests_dir", "../modules") or "../modules"))
    modules_dir = md if md.is_absolute() else (console_dir / md).resolve()
    chosen, named = (name, True) if name else selected(settings)
    f = file_of(directory, chosen)
    if not f.is_absolute():
        f = (console_dir / f).resolve() if not f.is_file() else f.resolve()
    if not f.is_file() and not named:
        return builtin_all()
    return read(f, directory / KERNEL, modules_dir, packs_dir)


def deprecated(settings) -> list[str]:
    """The old product keys the site still sets on this console (read for one release): Health names each one."""
    out = []
    for key in ("ui.product", "ui.tagline", "ui.copyright", "ui.owner", "ui.notice", "edition"):
        if settings.site(key) not in (None, "", {}):
            out.append(f"{key} is read for one release: set brand: in the distribution file instead" if key != "edition"
                       else "edition is read for one release: name a distribution instead (--distribution, DRISHTI_DISTRIBUTION)")
    if os.environ.get("DRISHTI_PRODUCT"):
        out.append("DRISHTI_PRODUCT is read for one release: set brand.product in the distribution file instead")
    return out
