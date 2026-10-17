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

"""Wave B, console lane tasks 3 to 5 without a browser: the model designer's Calculations tab and Quick calculation are on the page and their YAML is written as the
server reads it; the Fields shape carries the parameters and calculation groups; a standing query's pushed answers reach the browser over the one channel
(mq:<id>); every consumer has the time picker, the parameter bar and the state chip; the Admin BI usage page sums the audit log. The browser half is
tests/test_bi_wave_b_browser.py."""
import asyncio
import json
from types import SimpleNamespace

import yaml
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core import model_layer
from core.app import create_app
from core.bi_usage import summarise
from core.channel import ChannelSession
from core.config import load_settings

JS = CONSOLE / "web/static/js"


def app_with(backend):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    return TestClient(app)


# ---- task 3: the calculation UI ---------------------------------------------------------------------------------------------
def test_the_model_designer_has_the_calculations_tab_and_its_script():
    page = (CONSOLE / "web/templates/bi/model_designer.html").read_text(encoding="utf-8")
    assert 'data-tab="calc"' in page and "data-calc-pane" in page and "/static/js/bi-model-calc.js" in page
    calc = (JS / "bi-model-calc.js").read_text(encoding="utf-8")
    for fn in ("share_of(", "calc(", "closing(", "opening(", "avg_over(", "fiscal_ytd(", "as_of(", "param."):
        assert fn in calc, fn
    for key in ("'calc-groups'", "doc.parameters", "doc.calendars", "doc.fx", "'fiscal-year-start'", "SELECTED"):
        assert key in calc, key
    tabs = (JS / "bi-model-tabs.js").read_text(encoding="utf-8")
    assert "data-m-via" in tabs and "doc.measures[name].via" in tabs and "M.quickMeasure" in tabs
    editor = (JS / "bi-expr-editor.js").read_text(encoding="utf-8")
    assert "opts.names" in editor and "opts.keywordValues" in editor and "'ALL'" in editor


def test_the_calculation_keys_are_written_as_the_server_reads_them():
    doc = {"rachana": 2, "model": "m", "version": "1.0.0", "datasets": {"t": {"dataset": "d", "role": "fact"}},
           "measures": {"MTM": {"expr": "sum(t.mtm)"}, "MTM % of total": {"expr": "share_of(measure('MTM'), over=ALL)", "format": "pct"},
                        "MTM via": {"expr": "measure('MTM')", "via": "r_2"}},
           "fx": {"rates": "t", "from": "a", "to": "b", "rate": "r", "date": "d", "base": "USD"},
           "calendars": {"holidays": "USNY", "fiscal-year-start": 4},
           "parameters": {"shock": {"kind": "number", "default": 0, "min": -0.5, "max": 0.5, "step": 0.01}, "breakdown": {"kind": "field", "fields": ["t.a", "t.b"], "default": "t.a"}},
           "calc-groups": {"Time": {"precedence": 10, "items": {"Current": "SELECTED", "YTD": "ytd(SELECTED, t.d)"}}}}
    text = model_layer.to_yaml(doc)
    assert yaml.safe_load(text) == doc
    keys = [line.split(":")[0] for line in text.splitlines() if line and not line.startswith(" ")]
    assert keys.index("measures") < keys.index("calc-groups") < keys.index("parameters") < keys.index("calendars") < keys.index("fx")
    assert "  shock: {kind: number, default: 0, min: -0.5, max: 0.5, step: 0.01}" in text            # one line per parameter


def test_the_fields_shape_carries_parameters_and_calculation_groups():
    described = {"id": "m@1", "datasets": [], "measures": [], "parameters": [{"name": "shock", "kind": "number", "default": 0}, {"kind": "bad"}],
                 "calcGroups": [{"name": "Time", "items": [{"name": "YTD", "expr": "ytd(SELECTED, t.d)"}]}]}
    shape = model_layer.fields_shape(described, "m@1")
    assert [p["name"] for p in shape["parameters"]] == ["shock"] and shape["calcGroups"][0]["name"] == "Time"


# ---- task 4: live everywhere ------------------------------------------------------------------------------------------------
def test_a_standing_querys_pushed_answers_reach_the_browser_over_the_one_channel():
    class Backend:
        def __init__(self):
            self.paths = []

        async def sse(self, path, ident=None, opened=None):
            self.paths.append(path)
            yield "answer", json.dumps({"subscription": "sq_1", "cursor": "g-2", "rows": [["DESK-A", 12.0]], "mode": "incremental"})
            yield "status", json.dumps({"subscription": "sq_1", "state": "live", "mode": "incremental", "lagMillis": 41})
            await asyncio.sleep(30)

    async def go():
        backend = Backend()
        session = ChannelSession(backend, SimpleNamespace(user="rita"), lambda e, d: d, limit=4)

        async def never():
            return False

        out = []
        agen = session.events(["mq:sq_1"], never)
        async for chunk in agen:
            out.append(chunk)
            if len(out) >= 3:
                break
        await agen.aclose()
        return out, backend

    out, backend = asyncio.run(go())
    events = [(c.split("\n")[0], json.loads(c.split("\n")[1][6:])) for c in out[1:]]
    assert [e[0] for e in events] == ["event: answer", "event: status"] and events[0][1]["ch"] == "mq:sq_1"
    assert backend.paths == ["/bi/mq/stream?ids=sq_1"]
    assert "'answer'" in (JS / "live-hub.js").read_text(encoding="utf-8")


def test_every_consumer_has_the_time_picker_the_chip_and_follows_pushed_answers():
    c = app_with(BiBackend())
    report = c.get("/bi/r/r1").text
    board = c.get("/bi/b/b1").text
    explore = c.get("/bi/explore").text
    for page in (report, board, explore):
        assert "data-time-mode" in page and 'value="asof"' in page and 'value="window"' in page and "/static/js/bi-consume.js" in page
    assert "data-param-bar" in report
    consume = (JS / "bi-consume.js").read_text(encoding="utf-8")
    assert "'/mq/subscribe'" in consume and "'mq:' + sq" in consume and "EventSource" not in consume and "innerHTML" not in consume
    for word in ("Live", "Throttled", "Stale", "Every few seconds"):
        assert word in consume, word
    for name in ("bi-report.js", "bi-board.js", "alerts-live.js"):
        assert "B.consume.follow(" in (JS / name).read_text(encoding="utf-8"), name


# ---- task 5: explain and usage ----------------------------------------------------------------------------------------------
def test_each_visual_has_the_explain_panel():
    report = (JS / "bi-report.js").read_text(encoding="utf-8")
    assert "explain-plan" in report and "B.consume.explain(" in report
    consume = (JS / "bi-consume.js").read_text(encoding="utf-8")
    assert "explain: true" in consume and "m.tiers" in consume and "m.explainText" in consume and "m.timings" in consume


def test_the_usage_summary_of_the_audit_log():
    events = [{"at": "2026-10-17T09:00:00Z", "actor": "rita", "action": "bi-mq", "subject": "trades-basic@1", "detail": "1 fields, 2 measures, 4 rows from columns"},
              {"at": "2026-10-17T09:01:00Z", "actor": "rita", "action": "bi-mq", "subject": "trades-basic@1", "detail": "1 fields, 2 measures, 4 rows from columns (cached)"},
              {"at": "2026-10-17T09:02:00Z", "actor": "hana", "action": "bi-query", "subject": "file:dataset:x", "detail": "model query: 2 fields, 1 measures, 10 rows from live"},
              {"at": "2026-10-17T09:03:00Z", "actor": "hana", "action": "bi-sql", "subject": "trades-basic@1", "detail": "grouped: SELECT 1"},
              {"at": "2026-10-17T09:04:00Z", "actor": "wes", "action": "bi-item-published", "subject": "r", "detail": ""},
              {"at": "2026-10-17T09:05:00Z", "actor": "wes", "action": "login", "subject": "", "detail": ""}]
    u = summarise(events)
    tb = {m["model"]: m for m in u["models"]}
    assert u["total"] == 3 and u["sql"] == 1 and u["hitRate"] == 33.3
    assert tb["trades-basic@1"]["questions"] == 2 and tb["trades-basic@1"]["hitRate"] == 50.0 and tb["trades-basic@1"]["layers"] == {"columns": 2} and tb["trades-basic@1"]["sql"] == 1
    assert tb["file:dataset:x"]["layers"] == {"live": 1} and u["other"] == [{"action": "bi-item-published", "count": 1}]
    assert u["readers"][0] == {"reader": "rita", "questions": 2}


def test_the_usage_page_for_a_bi_administrator():
    class Backend(BiBackend):
        async def admin(self, method, path, ident, body=None, **params):
            if path == "/audit":
                return [{"at": "2026-10-17T09:00:00Z", "actor": "rita", "action": "bi-mq", "subject": "trades-basic@1", "detail": "1 fields, 2 measures, 4 rows from columns (cached)"}]
            return await super().admin(method, path, ident, body, **params)

        async def bi(self, method, path, ident, body=None, **params):
            if path == "/engine":
                return {"engine": "duckdb", "live": {}}
            return await super().bi(method, path, ident, body, **params)

    c = app_with(Backend())
    page = c.get("/admin/bi/usage")
    assert page.status_code == 200 and 'data-usage-model="trades-basic@1"' in page.text and "100.0 %" in page.text and "Fork-server" in page.text
    assert "data-usage-link" in c.get("/admin/bi").text
    reader = app_with(BiBackend(("bi",)))
    assert reader.get("/admin/bi/usage").status_code == 403
