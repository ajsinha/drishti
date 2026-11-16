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
    """The answer with only the modules ``keep(id)`` holds (a module not installed on the server is not in its answer)."""
    out = copy.deepcopy(doc)
    out["modules"] = [m for m in out["modules"] if keep(m["id"])]
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
