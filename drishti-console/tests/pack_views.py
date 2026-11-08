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

"""Every Sutra of every shipped pack bound on its own sample by a real server: the ViewModels the render parity suites draw
(test_render_parity_browser.py; EMBED_GUIDE.md section 14).

``drawable()`` lists, at collection, every pack Sutra that has a sample document (one test each). ``bind(server)`` asks a real server
for each one's ViewModel (POST /api/v1/studio/preview). The ``pack_views`` fixture (conftest.py) binds them once per test run for every
pytest worker (browser_harness.shared_once): the first worker that needs them starts a server, the others read its answer."""
from __future__ import annotations

import functools
import json
import re
from pathlib import Path

from core.pack_paths import pack_dir, pack_dirs

ROOT = Path(__file__).resolve().parents[2]


def pack_sutras() -> list[tuple[str, Path]]:
    out = []
    for name, d in pack_dirs().items():
        out += [(name, f) for f in sorted(d.glob("sutras/**/*.sutra.yaml"))]
    for f in sorted((ROOT / "docs" / "guides" / "examples").glob("*.sutra.yaml")):
        out.append(("examples", f))
    return out


def sample(pack: str, sutra: Path, kind: str, text: str) -> tuple[str, dict] | None:
    if pack == "examples":
        doc = sutra.with_name(sutra.name.replace(".sutra.yaml", ".json"))
        return ("SAMPLE", json.loads(doc.read_text(encoding="utf-8"))) if doc.exists() else None
    samples = pack_dir(pack) / "samples" / f"{kind}.jsonl"
    if not samples.exists():
        return None
    m = re.search(r'"/v/[^/]+/([^"]+)"', text)
    want = m.group(1) if m else None
    first = None
    with samples.open(encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            first = first or line
            if want and f'"{want}"' in line:
                return want, json.loads(line)
    return (want or "SAMPLE", json.loads(first)) if first else None


def _kind(text: str) -> str:
    import yaml
    y = yaml.load(text, Loader=getattr(yaml, "CSafeLoader", yaml.SafeLoader)) or {}
    kind = ((y.get("match") or {}).get("kind")) if isinstance(y.get("match"), dict) else None
    return kind or "trade"


@functools.lru_cache(maxsize=1)
def drawable() -> tuple[str, ...]:
    """Every pack Sutra (its path from the repository root) that has a sample to bind it on: one test per Sutra. Read at collection
    on every worker, so it depends on the files only."""
    out = []
    for pack, f in pack_sutras():
        if pack == "examples":
            ok = f.with_name(f.name.replace(".sutra.yaml", ".json")).exists()
        else:
            s = pack_dir(pack) / "samples" / f"{_kind(f.read_text(encoding='utf-8'))}.jsonl"
            ok = s.exists() and s.stat().st_size > 0
        if ok:
            out.append(str(f.relative_to(ROOT)))
    return tuple(out)


def test_id(rel: str) -> str:
    """A short test id: pack/sutra name."""
    parts = Path(rel).parts
    pack = parts[2] if parts[:2] == ("config", "packs") else "examples"
    return f"{pack}/{Path(rel).name.removesuffix('.sutra.yaml')}"


def bind(server: str) -> dict:
    """{"views": {sutra path: ViewModel}, "skipped": [why]} for every pack Sutra with a sample document, from a real server."""
    import urllib.request
    got, skipped = {}, []
    for pack, f in pack_sutras():
        text = f.read_text(encoding="utf-8")
        kind = _kind(text)
        doc = sample(pack, f, kind, text)
        if not doc:
            skipped.append(str(f.relative_to(ROOT)))
            continue
        body = json.dumps({"yaml": text, "kind": kind, "id": doc[0], "document": doc[1]}).encode()
        req = urllib.request.Request(server + "/api/v1/studio/preview", data=body, headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                got[str(f.relative_to(ROOT))] = json.loads(r.read())
        except Exception as e:  # noqa: BLE001 - recorded: a Sutra the preview refuses is reported, not drawn
            skipped.append(f"{f.relative_to(ROOT)}: {e}")
    return {"views": got, "skipped": skipped}
