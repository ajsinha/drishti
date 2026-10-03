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

"""Starting a Design from where the old Studio addresses came from: an example (as a copy), a Sutra of the registry (with its
Studio test entities carried over as stored-entity samples), a stored entity, or nothing (a scratch Design on the default
example). Used by the redirects of ``/studio`` and by ``/build`` itself; the console holds nothing, the server keeps the Designs."""
from __future__ import annotations

import json
import logging
import re

from core.backend import BackendError

LOG = logging.getLogger("drishti.console.designs")

NEW_SUTRA = """rachana: 1
sutra: my-layout
version: 1
description: What this layout shows, and for which entities.
match: {{ kind: {kind} }}
title: {{ pill: "{label}", id: $.id }}
panels:
  - {{ id: refs, kind: links, title: Linked entities, area: right }}
"""


def new_sutra(kind: str) -> str:
    """The starting text of a new Sutra: for the kind being previewed, binding no field (no pack's field names)."""
    kind = kind if kind.replace("-", "").isalnum() else "trade"
    return NEW_SUTRA.format(kind=kind, label=kind.replace("-", " ").capitalize())


def strip_licence(text: str) -> str:
    """The text without a leading ``<!-- ... -->`` comment that carries the licence header (an example's README has one): a reviewer
    reads the notes, not the legal text."""
    m = re.match(r"\s*<!--.*?-->\s*", text or "", re.S)
    return text[m.end():] if m and re.search(r"Copyright|PROPRIETARY|Project Drishti", m.group(0)) else (text or "")


async def copy_example(backend, ex, me, fallback_kind: str, scratch: bool = False) -> dict:
    """A new Design that is a COPY of an example: its Sutra, its JSON as a sample, its README as notes. ``scratch``: unnamed, so it
    is forgotten after a day. The files under docs/guides/examples are only read."""
    design = await backend.designs("POST", "", me, {"name": "" if scratch else f"{ex.title} (copy)"[:100], "kind": ex.kind or fallback_kind,
                                                    "sutra": ex.yaml, "notes": strip_licence(ex.readme)})
    await backend.designs("POST", f"/{design['id']}/samples", me, {"samples": [{"name": f"{ex.name}.json", "document": json.loads(ex.json)}]})
    return design


async def migrate_tests(backend, me, design_id: str, base: str) -> int:
    """Studio kept, per Sutra and author, the entities to try it on. A Design made from that Sutra starts with them as stored-entity
    samples (read again each time, with the user's rights), so the old habit survives. An entity that can no longer be opened is
    skipped. Returns how many were added."""
    name = (base or "").partition("@")[0]
    if not name:
        return 0
    try:
        kept = await backend.studio_tests(name, me)
    except BackendError:
        return 0
    by_kind: dict[str, list[str]] = {}
    for t in kept or []:
        if isinstance(t, dict) and isinstance(t.get("kind"), str) and isinstance(t.get("id"), str):
            by_kind.setdefault(t["kind"], []).append(t["id"])
    added = 0
    for kind, ids in by_kind.items():
        try:
            await backend.designs("POST", f"/{design_id}/samples", me, {"refs": {"kind": kind, "ids": ids}})
            added += len(ids)
        except BackendError as e:
            LOG.info("studio tests of %s: %d %s entit(ies) not carried over (%s)", name, len(ids), kind, e.code)
    return added


async def add_entity(backend, me, design_id: str, kind: str, id_: str) -> bool:
    """A stored entity as a ref sample; False when the user may not open it (the Design is still made)."""
    try:
        await backend.designs("POST", f"/{design_id}/samples", me, {"refs": {"kind": kind, "ids": [id_]}})
        return True
    except BackendError:
        return False
