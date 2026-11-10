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
"""A stand-in for the server's module registry as the console reads it (KERNEL_AND_MODULES.md sections 4.7 and 12): the SDK archetype's
``.dmod`` console part (``drishti-sdk/drishti-module-archetype``, the module ``greeter``, word GREETER), installed, removed or switched off
like the deploy API does, served at ``/public/modules/console`` and its zip, with ``/modules/needs`` and ``/modules/route`` answered the
way the server does (a saved item that names the module's kind or word needs it while it is gone)."""
from __future__ import annotations

import io
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ARCHETYPE = ROOT / "drishti-sdk" / "drishti-module-archetype" / "src" / "main" / "resources" / "archetype-resources" / "src" / "dmod"
FILL = {"${artifactId.toUpperCase()}": "GREETER", "${artifactId}": "greeter", "${version}": "1.0.0", "${groupId}": "com.example",
        "${domain}": "testing", "${package}": "com.example.greeter"}


def archetype_zip(extra: dict[str, bytes] | None = None) -> bytes:
    """The archetype's package as the server hands its console part over (module.yaml, console/, docs/), the placeholders filled."""
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        for f in sorted(ARCHETYPE.rglob("*")):
            rel = f.relative_to(ARCHETYPE).as_posix()
            if not f.is_file() or not (rel == "module.yaml" or rel.startswith(("console/", "docs/"))):
                continue
            text = f.read_text(encoding="utf-8")
            for k, v in FILL.items():
                text = text.replace(k, v)
            z.writestr(rel, text)
        for rel, data in (extra or {}).items():
            z.writestr(rel, data)
    return out.getvalue()


class Registry:
    """The cluster's word on the module: ``active``, ``off``, ``removed`` or None (never installed)."""

    CLAIMS = ["API prefix /api/v1/greeter", "function GREETER", "kind greeter-greeting"]

    def __init__(self, extra: dict[str, bytes] | None = None):
        self.state: str | None = None
        self.version = "1.0.0"
        self.extra = extra

    def listing(self) -> dict:
        if self.state is None:
            return {"modules": [], "fingerprint": "0"}
        return {"modules": [{"id": "greeter", "version": self.version, "state": self.state, "title": "greeter", "console": self.state == "active",
                             "claims": self.CLAIMS}], "fingerprint": self.state}

    def install(self, backend, monkeypatch) -> None:
        from core.backend import BackendError

        async def public(path, raw=False, timeout=30.0):
            if path == "/public/modules/console":
                return self.listing()
            if path == f"/public/modules/console/greeter/{self.version}" and self.state == "active":
                return archetype_zip(self.extra)
            raise BackendError(410 if self.state == "removed" else 404, "DRS-4040", path)

        before = backend._send

        async def send(method, path, ident=None, **kw):
            if path == "/modules/needs":
                gone = self.state in ("off", "removed")
                words = "needs module greeter" + (" (off)" if self.state == "off" else " (not installed)")
                return {"needs": {i["id"]: [{"module": "greeter", "title": "greeter", "state": self.state, "words": words}]
                                  for i in kw["json"]["items"] if gone and ("greeter-greeting" in i["text"] or "GREETER" in i["text"])}}
            if path == "/modules/route":
                status = {"active": 200, "off": 404, "removed": 410}.get(self.state or "", 404)
                return {"status": status, "module": "greeter" if self.state else None, "title": "greeter", "state": self.state,
                        "by": "admin-two", "at": "2026-11-10T10:42:00Z", "deployment": "dpl-7"}
            return await before(method, path, ident, **kw)

        put = (lambda o, n, v: monkeypatch.setattr(o, n, v, raising=False)) if monkeypatch is not None else setattr   # None: a stand-in of its own
        put(backend, "public", public)
        put(backend, "_send", send)

        async def sutras(ident=None):
            return [{"name": "greetings-board", "kind": "greeter-greeting", "latest": 2}]
        put(backend, "sutras", sutras)
