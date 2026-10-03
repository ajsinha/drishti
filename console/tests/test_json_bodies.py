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

# Drishti - Copyright (c) 2025-2030 Ashutosh Sinha (ajsinha@gmail.com). All rights reserved. Proprietary and confidential.
"""UX-13: a console endpoint answers a body that is not JSON with a problem (400 or 415), never a plain 500."""
import inspect
import re
from pathlib import Path

import pytest

from core.app import create_app
from core.config import load_settings

from conftest import CONSOLE

BODIES = [("this is not json", "text/plain"), ("{broken", "application/json"), ("[1, 2]", "application/json"), ("\"text\"", "application/json")]


def _routes():
    app = create_app(load_settings(CONSOLE / "config"))
    out = []
    flat = [x for r in app.routes for x in (r.original_router.routes if hasattr(r, "original_router") else [r])]
    for r in flat:
        for m in getattr(r, "methods", None) or ():
            if m in ("POST", "PUT", "PATCH", "DELETE"):
                out.append((m, r.path, r.endpoint))
    return sorted(out, key=lambda t: (t[1], t[0]))


def _json_routes():
    return [(m, p) for m, p, fn in _routes() if "json_body" in inspect.getsource(fn)]


@pytest.mark.parametrize("method,path", _json_routes())
@pytest.mark.parametrize("body,kind", BODIES)
def test_a_body_that_is_not_json_is_never_a_500(client, method, path, body, kind):
    url = re.sub(r"\{[^}]*\}", "x", path)
    r = client.request(method, url, content=body, headers={"content-type": kind, "origin": "http://testserver"})
    assert r.status_code < 500, f"{method} {path} -> {r.status_code}: {r.text[:200]}"


def test_no_route_reads_a_json_body_by_itself():
    """Every JSON body goes through core.csrf.json_body (400/415 problems), never request.json() or json.loads."""
    bad = [p.name for p in (CONSOLE / "routes").glob("*.py")
           if re.search(r"request\.json\(|json\.loads\(\s*await|await request\.body\(\)\)?\s*\)?\s*\.json", p.read_text())]
    assert not bad, bad
    assert len(_json_routes()) >= 15
