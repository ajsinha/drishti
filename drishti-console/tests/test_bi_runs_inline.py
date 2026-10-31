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

"""BI runs, console lane R2c (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 6): the inline wait. The proxy passes ``Prefer: respond-async, wait=N`` on (capped at
10 s) and waits N + 5 s, never the 35 s of a synchronous query; it answers 200 with the answer, 202 with the run, and 409 DRS-9082 / DRS-9089 with the
estimate, the confirm token and the run's id; without Prefer a question is synchronous exactly as before. And the page scripts' side of the contract:
B.ask sends the header and sorts the four outcomes, a report visual asks with ``interaction`` and ``supersedes``, the run card and the confirm card
say every state in words."""
import re

import pytest
from fastapi.testclient import TestClient

from bi_runs_backend import REPORT_ID, RunsBackend
from conftest import CONSOLE
from core.app import create_app
from core.config import load_settings
from routes import bi_routes

JS = CONSOLE / "web/static/js"
QUERY = f"/bi/api/reports/{REPORT_ID}/query"


@pytest.fixture()
def bi():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = RunsBackend(seed=False)
    return TestClient(app), app.state.backend


class Spy(RunsBackend):
    """Records the wait the console gave each call."""

    def __init__(self):
        super().__init__(seed=False)
        self.waits = []

    async def bi_async(self, method, path, ident, body=None, prefer="", wait=None, **params):
        self.waits.append(("async", prefer, wait))
        return await super().bi_async(method, path, ident, body, prefer, wait, **params)

    async def bi(self, method, path, ident, body=None, wait=None, **params):
        if path.endswith("/query"):
            self.waits.append(("sync", None, wait))
        return await super().bi(method, path, ident, body, **params)


def test_an_answer_in_time_is_200_with_todays_body(bi):
    client, backend = bi
    r = client.post(QUERY, json={"page": "overview", "visual": "mtm", "state": {}}, headers={"Prefer": "respond-async, wait=2"})
    assert r.status_code == 200 and r.json()["columns"][0]["name"] == "trade.desk"
    assert backend.asked[-1][2] == "respond-async, wait=2"


def test_a_question_that_needs_confirming_is_409_with_the_estimate_token_and_run(bi):
    client, backend = bi
    r = client.post(QUERY, json={"page": "overview", "visual": "mtm-by-desk-month", "state": {}}, headers={"Prefer": "respond-async, wait=2"})
    body = r.json()
    assert r.status_code == 409 and body["code"] == "DRS-9082"
    assert body["estimate"]["seconds"] == {"low": 150, "high": 330} and body["estimate"]["basis"] == "delta-stats"
    assert body["confirmToken"].startswith("ct-") and backend.runs[body["runId"]]["state"] == "awaiting-confirm"


def test_a_confirmed_question_is_202_with_the_run(bi):
    client, _ = bi
    r = client.post(QUERY, json={"page": "overview", "visual": "mtm-by-desk-month", "state": {}, "confirm": True, "confirmToken": "ct-1"}, headers={"Prefer": "respond-async, wait=2"})
    assert r.status_code == 202
    run = r.json()["run"]
    assert run["state"] in ("queued", "running") and run["can"]["cancel"] is True and run["estimate"]["why"]


def test_an_interaction_that_needs_a_run_is_409_drs_9089(bi):
    client, _ = bi
    r = client.post(QUERY, json={"page": "overview", "visual": "mtm-by-desk-month", "state": {}, "interaction": True, "supersedes": "x/overview/mtm-by-desk-month"},
                    headers={"Prefer": "respond-async, wait=2"})
    assert r.status_code == 409 and r.json()["code"] == "DRS-9089" and r.json()["estimate"]["dates"] == 182


def test_the_console_waits_the_inline_wait_and_five_seconds_capped_at_ten():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = spy = Spy()
    client = TestClient(app)
    client.post(QUERY, json={"visual": "mtm"}, headers={"Prefer": "respond-async, wait=2"})
    client.post(QUERY, json={"visual": "mtm"}, headers={"Prefer": "respond-async, wait=60"})
    client.post(QUERY, json={"visual": "mtm"}, headers={"Prefer": "respond-async"})
    client.post(QUERY, json={"visual": "mtm"})
    assert spy.waits == [("async", "respond-async, wait=2", 7.0), ("async", "respond-async, wait=10", 15.0), ("async", "respond-async, wait=2", 7.0),
                         ("sync", None, bi_routes.QUERY_WAIT_SECONDS)]


def test_without_prefer_a_question_is_synchronous_as_before(bi):
    client, backend = bi
    r = client.post(QUERY, json={"page": "overview", "visual": "mtm-by-desk-month", "state": {}})
    assert r.status_code == 200 and r.json()["rows"] and not backend.asked


def test_a_full_queue_passes_its_retry_after_on(bi):
    client, backend = bi

    async def full(*a, **k):
        from core.backend import BackendError
        e = BackendError(429, "DRS-9081", "you have 5 runs queued or running")
        e.retry_after = "30"
        raise e
    backend.bi_async = full
    r = client.post(QUERY, json={"visual": "x"}, headers={"Prefer": "respond-async, wait=2"})
    assert r.status_code == 429 and r.json()["code"] == "DRS-9081" and r.headers["retry-after"] == "30"


def test_the_prefer_header_is_read_in_any_case_and_a_get_is_never_async():
    assert bi_routes._WAIT.search("RESPOND-ASYNC, WAIT=3.5").group(1) == "3.5"
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = spy = Spy()
    TestClient(app).get("/bi/api/runs", headers={"Prefer": "respond-async, wait=2"})
    assert not [w for w in spy.waits if w[0] == "async"]


# ---- the page scripts' side ---------------------------------------------------------------------------------------------------------------

def test_b_ask_sends_prefer_and_sorts_the_four_outcomes():
    lib = (JS / "bi-lib.js").read_text(encoding="utf-8")
    body = lib[lib.index("B.ask = function"):lib.index("B.publishedBanner")]
    assert "Prefer: 'respond-async, wait=' + wait" in body
    for outcome in ("r.status === 202", "'DRS-9082'", "'DRS-9089'", "{ answer:"):
        assert outcome in body, outcome


def test_a_report_visual_asks_with_the_inline_wait_interaction_and_supersedes():
    rep = (JS / "bi-report.js").read_text(encoding="utf-8")
    fn = rep[rep.index("function ask(spec, entry, token, quiet)"):]
    fn = fn[:fn.index("\n  }\n")]
    assert "B.ask(path" in fn and "interaction" in fn and "supersedes: reportId + '/' + pageId + '/' + spec.id" in fn
    assert "R.seconds(st.inlineWait)" in fn and "R.fromUrl(spec.id" in fn and "quiet" in fn          # a live redraw stays synchronous
    board = (JS / "bi-report-board.js").read_text(encoding="utf-8")
    assert "B.ask(path" in board and "R.settle(" in board


def test_the_run_card_says_every_state_in_words_never_a_code_alone():
    runs = (JS / "bi-runs.js").read_text(encoding="utf-8")
    for code in [f"DRS-90{n}" for n in range(80, 90)]:
        assert f"'{code}'" in runs, code                          # every code of section 16 has its words
    for words in ("We'll put it on the bell when it is ready", "Open in Runs", "Run in background", "Narrow the dates", "Not now",
                  "We cannot estimate this yet", "progress is not measured"):
        assert words.replace("'", "\\'") in runs or words in runs, words
    assert re.search(r"return code \? text \+ ' \(' \+ code \+ '\)' : text", runs)         # the code follows the words, in brackets


def test_the_proxy_keeps_what_a_run_refusal_carries():
    for key in ("estimate", "confirmToken", "runId", "run"):
        assert key in bi_routes._KEPT
