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


"""The server's recorded installation answers for the console's unit tests (docs/architecture/THIN_CONSOLE_DESIGN.md 5.2, "Recorded
answers"): ``tests/answers/bootstrap-<distribution>.json``, written by the server's own code (ConsoleAnswersRecordTest, which fails when one
is stale). One direction only: the server makes them, the console reads them; nothing here works a rule out."""
from __future__ import annotations

import copy
import hashlib
import io
import json
import zipfile
from functools import lru_cache
from pathlib import Path

ANSWERS = Path(__file__).resolve().parent / "answers"


@lru_cache(maxsize=8)
def _recorded(name: str) -> str:
    return (ANSWERS / f"bootstrap-{name}.json").read_text(encoding="utf-8")


@lru_cache(maxsize=1)
def _admin_pages() -> tuple:
    return tuple(json.loads((ANSWERS / "admin-pages.json").read_text(encoding="utf-8"))["admin-pages"])


def admin_pages() -> list[str]:
    """The Admin pages the server's person answer lists for an administrator (``admin-pages``; recorded by ConsoleAnswersRecordTest)."""
    return list(_admin_pages())


def bootstrap(name: str = "all", lenses=None) -> dict:
    """The installation's answer of distribution ``name`` (``all``, ``risk``, ``finance``, ``genomics``). ``lenses``: the lenses the server
    offers (its ``drishti.lenses``), else the distribution's own; each with the arrangement the server read from its file."""
    doc = json.loads(_recorded(name))
    d = doc["distribution"]
    specs = d["lenses"]["specs"]
    offered = [str(x) for x in (lenses if lenses is not None else d["lenses"]["offered"])]
    d["lenses"] = {"default": d["lenses"].get("default") if d["lenses"].get("default") in offered else "",
                   "offered": offered, "specs": {n: specs[n] for n in offered if n in specs}}
    doc["digest"] = hashlib.sha256(json.dumps(doc, sort_keys=True).encode()).hexdigest()[:16]
    return doc


def follow(app, about: dict) -> bool:
    """What the console does when its server answers: learn ``about`` (engines, modules, brand), then follow the installation answer of
    the distribution ``about`` names, offering the lenses ``about`` lists. True when anything changed."""
    eds = app.state.editions
    name = str(((about or {}).get("edition") or {}).get("name") or "")
    short = name[len("drishti."):] if name.startswith("drishti.") else "all"     # only a distribution says what exists; a lens's name is none
    changed = eds.learn(about)
    doc = bootstrap(short if (ANSWERS / f"bootstrap-{short}.json").is_file() else "all", (about or {}).get("lenses") or [])
    return eds.follow(doc) or changed


def with_modules(doc: dict, keep) -> dict:
    """The answer with only the modules ``keep(id)`` holds (a module not installed on the server is not in its answer, nor are its packs'
    command words)."""
    out = copy.deepcopy(doc)
    out["modules"] = [m for m in out["modules"] if keep(m["id"])]
    out["commands"] = {w: c for w, c in (out.get("commands") or {}).items() if not c.get("module") or keep(c["module"])}
    out["digest"] = hashlib.sha256(json.dumps(out, sort_keys=True).encode()).hexdigest()[:16]
    return out


def module_zip(folder: Path) -> bytes:
    """A module's console part as the server hands it over (``/public/modules/console/<id>/<version>``): module.yaml, console/,
    elements/ and docs/ of its folder."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for part in ("module.yaml", "console", "elements", "docs"):
            p = folder / part
            files = [p] if p.is_file() else sorted(f for f in p.rglob("*") if f.is_file()) if p.is_dir() else []
            for f in files:
                z.write(f, f.relative_to(folder).as_posix())
    return buf.getvalue()


@lru_cache(maxsize=1)
def _shipped() -> dict:
    """The product's own content modules shipped as folders with a console part and no code (``modules/<id>/module.yaml`` without a
    ``java:`` block, with ``console/plugin.yaml``): id -> (folder, version, title), as the server's module list (ModulePublicController) reads them."""
    import yaml

    out = {}
    import resolved                                         # the Java resolver says where every module is (tools/resolved.py)

    for f in sorted(m.home / "module.yaml" for m in resolved.repository().modules.values()):
        manifest = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        if manifest.get("java") is not None or not (f.parent / "console" / "plugin.yaml").is_file():
            continue
        mid = str(manifest.get("id") or f.parent.name)
        out[mid] = (f.parent, str(manifest.get("version") or "0"), str(manifest.get("title") or mid))
    return out


def shipped_listing(loaded) -> dict:
    """``GET /public/modules/console`` of a server with no module deployed: the shipped folder modules it loaded (``loaded``: the modules its
    about answer lists; None, every one), each active with its console part."""
    rows = [{"id": mid, "version": ver, "state": "active", "title": title, "console": True, "claims": []}
            for mid, (_, ver, title) in _shipped().items() if loaded is None or mid in loaded]
    return {"modules": rows, "fingerprint": "shipped|" + ",".join(r["id"] for r in rows)}


def shipped_part(mid: str, version: str) -> bytes | None:
    """``GET /public/modules/console/<id>/<version>`` of a shipped folder module (None: not one, or not that version)."""
    at = _shipped().get(mid)
    return module_zip(at[0]) if at is not None and at[1] == version else None


NAMES = ("product", "tagline", "owner", "copyright", "notice")      # pinned by the distribution file (ConsoleDistribution.NAMES)


def branded(name: str = "all", lenses=(), **brand) -> dict:
    """The installation's answer of distribution ``name`` as a site's own distribution file with ``brand:`` would compile it on the server
    (ConsoleDistribution): the names and notices pinned, every other brand key (``product_native``, ``logo``…) in the layer under the
    site's own settings; ``lenses``: the ones the file offers (none unless named). The console reads no distribution file; a rebranded
    console is one whose server says so."""
    doc = bootstrap(name, list(lenses))
    d = doc["distribution"]
    d["brand"] = {**(d.get("brand") or {}), **brand}
    settings = d.setdefault("settings", {})
    pinned = (settings.get("pinned") or {}).get("ui") or {}
    layer = (settings.get("layer") or {}).get("ui") or {}
    for k, v in brand.items():
        (pinned if k in NAMES else layer)[k] = v
    settings["pinned"], settings["layer"] = {"ui": pinned}, {"ui": layer}
    doc["digest"] = hashlib.sha256(json.dumps(doc, sort_keys=True).encode()).hexdigest()[:16]
    return doc


def installed(name: str = "all"):
    """The installation answer of distribution ``name`` as the console's sitemap reads it (core/menus.py ``spec_of``): each module's row
    by id, with its ``console-menus``, and the answer's digest, the way :class:`core.edition.Editions` keeps them after it follows."""
    from types import SimpleNamespace

    doc = bootstrap(name)
    return SimpleNamespace(server_modules={str(m["id"]): m for m in doc.get("modules") or [] if isinstance(m, dict) and m.get("id")},
                           _digest=doc["digest"])


def serve(app, doc: dict) -> None:
    """The console takes ``doc`` from its own server as it does in production (core/server_facts.py): the server's about answer names
    the installation's stamp, the console asks its bootstrap, keeps it for drawing (the commands each menu item needs) and its editions
    follow it."""
    import asyncio

    facts = app.state.server_facts

    async def get(url, headers):
        return 200, doc
    facts.get = get
    asyncio.run(facts.follow(app.state.servers.default, {"stamps": {"installation": doc["digest"]}}, "http://stand-in", {}))
