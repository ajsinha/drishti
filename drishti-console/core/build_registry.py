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

"""The Build home's registry of object types (2.9.11.e; docs/architecture/SHARED_SCREENS.md section 1, mock-up e1-build-home A, B and C).

Every kind of thing a person may build (a connector, a table, a Sutra, a report, ...) is one :class:`ObjectType`, registered by the domain
that owns it: the core's in ``core/build_types.py``, banking's in ``core/build_types_banking.py`` (the domain split moves that file with
the rest of banking, nothing else changes). A type says its rail group (Data, Screens, Analysis, Work), its first-visit task tile (bring,
shape, show, package), who may see it (``requires``, the sitemap's words), where + New starts one, and how to list what exists
(``source``, an async function of the request answering :class:`Item` rows). The Work group's types are *derived*: they list rows of the
other types (my drafts, waiting for review, recently changed).

Lineage is generic: an item names what it **uses** (``uses``, references ``type:name``); the registry inverts every item's ``uses`` into
the other side, **used by**, across all types gathered for the person, so a source never computes both.

Nothing here is kept per person: each request gathers its own rows (session isolation), every source runs at once with a time limit, and
a source that fails or times out leaves its type with no count ("not available") instead of failing the page.
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Awaitable, Callable

LOG = logging.getLogger("drishti.build")

GROUPS = (("data", "Data"), ("screens", "Screens"), ("analysis", "Analysis"), ("work", "Work"))
TASKS = (("bring", "Bring data in", "connectors, lakes, a file, a Python table"),
         ("shape", "Shape it", "virtual tables and semantic models"),
         ("show", "Show it", "screens (Sutras), reports and boards"),
         ("package", "Package it", "packs, the UI kit, functions"))
SOURCE_SECONDS = 6.0


@dataclass
class Item:
    """One thing that exists: its name, kind, status (with version), its editor's address, what it uses (``type:name``), when it last
    changed (epoch ms) and by whom. ``draft``: an unfinished draft of the person's; ``review``: waiting for someone's approval."""
    type: str
    name: str
    kind: str = ""
    status: str = ""
    href: str = ""
    uses: tuple[str, ...] = ()
    changed: int = 0
    by: str = ""
    draft: bool = False
    review: bool = False
    extra: dict = field(default_factory=dict)

    @property
    def ref(self) -> str:
        return f"{self.type}:{self.name}"


Source = Callable[..., Awaitable[list[Item]]]


@dataclass(frozen=True)
class ObjectType:
    id: str
    group: str
    label: str
    icon: str
    task: str = ""
    domain: str = "core"
    requires: tuple[str, ...] = ()
    new: str = ""
    new_label: str = ""
    source: Source | None = None
    derived: Callable[[dict[str, list[Item]]], list[Item]] | None = None
    blurb: str = ""


class Registry:
    """The object types in registration order; a domain registers each of its own once."""

    def __init__(self) -> None:
        self._types: dict[str, ObjectType] = {}

    def register(self, t: ObjectType) -> None:
        if t.group not in dict(GROUPS):
            raise ValueError(f"{t.id}: unknown group {t.group}")
        if t.id in self._types:
            raise ValueError(f"{t.id}: registered twice")
        self._types[t.id] = t

    def types(self) -> list[ObjectType]:
        return list(self._types.values())

    def get(self, type_id: str) -> ObjectType | None:
        return self._types.get(type_id)

    def domains(self) -> dict[str, list[str]]:
        out: dict[str, list[str]] = {}
        for t in self._types.values():
            out.setdefault(t.domain, []).append(t.id)
        return out


REGISTRY = Registry()


def register(t: ObjectType) -> None:
    REGISTRY.register(t)


def loaded() -> Registry:
    """The registry with every domain's types (each registers on import, once)."""
    from core import build_types, build_types_banking  # noqa: F401  (registration on import)
    return REGISTRY


async def gather(request, types: list[ObjectType]) -> dict[str, list[Item] | None]:
    """Every listed type's rows for this request, all sources at once; ``None`` for a type whose source failed or took too long."""
    direct = [t for t in types if t.source is not None]

    async def one(t: ObjectType):
        try:
            return await asyncio.wait_for(t.source(request), SOURCE_SECONDS)
        except Exception as e:                                   # a source that fails leaves its type without a count, never the page
            LOG.info("build home: %s not available (%s)", t.id, type(e).__name__)
            return None

    answers = await asyncio.gather(*(one(t) for t in direct))
    out: dict[str, list[Item] | None] = {t.id: a for t, a in zip(direct, answers)}
    have = {k: v for k, v in out.items() if v is not None}
    for t in types:
        if t.derived is not None:
            out[t.id] = t.derived(have)
    return out


def used_by(rows: dict[str, list[Item] | None]) -> dict[str, list[str]]:
    """Lineage's other side: for each ``type:name``, the items that use it (``type:name`` too), across every type gathered."""
    out: dict[str, list[str]] = {}
    seen: set[tuple[str, str]] = set()
    for items in rows.values():
        for it in items or []:
            for u in it.uses:
                if (u, it.ref) not in seen:
                    seen.add((u, it.ref))
                    out.setdefault(u, []).append(it.ref)
    return out


def matches(item: Item, words: list[str]) -> bool:
    """One search across everything: every word in the item's name, kind, status or what it uses."""
    hay = " ".join([item.name, item.kind, item.status, item.by, " ".join(item.uses)]).lower()
    return all(w in hay for w in words)
