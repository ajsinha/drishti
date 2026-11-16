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

"""The boot test: a real server and console, started as the browser lane starts them, with the demo off over a built demo lake. The
bank's VaR draws its headline, POS and TRD list rows, FORMS lists every pack, and the server's log has no class that failed to load (a
connector naming a module's engine waits for the modules, it does not fail) and no connector refused for a map it could not find. No
DRISHTI_PACKS_EXTRA_DIRS: the modules' packs come from the checkout's modules folder alone."""
from __future__ import annotations

import json
import re
import urllib.request

import pytest

from var_stack import shared_lake, var_server
from test_workbench_browser import browser  # noqa: F401 - the browser fixture


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    lake = shared_lake(tmp_path_factory)
    yield from var_server(tmp_path_factory, lake, heap="3072m")   # every trade of six systems and the VaR inputs


def _packs_with_form() -> set[str]:
    """The packs of the code module (banking's .dmod folder) with a form, found through the one pack resolution (core/resolution.py)."""
    import yaml
    from core import resolution as R
    out = set()
    packs = R.build(R.Settings.from_env(env={})).packs
    for p in (packs[n] for n in packs):
        if p.folder is None or p.origin != "MODULE" or "dmod" not in p.folder.parts:
            continue
        m = yaml.safe_load((p.folder / "pack.yaml").read_text(encoding="utf-8")) or {}
        if isinstance(m.get("form"), dict) and m["form"]:
            out.add(p.name)
    return out


WITH_FORM = _packs_with_form()


def _wait(pg, js, timeout=120_000):
    pg.wait_for_function(js, timeout=timeout)


def test_a_demo_off_stack_boots_with_var_positions_trades_and_every_form(stack, browser):
    assert "DRISHTI_PACKS_EXTRA_DIRS" not in (stack.work / "server.log").read_text(errors="replace")
    pg = browser.new_page(viewport={"width": 1600, "height": 1000})
    try:
        pg.goto(stack + "/go?q=VAR+BANK")
        _wait(pg, "() => !!document.querySelector('[data-var][data-painted]')")
        headline = pg.locator('.vx-tile[data-tile="0"] .v').inner_text().strip()
        assert re.search(r"\d", headline), f"the bank's VaR has no headline: {headline!r}"

        for word in ("POS", "TRD"):
            pg.goto(stack + "/go?q=" + word)
            try:
                _wait(pg, "() => document.querySelectorAll('.dg-row, tbody tr').length > 0", timeout=40_000)
            except Exception:
                with urllib.request.urlopen(stack.server + "/api/v1/admin/bindings/map", timeout=60) as r:
                    kinds = [k for k in json.loads(r.read().decode())["kinds"] if k["kind"] in ("trade", "position")]
                pytest.fail(f"{word}: {pg.url}\n{json.dumps(kinds, indent=1)[:6000]}", pytrace=False)
            assert pg.locator(".dg-row, tbody tr").count() > 0, f"{word} lists no rows"

        with urllib.request.urlopen(stack.server + "/api/v1/packs", timeout=60) as r:
            body = json.loads(r.read().decode())
        rows = body if isinstance(body, list) else body.get("packs", [])
        names = sorted({str(p.get("name") or p.get("pack")) for p in rows if isinstance(p, dict) and not p.get("sample")} - {"None"})
        assert names, f"the server lists no packs: {str(body)[:300]}"
        names = [n for n in names if n in WITH_FORM]          # FORMS lists the packs that declare a form: section
        pg.goto(stack + "/forms")
        page = pg.content()               # each pack section carries its name and title in data-text (the page shows the title)
        missing = [n for n in names if n not in page and n.replace("-", " ").lower() not in page.lower()]
        why = ""
        for n in missing:
            try:
                with urllib.request.urlopen(stack.server + "/api/v1/forms/" + n, timeout=60) as r:
                    why += f"\n{n}: {r.read().decode()[:300]}"
            except Exception as e:                                      # noqa: BLE001
                why += f"\n{n}: {e} {getattr(e, 'read', lambda: b'')().decode()[:400]}"
        assert not missing, f"FORMS does not list the packs {missing}{why}"
    finally:
        pg.close()
    log = (stack.work / "server.log").read_text(errors="replace")
    unloaded = [line[:300] for line in log.splitlines() if "ClassNotFoundException" in line and "waits for the modules to load" not in line]
    assert not unloaded, "a class failed to load at start:\n" + "\n".join(unloaded)[:2000]
    assert "is not found (looked in the connector's module folder" not in log, "a connector's map file was not found"
    assert not re.search(r" ERROR .*failed to start", log), "a connector failed to start:\n" + "\n".join(
        line[:300] for line in log.splitlines() if re.search(r" ERROR .*failed to start", line))[:2000]
