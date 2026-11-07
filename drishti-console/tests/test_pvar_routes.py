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

"""Parametric VaR on the VaR explorer (docs/architecture/PARAMETRIC_VAR.md; VAR_GUIDE.md section 3.12): a Parametric toggle on the
explorer (pm=1) and, when on, the tiles, the tree's columns and the Normal curve toggle; PVAR is only an alias that opens the explorer with
the toggle on, on the scope and lens VAR would read: no screen, view or menu item of its own; the console passes parametric= and matrix= to
the engine and nothing else."""
import pathlib

from routes.var_routes import pvar_command, var_command, var_suggestions
import engine_fakes  # noqa: E402


def test_pvar_opens_the_explorer_in_parametric_mode():
    assert pvar_command("PVAR BANK".split(), "asha") == "/var?scope=BANK&pm=1"
    assert pvar_command(["PVAR"], "asha") == "/var?scope=BANK&pm=1"
    assert pvar_command("pvar desk desk-rates 10D SUM 95".split(), "asha") == "/var?scope=DESK%3Adesk-rates&h=10d&m=sum&q=0.95&pm=1"
    assert pvar_command("PVAR DESK DESK-RATES IN EUR <GO>".split(), "asha") == "/var?scope=DESK%3ADESK-RATES&ccy=EUR&pm=1"
    assert pvar_command("VAR BANK".split(), "asha") is None
    assert var_command("VAR BANK".split(), "asha") == "/var?scope=BANK"            # VAR stays historical


def test_the_go_line_takes_pvar(client):
    r = client.get("/go?q=PVAR%20BANK%20%3CGO%3E", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/var?scope=BANK&pm=1"


def test_the_explorer_has_a_parametric_toggle_and_no_views_of_its_own(client):
    page = client.get("/var?scope=BANK&pm=1").text
    assert 'data-pm="1"' in page and "data-vp-on checked" in page
    assert "data-vp-curve" in page and "<kbd>N</kbd>" in page and "data-vp-tiles" in page
    assert "/static/js/drishti_var-parametric.js" in page and "/static/css/drishti_var-parametric.css" in page
    assert "cross-check, not official" in page and "Worked in the playground" in page
    assert "data-vp-view" not in page and "data-vp-pane" not in page and "PLAY PVAR" not in page   # no views B and C, no PLAY PVAR
    assert "VAR BANK &lt;GO&gt;" in page or "VAR BANK <GO>" in page              # the command line says VAR: PVAR is an alias
    plain = client.get("/var?scope=BANK").text
    assert "data-vp-on" in plain and "data-vp-on checked" not in plain            # the toggle, off
    assert "data-vp-curve" not in plain and "data-vp-tiles" not in plain and "data-vx-pvar" not in plain
    assert "/static/js/drishti_var-parametric.js" in plain                                 # the toggle's script


def test_no_menu_item_names_parametric_var(client):
    menus = (pathlib.Path(__file__).resolve().parents[1] / "config" / "sitemap.yaml").read_text()
    assert "PVAR" not in menus and "Parametric VaR" not in menus


def test_the_proxy_passes_parametric_and_matrix_on(client, backend, monkeypatch):
    seen = []

    async def var(method, path, ident, body=None, raw=False, **params):
        seen.append((method, path, params))
        return {"rows": [], "parametric": {"var": 1}}
    engine_fakes.patch(monkeypatch, backend, var=var)
    r = client.get("/api/var/scope?scope=BANK&parametric=false")
    assert r.status_code == 200
    assert seen[-1][2].get("parametric") == "false"
    client.get("/api/var/scope/contributions?scope=BANK&by=child&matrix=true")
    assert seen[-1][1] == "/scope/contributions" and seen[-1][2].get("matrix") == "true"
    client.get("/api/var/tree?parametric=false")
    assert seen[-1][2].get("parametric") == "false"


def test_the_type_ahead_offers_the_lens_after_a_pvar_scope():
    picks = [x["complete"] for x in var_suggestions("PVAR DESK DESK-RATES")]
    assert "PVAR DESK DESK-RATES 10D SUM" in picks and "PVAR DESK DESK-RATES 95" in picks
    assert not any(x.endswith("EXPLAIN") for x in picks)
    assert var_suggestions("VAR BANK")[0]["mnemonic"] == "VAR"
