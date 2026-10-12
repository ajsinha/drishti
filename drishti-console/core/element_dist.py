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

"""How Drishti Elements are distributed (docs/architecture/ELEMENTS.md section 13; build step 12).

The library is the ES modules under ``web/embed/``. Each release has a semantic version (``VERSION``, also the ``version`` the library reports in
``window.DrishtiElements``); the console serves exactly that version immutably at ``/embed/v1/elements/<version>/...`` with an ``integrity.json`` of SHA-384
hashes (subresource integrity), answers a partial version (``1`` or ``1.1``) with a ``302`` to the exact one, and the npm package ``@drishti/elements``
(``web/elements/package/``) is the same bytes. ``web/elements/integrity.json`` is the committed record: ``tests/test_element_package.py`` fails when the
modules changed and ``VERSION`` did not, so a version always names the same bytes.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
from datetime import date, datetime, timezone
from email.utils import format_datetime
from pathlib import Path

WEB = Path(__file__).resolve().parent.parent / "web"
EMBED = WEB / "embed"
OUT = WEB / "elements"
PACKAGE = OUT / "package"
LOCK = OUT / "integrity.json"
VERSION = "1.4.0"                  # bump on ANY change of a file under web/embed/ (the guard test says so); major = the element API contract
API = "1.0"                        # the embed API contract the modules speak (Drishti-Embed-Api)
ENTRY = "drishti-elements.js"
SEMVER = re.compile(r"\d+(\.\d+){0,2}")


def files() -> dict[str, Path]:
    """The distributed files by their path in a version's folder: the entry and ``m/<name>.js`` for every module."""
    out = {ENTRY: EMBED / ENTRY}
    for path in sorted(EMBED.glob("*.js")):
        if path.name != ENTRY:
            out[f"m/{path.name}"] = path
    return out


def sri(data: bytes) -> str:
    return "sha384-" + base64.b64encode(hashlib.sha384(data).digest()).decode()


def integrity() -> dict:
    """The record of one version: the SHA-384 of every file (subresource integrity values), their sizes and the API contract."""
    return {"name": "@drishti/elements", "version": VERSION, "api": API,
            "files": {rel: {"integrity": sri(p.read_bytes()), "bytes": p.stat().st_size} for rel, p in files().items()}}


def lock_text(record: dict | None = None) -> str:
    return json.dumps(record or integrity(), indent=2, sort_keys=True) + "\n"


def resolve(spec: str) -> str | None:
    """The exact version a requested one names: itself, or for a partial ``1`` / ``1.1`` the newest that matches (this console holds one version); else None."""
    if not SEMVER.fullmatch(spec):
        return None
    if spec == VERSION or VERSION.startswith(spec + "."):
        return VERSION
    return None


def _day(text) -> date | None:
    try:
        return date.fromisoformat(str(text)[:10])
    except ValueError:
        return None


def lifecycle(settings: dict, major: str = "1", today: date | None = None) -> dict:
    """What ``embed.lifecycle.<major>`` says about an embed API major: ``{"deprecated": bool, "sunset": date|None, "retired": bool, "headers": {...}, "link": str}``.

    Configuration is ``embed.lifecycle: {"1": {deprecation: 2027-01-01, sunset: 2027-07-01, link: https://.../migrating}}``; absent means nothing is announced.
    ``Deprecation`` (RFC 9745) carries the date as ``@<epoch seconds>``, ``Sunset`` (RFC 8594) as an HTTP date; once the sunset day has come the major is retired
    (``410 DRS-8006``)."""
    entry = ((settings or {}).get("lifecycle") or {}).get(major) or {}
    today = today or datetime.now(timezone.utc).date()
    dep, sun = _day(entry.get("deprecation")), _day(entry.get("sunset"))
    headers: dict = {}
    if dep and today >= dep:
        headers["Deprecation"] = "@" + str(int(datetime(dep.year, dep.month, dep.day, tzinfo=timezone.utc).timestamp()))
    if sun:
        headers["Sunset"] = format_datetime(datetime(sun.year, sun.month, sun.day, tzinfo=timezone.utc), usegmt=True)
    link = str(entry.get("link") or "")
    if link and headers:
        headers["Link"] = f'<{link}>; rel="deprecation"; type="text/html"'
    return {"deprecated": "Deprecation" in headers, "sunset": sun, "retired": bool(sun and today >= sun), "headers": headers, "link": link}
