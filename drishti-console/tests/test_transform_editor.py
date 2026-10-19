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

"""Wave C (2.4.0) data prep on the console (docs/guides/PYTHON_BI.md section 17, BI_PARITY_PLAN.md P1 to P3 and P5) and the browser Python console's server
side (docs/guides/ANALYSE.md section 15):

* the snippets library: every snippet's own example is right in plain pandas **and** in the sandbox, a value is always a literal (never code), an
  ``input`` parameter must be one of the Transform's inputs;
* the editor's documents: a spec is written as a Python item and a Python dataset and read back as the same steps; hand-written YAML opens as steps;
* ``/bi/transform/steps`` asks each input of the server as the reader (the preview's row limit, the masked columns) and runs the steps in the sandbox;
  without bi-author it runs nothing;
* ``/pyconsole``: not a BI page, the strict policy, Calc's worker; its extract is the reader's (masked), and a promotion runs the same code on the console
  backend over the extract rebuilt for the reader and keeps it as a notebook draft."""
import asyncio
import io
from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from bi_backend import BiBackend  # noqa: E402
from conftest import CONSOLE  # noqa: E402
from core.app import CSP, create_app  # noqa: E402
from core.config import load_settings  # noqa: E402
from core.prep_snippets import SnippetError, SnippetLibrary, frame, plain, run_example  # noqa: E402
from core.transform_editor import EditorError, compose, parse  # noqa: E402

LIBRARY = SnippetLibrary([CONSOLE / "config" / "prep-snippets.yaml"])


def arrow(cols: dict) -> bytes:
    t = pa.table(cols)
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, t.schema) as w:
        w.write_table(t)
    return sink.getvalue()


TRADES = {"TRADE_ID": ["T1", "T2", "T3"], "DESK": ["DESK-FX", "DESK-FX", "DESK-RATES"], "NOTIONAL": ["1,000", "2,000", "500"]}


class PrepBackend(BiBackend):
    """Plays the server: an input is the reader's rows (bob sees DESK-FX only, NOTIONAL masked); items saved are recorded."""

    def __init__(self, powers=("bi", "bi-author")):
        super().__init__(powers)
        self.reader, self.asked, self.saved = "ann", [], []

    async def bi_arrow(self, path, ident, body, **params):
        self.asked.append((path, body, params, ident.user))
        if self.reader == "bob":
            rows = {k: [v for v, d in zip(vals, TRADES["DESK"]) if d == "DESK-FX"] for k, vals in TRADES.items()}
            rows["NOTIONAL"] = [None] * len(rows["DESK"])
            return arrow(rows), {"X-Bi-Rows": str(len(rows["DESK"])), "X-Bi-Masked": "NOTIONAL" if path == "/python/input" else "trade.NOTIONAL", "X-Bi-Model": "dataset:pack.trading.trade"}
        if path == "/mq":
            return arrow({"trade." + k: v for k, v in TRADES.items()}), {"X-Bi-Rows": "3", "X-Bi-Masked": ""}
        return arrow(TRADES), {"X-Bi-Rows": "3", "X-Bi-Masked": "", "X-Bi-Model": "dataset:pack.trading.trade", "X-Bi-As-Of": "2026-10-08"}

    async def bi(self, method, path, ident, body=None, **params):
        if method == "POST" and path == "/workspaces/me/items":
            self.saved.append(body)
            return {"id": "bi-nb-1", "kind": body["kind"], "title": body["title"]}
        return await super().bi(method, path, ident, body, **params)


@pytest.fixture()
def made():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = PrepBackend()
    yield TestClient(app), app.state.backend
    asyncio.run(app.state.pyrunner.close())


SPEC = {"title": "Desk notional", "inputs": [{"name": "trades", "input": {"kind": "trade", "pack": "trading", "fields": ["TRADE_ID", "DESK", "NOTIONAL"]}}],
        "steps": [{"label": "Start from trades", "code": "df = trades.copy()"},
                  {"label": "Notional as a number", "code": LIBRARY.render("type-fix-number", {"column": "NOTIONAL"}, ["trades"])["code"]},
                  {"label": "Total per desk", "code": LIBRARY.render("group-total", {"by": ["DESK"], "columns": ["NOTIONAL"]}, ["trades"])["code"]}]}


# ---- the snippets library ---------------------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("sid", sorted(LIBRARY.snippets))
def test_each_snippets_example_is_right_in_pandas(sid):
    ex = LIBRARY.get(sid)["example"]
    columns, rows = run_example(LIBRARY, sid)
    assert columns == ex["expect"]["columns"]
    assert rows == [[plain(v) for v in r] for r in ex["expect"]["rows"]]


def test_every_snippets_example_is_right_in_the_sandbox(made):
    """Each snippet as the step of a Transform, run by the console's sandboxed runner: the frame after the step is the example's."""
    client, _ = made
    runner = client.app.state.pyrunner
    for sid, s in sorted(LIBRARY.snippets.items()):
        ex = s["example"]
        given = list(ex["inputs"])
        names = ["base" if n == "df" else n for n in given]                        # the example's df is the Transform's first input
        step = LIBRARY.render(sid, ex.get("params") or {}, [n for n in names if n != "base"])
        spec = {"title": sid, "inputs": [{"name": n, "input": {"kind": "x"}} for n in names],
                "steps": [{"label": "Start", "code": "df = base.copy()"}, {"label": sid, "code": step["code"]}]}
        source = compose(spec)["source"]
        blobs = [_arrow_of(frame(ex["inputs"][n])) for n in given]
        m = {"name": sid, "interface": "transform", "entry": "run", "inputs": names, "packages": ["pandas", "numpy"], "timeout": 20, "memory": 512, "params": {}, "source": source}
        a = asyncio.run(runner.run_steps(m, blobs, names))
        assert a["complete"], (sid, a.get("failed"))
        last = a["steps"][-1]
        assert last["head"]["columns"] == ex["expect"]["columns"], sid
        assert [[plain(v) for v in r] for r in last["head"]["rows"]] == [[plain(v) for v in r] for r in ex["expect"]["rows"]], sid


def _arrow_of(f) -> bytes:
    t = pa.Table.from_pandas(f.astype(object).where(f.notna(), None) if len(f.columns) else f, preserve_index=False)
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, t.schema) as w:
        w.write_table(t)
    return sink.getvalue()


def test_a_value_is_a_literal_never_code():
    evil = 'x"]; import os; os.system("id"); df["'
    code = LIBRARY.render("type-fix-number", {"column": evil}, ["trades"])["code"]
    assert "import os" in code and code.count(repr(evil)) == 2                  # the whole value is one quoted string
    compile(code, "<t>", "exec")
    with pytest.raises(SnippetError, match="one of the Transform's inputs"):
        LIBRARY.render("append", {"other": "__import__('os')"}, ["trades"])
    with pytest.raises(SnippetError, match="one of"):
        LIBRARY.render("calculated-column", {"left": "a", "op": "**__import__('os')", "right": "b", "out": "c"}, [])
    with pytest.raises(SnippetError, match="fill in"):
        LIBRARY.render("keep-columns", {}, [])
    step = LIBRARY.render("append", {"other": "prices"}, ["trades", "prices"])
    assert step["code"] == "df = pd.concat([df, prices], ignore_index=True, sort=False)" and step["label"] == "Append another input (union) (prices)"


def test_the_library_lists_the_parity_snippets():
    ids = {s["id"] for s in LIBRARY.list()}
    assert {"append", "unpivot", "pivot", "fill-down", "split-column", "replace-values", "dedupe", "type-fix-number", "type-fix-date", "asof-prices", "asof-fx",
            "business-date", "tenor-bucket", "join"} <= ids


# ---- the editor's documents ---------------------------------------------------------------------------------------------------------------
def test_a_spec_is_written_as_two_items_and_read_back_as_the_same_steps():
    c = compose(SPEC)
    item, ds = yaml.safe_load(c["python"]), yaml.safe_load(c["dataset"])
    assert item["interface"] == "transform" and item["inputs"] == ["trades"] and item["python"] == "desk-notional" and item["target"] == "server"
    assert "    # step: Notional as a number" in item["source"] and item["source"].rstrip().endswith("return df")
    assert ds["rachana"] == 2 and ds["source"]["python"] == "desk-notional" and ds["source"]["inputs"]["trades"]["kind"] == "trade"
    back = parse(c["python"], c["dataset"])
    assert back["steps"] == c["spec"]["steps"] and back["inputs"] == c["spec"]["inputs"] and back["title"] == "Desk notional"


def test_hand_written_yaml_opens_as_steps():
    text = yaml.safe_dump({"rachana": 1, "python": "old", "interface": "transform", "inputs": ["a"], "source":
                           "import pandas as pd\n\nHELPER = 2\n\ndef run(a, params):\n    df = a.head(HELPER)\n    return df[['x']]\n"})
    spec = parse(text)
    assert spec["steps"] == [{"label": "Start", "code": "df = a.head(HELPER)\ndf = df[['x']]"}] and spec["prelude"] == "HELPER = 2"
    with pytest.raises(EditorError, match="not a Python Transform"):
        parse("rachana: 1\ninterface: measure\n")
    with pytest.raises(EditorError, match="Python name"):
        compose({"inputs": [{"name": "df", "input": {"kind": "t"}}]})


# ---- the routes -----------------------------------------------------------------------------------------------------------------------------
def test_the_editor_page_keeps_the_strict_policy(made):
    client, _ = made
    r = client.get("/bi/transform")
    assert r.status_code == 200 and r.headers["content-security-policy"] == CSP
    assert 'id="tx"' in r.text and "bi-transform.js" in r.text and "calc-worker" not in r.text
    s = client.get("/bi/transform/snippets").json()["snippets"]
    assert any(x["id"] == "asof-fx" and any(p["kind"] == "input" for p in x["params"]) for x in s)


def test_the_steps_run_on_the_readers_inputs(made):
    client, backend = made
    a = client.post("/bi/transform/steps", json={"spec": SPEC}).json()
    path, body, params, user = backend.asked[0]
    assert path == "/python/input" and params == {"format": "arrow"} and body["limit"] == 1000 and body["input"]["kind"] == "trade" and user == "ash"
    assert a["complete"] and [s["rows"] for s in a["steps"]] == [3, 3, 2]
    assert a["steps"][-1]["head"]["rows"] == [["DESK-FX", 3000], ["DESK-RATES", 500]]
    assert a["inputs"][0]["model"] == "dataset:pack.trading.trade" and a["inputs"][0]["asOf"] == "2026-10-08"
    backend.reader = "bob"                                                     # another reader: their rows, the masked column empty
    b = client.post("/bi/transform/steps", json={"spec": SPEC}).json()
    assert b["inputs"][0]["masked"] == ["NOTIONAL"] and b["steps"][-1]["head"]["rows"] == [["DESK-FX", 0.0]]


def test_without_bi_author_no_step_runs(made):
    client, backend = made
    backend.powers = ["bi"]
    client.app.state.bi_me = type(client.app.state.bi_me)()                   # forget the cached powers
    r = client.post("/bi/transform/steps", json={"spec": SPEC})
    assert r.status_code == 403 and r.json()["code"] == "DRS-9073" and not backend.asked
    assert client.post("/bi/transform/compose", json={"spec": SPEC}).status_code == 200      # the documents are still written


# ---- the browser Python console's server side ---------------------------------------------------------------------------------------------
def test_the_console_page_is_not_a_bi_page_and_keeps_the_strict_policy(made):
    client, _ = made
    r = client.get("/pyconsole")
    assert r.status_code == 200 and r.headers["content-security-policy"] == CSP and "wasm" not in r.headers["content-security-policy"]
    assert "/static/js/calc-worker.js" in r.text and 'id="pc"' in r.text                                # Calc's worker, the one script that compiles WebAssembly
    assert not (CONSOLE / "web/templates/bi/pyconsole.html").exists()


def test_the_browser_gets_the_readers_masked_extract(made):
    client, backend = made
    spec = {"dataset": "dataset:pack.trading.trade", "fields": ["trade.TRADE_ID", "trade.DESK", "trade.NOTIONAL"]}
    a = client.post("/pyconsole/extract", json={"extract": spec}).json()
    assert a["rowCount"] == 3 and a["masked"] == [] and a["maxRows"] == 20000
    assert backend.asked[-1][1]["limit"] == 20000 and backend.asked[-1][1]["shape"] == "rows"
    backend.reader = "bob"
    b = client.post("/pyconsole/extract", json={"extract": spec}).json()
    assert b["rowCount"] == 2 and b["masked"] == ["NOTIONAL"] and all(r[2] is None for r in b["rows"])


def test_a_promoted_result_runs_on_the_backend_and_is_kept_as_a_notebook(made):
    client, backend = made
    spec = {"dataset": "dataset:pack.trading.trade", "fields": ["trade.DESK", "trade.NOTIONAL"]}
    code = 'df["NOTIONAL"] = pd.to_numeric(df["NOTIONAL"].str.replace(",", ""))\nimport pandas as pd\nshow(df.groupby("DESK")["NOTIONAL"].sum(), "By desk")\nchart(df, kind="bar")\n'
    code = "import pandas as pd\n" + code
    p = client.post("/pyconsole/promote", json={"extract": spec, "code": code, "title": "Desk notional"}).json()
    assert p["target"] == "server" and p["inputRows"] == 3
    assert p["answer"]["table"]["columns"] == ["DESK", "NOTIONAL"] and p["answer"]["table"]["rows"] == [["DESK-FX", 3000], ["DESK-RATES", 500]]
    nb = backend.saved[-1]
    assert nb["kind"] == "notebook" and nb["title"] == "Desk notional" and p["item"]["href"] == "/bi/explore?item=bi-nb-1"
    doc = yaml.safe_load(nb["body"])
    assert doc["dataset"] == "dataset:pack.trading.trade" and doc["cells"][0]["target"] == "server" and "show(" in doc["cells"][0]["source"]
    failing = client.post("/pyconsole/promote", json={"extract": spec, "code": "1/0\n", "title": "Bad"})
    assert failing.status_code == 422 and failing.json()["code"] == "DRS-9071" and len(backend.saved) == 1        # a failing code is not kept
    backend.powers = ["bi"]
    client.app.state.bi_me = type(client.app.state.bi_me)()
    refused = client.post("/pyconsole/promote", json={"extract": spec, "code": code})
    assert refused.status_code == 403 and refused.json()["code"] == "DRS-9073"


def test_the_sandbox_still_refuses_in_a_promoted_cell(made):
    client, _ = made
    spec = {"dataset": "dataset:pack.trading.trade", "fields": ["trade.DESK"]}
    r = client.post("/pyconsole/promote", json={"extract": spec, "code": "import socket\nsocket.socket()\n"})
    assert r.status_code in (403, 422) and r.json()["code"] in ("DRS-9073", "DRS-9071")
    r = client.post("/pyconsole/promote", json={"extract": spec, "code": "open('/etc/passwd').read()\n"})
    assert r.status_code == 403 and r.json()["code"] == "DRS-9073"


def test_the_preview_rows_setting_is_read(made):
    client, _ = made
    from routes.transform_routes import EditorSettings
    e = EditorSettings(load_settings(CONSOLE / "config"))
    assert e.preview_rows == 1000 and e.timeout == 20 and len(e.snippets.snippets) >= 20
    assert Path(CONSOLE / "config" / "prep-snippets.yaml").read_text(encoding="utf-8").startswith("# Project Drishti")
