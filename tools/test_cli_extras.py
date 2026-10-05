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

"""Tests of `server smoke`, `doctor`, `data ingest --watch`, `view get|explain` and `completion` (REST parts against an in-process fake server)."""
import contextlib
import io
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import drishti as D  # noqa: E402
import ingest_jsonl as IJ  # noqa: E402
import ingest_watch as W  # noqa: E402
import doctor as DR  # noqa: E402

VIEW = {"ref": {"kind": "trade", "id": "T1"}, "mnemonic": "TRD", "title": {"pill": "Trade", "id": "T1", "with": {"text": "Acme"}},
        "strip": [{"label": "Notional", "text": "USD 5"}],
        "panels": [{"id": "terms", "kind": "kv", "title": "Terms", "key": "F2", "empty": False, "data": {"fields": [{"label": "Coupon", "text": "1%"}]}},
                   {"id": "cf", "kind": "table", "title": "Flows", "empty": False,
                    "data": {"columns": ["Date", "Amt"], "numeric": [False, True], "rows": [{"cells": [{"text": "2026-01-01"}, {"text": "10"}]}]}}],
        "provenance": {"source": "s", "generation": 1, "layout": "L"}, "timings": {"total": 1.5}}
EXPLAIN = {"ref": {"kind": "trade", "id": "T1"}, "about": {"kindTitle": "Trade", "text": "A trade.", "pack": {"title": "Trading"}},
           "glossary": [{"label": "Coupon", "shownIn": ["terms"], "means": "The rate."}], "data": {"source": "s", "generation": 1},
           "layout": {"label": "Sutra t v1", "sutra": {"name": "t", "version": 1, "pack": "trading", "priority": 10, "where": "x"},
                      "candidates": [{"name": "o", "priority": 10, "where": "y", "result": "false"}]}, "next": {"keys": [{"key": "F2", "label": "Terms"}]}}


class Fake(BaseHTTPRequestHandler):
    routes: dict = {}
    seen: list = []

    def do_GET(self):                                                    # noqa: N802
        Fake.seen.append(self.path)
        path = self.path.split("?")[0]
        hit = Fake.routes.get(path)
        code, body = (200, hit) if hit is not None else (404, {"code": "DRS-1001", "detail": "nothing here"})
        if callable(body):
            code, body = body(self.path)
        raw = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *a):
        pass


def run(*argv, env=None):
    out, err = io.StringIO(), io.StringIO()
    saved = {k: os.environ.get(k) for k in (env or {})}
    os.environ.update(env or {})
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = D.main([str(a) for a in argv])
    finally:
        for k, v in saved.items():
            os.environ.pop(k, None) if v is None else os.environ.__setitem__(k, v)
    return code, out.getvalue(), err.getvalue()


class ServerCase(unittest.TestCase):
    def setUp(self):
        Fake.seen = []
        Fake.routes = {
            "/actuator/health": {"status": "UP"},
            "/api/v1/admin/packs": [{"name": "p1", "version": "1.0", "loaded": True, "enabled": True, "kinds": ["trade"]}],
            "/api/v1/admin/health": {"status": "OK", "server": {"version": "9.9", "java": "21"}, "packs": [{"name": "p1", "sutraProblems": []}]},
            "/api/v1/business-date": {"current": "2026-10-05", "previous": "2026-10-02", "earliest": "2021-10-05"},
            "/api/v1/search": {"rows": [{"ref": {"kind": "trade", "id": "T1"}}, {"ref": {"kind": "trade", "id": "T2"}}]},
            "/api/v1/views/trade/T1": VIEW, "/api/v1/views/trade/T2": VIEW, "/api/v1/views/trade/T1/explain": EXPLAIN,
            "/api/v1/me/tokens": [{"name": "ci", "active": True, "scopes": ["read"], "expiresAt": None, "lastUsedAt": "2026-10-01T00:00:00Z"}],
        }
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()


class Smoke(ServerCase):
    def test_passes_and_records_timings(self):
        code, out, _ = run("server", "smoke", "--server", self.url, "--pack", "p1", "--ids", "2", "--dates", "2", "--json")
        rep = json.loads(out)
        self.assertEqual(0, code)
        self.assertEqual(4, len(rep["views"]))                            # 2 ids x 2 dates
        self.assertEqual({"2026-10-05", "2026-10-02"}, {v["date"] for v in rep["views"]})
        self.assertTrue(all(v["ms"] is not None and v["panels"] == 2 for v in rep["views"]))
        self.assertIn("smoke: passed", run("server", "smoke", "--server", self.url)[1])

    def test_sutra_problems_fail(self):
        Fake.routes["/api/v1/admin/health"]["packs"][0]["sutraProblems"] = [{"file": "a.sutra.yaml", "problems": ["DRS-1 broken"]}]
        code, out, _ = run("server", "smoke", "--server", self.url, "--pack", "p1")
        self.assertEqual(1, code)
        self.assertIn("Sutra file(s) with problems", out)

    def test_pack_not_enabled_fails(self):
        Fake.routes["/api/v1/admin/packs"][0]["enabled"] = False
        self.assertEqual(1, run("server", "smoke", "--server", self.url, "--pack", "p1")[0])
        self.assertEqual(1, run("server", "smoke", "--server", self.url, "--pack", "nope")[0])

    def test_empty_view_fails_but_missing_earlier_date_only_skips(self):
        Fake.routes["/api/v1/views/trade/T1"] = lambda path: (404, {"detail": "no"}) if "2026-10-02" in path else (200, {"panels": []})
        code, out, _ = run("server", "smoke", "--server", self.url, "--pack", "p1", "--ids", "1", "--json")
        rows = {v["date"]: v["status"] for v in json.loads(out)["views"]}
        self.assertEqual((1, "fail", "skip"), (code, rows["2026-10-05"], rows["2026-10-02"]))

    def test_server_down_is_exit_1(self):
        self.srv.shutdown()
        self.assertEqual(1, run("server", "smoke", "--server", self.url, "--timeout", "2")[0])


class Doctor(ServerCase):
    def test_with_server_reports_version_and_token_scopes(self):
        code, out, _ = run("doctor", "--server", self.url, "--json", env={"DRISHTI_TOKEN": "drk_x", "DRISHTI_DELTA_ROOT": tempfile.gettempdir()})
        rep = {c["name"]: c for c in json.loads(out)["checks"]}
        self.assertIn("version 9.9", rep["server"]["detail"])
        self.assertIn("scopes read", rep["token"]["detail"])
        self.assertEqual("ok", rep["DRISHTI_DELTA_ROOT"]["level"])

    def test_rejected_token_is_red(self):
        Fake.routes["/api/v1/me/tokens"] = lambda p: (401, {"detail": "bad"})
        code, out, _ = run("doctor", "--server", self.url, env={"DRISHTI_TOKEN": "bad"})
        self.assertEqual(1, code)
        self.assertIn("RED", out)

    def test_directory_checks_and_exit_code(self):
        with tempfile.TemporaryDirectory() as t:
            res = DR.dir_check({"DRISHTI_FILES_ROOT": t, "DRISHTI_DELTA_ROOT": t + "/missing"})
        self.assertEqual(["warn", "ok"], [r["level"] for r in res])
        self.assertEqual(0, DR.exit_code(res))
        self.assertEqual(1, DR.exit_code([DR.item("fail", "x", "y")]))

    def test_port_probe_free_and_drishti(self):
        self.assertEqual("drishti", DR.probe(self.srv.server_address[1])[0])
        self.srv.shutdown()
        self.srv.server_close()
        self.assertEqual("free", DR.probe(self.srv.server_address[1])[0])


class View(ServerCase):
    def test_get_prints_strip_and_panels(self):
        code, out, _ = run("view", "get", "trade/T1", "--date", "2026-10-02", "--server", self.url)
        self.assertEqual(0, code)
        for text in ("Notional", "USD 5", "Terms", "Coupon", "1%", "2026-01-01"):
            self.assertIn(text, out)
        self.assertTrue(any("asOf=2026-10-02" in p for p in Fake.seen))
        self.assertEqual("T1", json.loads(run("view", "get", "trade/T1", "--json", "--server", self.url)[1])["ref"]["id"])

    def test_explain_prints_about_glossary_and_layout(self):
        out = run("view", "explain", "trade/T1", "--server", self.url)[1]
        for text in ("A trade.", "Glossary", "The rate.", "Why this layout", "Match trace", "Sutra t v1"):
            self.assertIn(text, out)

    def test_bad_ref_and_missing_view(self):
        self.assertEqual(2, run("view", "get", "nonsense", "--server", self.url)[0])
        code, _, err = run("view", "get", "trade/NOPE", "--server", self.url)
        self.assertEqual(1, code)
        self.assertIn("404", err)


class Watch(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        t = self.t = pathlib.Path(self.tmp.name)
        self.drop, self.root, self.done = t / "drop", t / "files", t / "done"
        self.drop.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def put(self, name, *docs):
        (self.drop / name).write_text("".join(json.dumps(d) + "\n" for d in docs))

    def watch(self, *extra):
        argv = ["data", "ingest", "--watch", str(self.drop), "--once", "--stable-seconds", "0", "--domain", "dom", "--key", "id", "--date", "bd",
                "--store", "files", "--root", str(self.root), *extra]
        return run(*argv)

    def rows(self, day="2026-09-01"):
        f = self.root / "dom" / day / "trade.jsonl"
        return sorted(json.loads(x)["id"] for x in f.read_text().splitlines()) if f.exists() else []

    def test_ingests_once_and_is_idempotent(self):
        self.put("trade.jsonl", {"id": "A", "bd": "2026-09-01"})
        code, out, _ = self.watch()
        self.assertEqual((0, ["A"]), (code, self.rows()))
        self.assertIn("new file trade.jsonl", out)
        state = json.loads((self.root / W.STATE_NAME).read_text())["files"]
        (entry,) = state.values()
        self.assertEqual({"size", "mtime", "sha256", "at", "rows", "bad"}, set(entry) - {"movedTo"})
        out2 = self.watch()[1]
        self.assertNotIn("ingesting", out2)
        self.assertEqual(["A"], self.rows())                              # not appended twice

    def test_changed_file_is_ingested_again_and_touch_is_not(self):
        self.put("trade.jsonl", {"id": "A", "bd": "2026-09-01"})
        self.watch("--mode", "overwrite-dates")
        p = self.drop / "trade.jsonl"
        os.utime(p, ns=(1, 1))                                            # touched, same content
        self.assertNotIn("ingesting", self.watch("--mode", "overwrite-dates")[1])
        self.put("trade.jsonl", {"id": "A", "bd": "2026-09-01"}, {"id": "B", "bd": "2026-09-01"})
        self.assertIn("changed file", self.watch("--mode", "overwrite-dates")[1])
        self.assertEqual(["A", "B"], self.rows())

    def test_done_dir_moves_files_and_new_files_append(self):
        self.put("trade.jsonl", {"id": "A", "bd": "2026-09-01"})
        self.watch("--done-dir", str(self.done))
        self.assertEqual((False, True), ((self.drop / "trade.jsonl").exists(), (self.done / "trade.jsonl").exists()))
        self.put("trade.jsonl", {"id": "B", "bd": "2026-09-01"})           # a new batch with the same name
        self.watch("--done-dir", str(self.done))
        self.assertEqual(["A", "B"], self.rows())
        self.assertEqual(2, len(list(self.done.glob("*.jsonl"))))

    def test_failure_is_not_recorded_and_exits_1(self):
        self.put("trade.jsonl", {"id": "A", "bd": "2026-09-01"})
        (self.drop / "trade.jsonl").write_text('{"id": "A", "bd": "2026-09-01"}\nnot json\n')
        code, out, _ = self.watch()
        self.assertEqual(1, code)                                         # a bad line is reported
        self.assertIn("bad line", out)

        def boom(*a, **k):
            raise SystemExit("disk full")
        a = IJ.build_parser().parse_args(["--watch", str(self.drop), "--once", "--stable-seconds", "0", "--domain", "d", "--store", "files", "--root", str(self.root)])
        state = W.State(self.t / "state.json")
        bad = W.one_pass(a, boom, state, {}, lambda *_: None)
        self.assertEqual((0, 1), bad)
        self.assertEqual({}, state.files)                                 # nothing remembered: it is tried again

    def test_unfinished_file_is_waited_for(self):
        p = self.drop / "x.jsonl"
        p.write_text("{}\n")
        self.assertEqual([], W.stable([p], 1, sleep=lambda s: p.write_text("{}\n{}\n")))
        self.assertEqual([p], W.stable([p], 1, sleep=lambda s: None))


class Completion(unittest.TestCase):
    def test_scripts_cover_the_tree(self):
        for shell in ("bash", "zsh", "powershell"):
            code, out, _ = run("completion", shell)
            self.assertEqual(0, code)
            for word in ("smoke", "doctor", "--watch", "explain", "completion"):
                self.assertIn(word, out, shell)

    def test_bash_script_is_valid_and_completes(self):
        with tempfile.TemporaryDirectory() as t:
            script = pathlib.Path(t) / "c.bash"
            script.write_text(run("completion", "bash")[1])
            test = pathlib.Path(t) / "t.sh"
            test.write_text('source "$1"\nCOMP_WORDS=(drishti.py server s); COMP_CWORD=2; _drishti_complete; echo "A:${COMPREPLY[@]}"\n'
                            'COMP_WORDS=(drishti.py data ingest --store ""); COMP_CWORD=4; _drishti_complete; echo "B:${COMPREPLY[@]}"\n')
            self.assertEqual(0, subprocess.run(["bash", "-n", str(script)]).returncode)
            r = subprocess.run(["bash", str(test), str(script)], capture_output=True, text=True, cwd=D.ROOT)
        self.assertIn("A:smoke", r.stdout)
        self.assertIn("B:delta files", r.stdout)


if __name__ == "__main__":
    unittest.main()
