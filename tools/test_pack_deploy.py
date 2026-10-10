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

"""Tests of `server packs deploy|history|rollback|datasource` (against an in-process fake server) and of the shared pack-diff cases."""
import contextlib
import io
import json
import os
import pathlib
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import drishti as D  # noqa: E402
import packbundle as PB  # noqa: E402
import packdiff as PD  # noqa: E402

CASES = pathlib.Path(__file__).resolve().parent / "testdata" / "packdiff"


def verification(ok=True, breaking=0, **kw):
    rep = {"ok": ok, "file": "my-1.1.0.tar.gz", "size": 100, "sha256": "ab" * 32,
           "checks": [{"name": "archive", "ok": True, "detail": "100 bytes", "warn": False},
                      {"name": "manifest", "ok": ok, "detail": "all good" if ok else "checksum mismatch: sutras/a.sutra.yaml", "warn": False},
                      {"name": "signature", "ok": True, "detail": "not signed", "warn": True}],
           "pack": "my", "version": "1.1.0",
           "preview": {"pack": "my", "newVersion": "1.1.0", "state": "upgrade", "running": {"version": "1.0.0", "loaded": True, "where": "loaded"}, "versionOrder": "newer",
                       "counts": {"breaking": breaking, "selection": 0, "layout": 0, "change": 1},
                       "findings": ([{"level": "breaking", "what": "kind removed", "name": "swap", "detail": "monitors stop"}] if breaking else [])
                       + [{"level": "change", "what": "version", "name": "my", "detail": "1.0.0 -> 1.1.0"}]}}
    if ok:
        rep["uploadId"] = "up123"
    rep.update(kw)
    return rep


DS = {"pack": "my", "directory": "/srv/config/connectors", "missing": ["thing-stream"],
      "connectors": [{"name": "store", "defined": True, "origin": "file", "state": "RUNNING", "plugin": "delta", "kinds": ["trade"], "problems": []},
                     {"name": "thing-stream", "defined": False, "state": "NOT_CONFIGURED", "kinds": ["thing"], "problems": ["connector thing-stream is not configured"]}]}


class Fake(BaseHTTPRequestHandler):
    routes: dict = {}
    seen: list = []

    def _go(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else b""
        Fake.seen.append({"method": self.command, "path": self.path, "body": body, "headers": dict(self.headers)})
        hit = Fake.routes.get((self.command, self.path.split("?")[0]))
        code, out = (200, hit) if hit is not None else (404, {"code": "DRS-1001", "detail": "nothing here"})
        if callable(out):
            code, out = out(self)
        raw = json.dumps(out).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    do_GET = do_POST = do_PUT = do_DELETE = _go                           # noqa: N815

    def log_message(self, *a):
        pass


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = D.main([str(a) for a in argv])
    return code, out.getvalue(), err.getvalue()


class ServerCase(unittest.TestCase):
    def setUp(self):
        Fake.seen = []
        Fake.routes = {}
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"
        self.tmp = tempfile.TemporaryDirectory()
        self.archive = pathlib.Path(self.tmp.name) / "my-1.1.0.tar.gz"
        self.archive.write_bytes(b"not really a tar, the fake server does not open it")

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.tmp.cleanup()

    def calls(self, method):
        return [c for c in Fake.seen if c["method"] == method]


class Deploy(ServerCase):
    def test_preview_shows_the_checks_and_the_changes_then_discards_the_upload(self):
        Fake.routes[("POST", "/api/v1/admin/packs/deploy")] = verification(breaking=1)
        Fake.routes[("DELETE", "/api/v1/admin/packs/deploy/up123")] = {"discarded": True}
        code, out, _ = run("server", "packs", "deploy", self.archive, "--preview", "--server", self.url)
        self.assertEqual(0, code)
        for text in ("ok    archive", "FAIL" not in out and "warn  signature", "BREAKING (1)", "kind removed", "swap", "preview only: nothing was deployed", "1.0.0 (loaded) -> 1.1.0 (newer)"):
            self.assertTrue(text and text in out or text is True, f"{text!r} not in:\n{out}")
        up = self.calls("POST")[0]
        self.assertEqual("application/octet-stream", up["headers"]["Content-Type"])           # the archive itself is the body
        self.assertEqual(self.archive.read_bytes(), up["body"])
        self.assertEqual("my-1.1.0.tar.gz", up["headers"]["X-Drishti-Filename"])
        self.assertEqual(1, len(self.calls("DELETE")))                                          # discarded, never confirmed
        self.assertEqual(1, len(self.calls("POST")))

    def test_a_refused_archive_exits_1_and_stages_nothing(self):
        Fake.routes[("POST", "/api/v1/admin/packs/deploy")] = verification(ok=False)
        code, out, _ = run("server", "packs", "deploy", self.archive, "--server", self.url)
        self.assertEqual(1, code)
        self.assertIn("FAIL  manifest", out)
        self.assertIn("checksum mismatch", out)
        self.assertIn("refused: nothing was changed", out)
        self.assertEqual([], self.calls("DELETE"))

    def test_breaking_changes_need_accept_breaking(self):
        Fake.routes[("POST", "/api/v1/admin/packs/deploy")] = verification(breaking=1)
        Fake.routes[("DELETE", "/api/v1/admin/packs/deploy/up123")] = {"discarded": True}
        code, out, _ = run("server", "packs", "deploy", self.archive, "--server", self.url)
        self.assertEqual(1, code)
        self.assertIn("run again with --accept-breaking", out)
        self.assertEqual(1, len(self.calls("DELETE")))
        Fake.seen.clear()
        Fake.routes[("POST", "/api/v1/admin/packs/deploy/up123")] = {"deployed": "1.1.0", "previous": "1.0.0", "restarting": False, "note": "Saved."}
        code, out, _ = run("server", "packs", "deploy", self.archive, "--accept-breaking", "--server", self.url)
        self.assertEqual(0, code)
        self.assertIn("deployed my 1.1.0 (replacing 1.0.0)", out)
        confirm = [c for c in self.calls("POST") if c["path"].startswith("/api/v1/admin/packs/deploy/up123")][0]
        self.assertIn("acceptBreaking=true", confirm["path"])

    def test_a_clean_upload_is_confirmed_without_flags(self):
        Fake.routes[("POST", "/api/v1/admin/packs/deploy")] = verification()
        Fake.routes[("POST", "/api/v1/admin/packs/deploy/up123")] = {"deployed": "1.1.0", "previous": None, "restarting": True, "note": "The server restarts in place now."}
        code, out, _ = run("server", "packs", "deploy", self.archive, "--server", self.url, "--json")
        self.assertEqual(0, code)
        res = json.loads(out)
        self.assertTrue(res["verification"]["ok"])
        self.assertEqual("1.1.0", res["result"]["deployed"])
        self.assertEqual([], self.calls("DELETE"))

    def test_the_sidecar_checksum_is_sent_and_a_changed_file_is_caught_before_the_upload(self):
        good = PB.sha256_file(self.archive)
        (self.archive.with_name(self.archive.name + ".sha256")).write_text(f"{good}  {self.archive.name}\n")
        Fake.routes[("POST", "/api/v1/admin/packs/deploy")] = verification(ok=False)
        run("server", "packs", "deploy", self.archive, "--preview", "--server", self.url)
        self.assertEqual(good, self.calls("POST")[0]["headers"]["X-Drishti-Sha256"])
        Fake.seen.clear()
        self.archive.write_bytes(b"changed after bundling")
        code, _, err = run("server", "packs", "deploy", self.archive, "--preview", "--server", self.url)
        self.assertEqual(1, code)
        self.assertIn("sha256 does not match", err)
        self.assertEqual([], Fake.seen)                                                         # nothing was sent

    def test_a_missing_archive_is_a_usage_error_and_a_403_explains_the_scope(self):
        self.assertEqual(2, run("server", "packs", "deploy", "/no/such/archive.tar.gz", "--server", self.url)[0])
        Fake.routes[("POST", "/api/v1/admin/packs/deploy")] = lambda h: (403, {"code": "DRS-5002", "detail": "this token lacks the scope packs:admin"})
        code, _, err = run("server", "packs", "deploy", self.archive, "--server", self.url)
        self.assertEqual(1, code)
        self.assertIn("packs:admin", err)


class History(ServerCase):
    def test_history_and_rollback(self):
        Fake.routes[("GET", "/api/v1/admin/packs/history")] = {"history": [
            {"at": "2026-10-05T10:00:00Z", "action": "deploy", "pack": "my", "version": "1.1.0", "previous": "1.0.0", "by": "ann"}],
            "kept": {"my": [{"version": "1.0.0", "keptAt": "x"}, {"version": "shipped"}]}}
        code, out, _ = run("server", "packs", "history", "--pack", "my", "--server", self.url)
        self.assertEqual(0, code)
        for text in ("2026-10-05 10:00:00", "deploy", "1.1.0", "ann", "kept for rollback, my: 1.0.0, shipped"):
            self.assertIn(text, out)
        self.assertIn("pack=my", Fake.seen[0]["path"])
        Fake.routes[("POST", "/api/v1/admin/packs/my/rollback")] = {"restored": "1.0.0", "replaced": "1.1.0", "note": "Saved."}
        code, out, _ = run("server", "packs", "rollback", "my", "--version", "1.0.0", "--server", self.url)
        self.assertEqual(0, code)
        self.assertIn("rolled my back to 1.0.0 (replacing 1.1.0)", out)
        self.assertIn("version=1.0.0", self.calls("POST")[0]["path"])


class DataSource(ServerCase):
    def setUp(self):
        super().setUp()
        Fake.routes[("GET", "/api/v1/admin/packs/my/datasource")] = DS

    def test_get_lists_the_packs_connectors_and_exits_1_while_one_is_not_configured(self):
        code, out, _ = run("server", "packs", "datasource", "get", "my", "--server", self.url)
        self.assertEqual(1, code)
        for text in ("connectors of my", "/srv/config/connectors", "store", "running", "delta", "file", "thing-stream", "nothing defines it",
                     "thing-stream is not configured: create it with `drishti.py connector apply thing-stream.yaml`"):
            self.assertIn(text, out)
        done = dict(DS, missing=[], connectors=DS["connectors"][:1])
        Fake.routes[("GET", "/api/v1/admin/packs/my/datasource")] = done
        self.assertEqual(0, run("server", "packs", "datasource", "get", "my", "--server", self.url)[0])

    def test_the_edit_commands_are_gone(self):
        for sub in ("set", "test", "reset"):
            self.assertEqual(2, run("server", "packs", "datasource", sub, "my", "--server", self.url)[0])


class SharedDiffCases(unittest.TestCase):
    def test_the_python_diff_matches_the_expected_findings_the_java_diff_also_reads(self):
        import yaml
        old, new = PD.load_pack(CASES / "old", yaml), PD.load_pack(CASES / "new", yaml)
        got = [{k: f[k] for k in ("level", "what", "name", "detail")} for f in PD.diff_packs(old, new)]
        self.assertEqual(json.loads((CASES / "expected.json").read_text(encoding="utf-8")), got)


if __name__ == "__main__":
    unittest.main()
