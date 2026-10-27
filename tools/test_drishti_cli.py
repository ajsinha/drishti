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

"""Tests of tools/drishti.py: the argparse wiring of every command, each group against fakes (a fake `java`, an in-process
HTTP server standing in for the Drishti server), and one real `sutra lint` on a shipped pack when the exec jar is built."""
import contextlib
import io
import json
import os
import pathlib
import shutil
import stat
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import drishti as D  # noqa: E402

ROOT = D.ROOT


def run(*argv, env=None):
    """main(argv) -> (exit code, stdout, stderr); the environment is restored afterwards."""
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


class Wiring(unittest.TestCase):
    COMMANDS = [("sutra", c) for c in ("shape", "design", "lint", "test", "preview", "gen")] + \
               [("pack", c) for c in ("new", "check", "about-check", "publish", "keygen", "verify", "install")] + \
               [("data", "ingest"), ("data", "load"), ("data", "landed"), ("data", "loads"),("server", "smoke"), ("view", "get"), ("view", "explain"), ("server", "health"), ("server", "packs"), ("docs", "shots"), ("bi", "workspace"), ("bi", "catalogue")] + \
               [("lake", c) for c in ("build", "scan", "status", "maintain")] + \
               [("design", c) for c in ("list", "create", "get", "save", "check", "propose", "approve", "reject", "proposals", "export", "import",
                                        "bind", "autodesign", "delete")]

    def test_every_command_has_help(self):
        for group, cmd in self.COMMANDS:
            with self.subTest(command=f"{group} {cmd}"):
                code, out, _ = run(group, cmd, "--help")
                self.assertEqual(0, code)
                self.assertIn(f"drishti.py {group} {cmd}", out)

    def test_pack_actions_have_help(self):
        for act in ("list", "load", "unload", "on", "off"):
            self.assertEqual(0, run("server", "packs", act, "--help")[0])

    def test_usage_errors_exit_2(self):
        for argv in (["nonsense"], ["pack"], ["pack", "check"], ["data", "load"], ["data", "load", "--store", "nope"], ["design", "get"]):
            with self.subTest(argv=argv):
                self.assertEqual(2, run(*argv)[0])

    def test_unrecognised_option_is_usage_but_sutra_passes_it_on(self):
        self.assertEqual(2, run("pack", "check", "x", "--bogus")[0])
        self.assertEqual(2, run("server", "health", "--bogus")[0])


def fake_java(directory: pathlib.Path) -> pathlib.Path:
    """A `java` that logs its arguments and exits with $FAKE_EXIT after printing $FAKE_OUT (stdout) and $FAKE_ERR (stderr)."""
    f = directory / "java"
    f.write_text('#!/bin/sh\necho "$@" >> "$FAKE_LOG"\n[ -n "$FAKE_OUT" ] && echo "$FAKE_OUT"\n[ -n "$FAKE_ERR" ] && echo "$FAKE_ERR" >&2\nexit ${FAKE_EXIT:-0}\n')
    f.chmod(f.stat().st_mode | stat.S_IEXEC)
    return f


class Tools(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.java = fake_java(self.tmp)
        self.jar = self.tmp / "x-exec.jar"
        self.jar.write_text("jar")
        self.log = self.tmp / "calls.log"
        self.env = {"FAKE_LOG": str(self.log), "FAKE_EXIT": "0", "FAKE_OUT": "", "FAKE_ERR": "", "DRISHTI_TMP_DIR": str(self.tmp / "jvm")}
        self.pack = self.tmp / "my-pack"
        (self.pack / "config").mkdir(parents=True)
        (self.pack / "pack.yaml").write_text("pack: my-pack\nkinds: [trade]\ningest:\n  trade: {key: tradeId, date: businessDate}\n")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def jvm(self):
        return ["--java", self.java, "--jar", self.jar]

    def calls(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def test_sutra_lint_runs_the_jar_with_absolute_paths_and_returns_its_exit_code(self):
        code, _, _ = run("sutra", "lint", self.pack, "--strict", *self.jvm(), env=self.env)
        self.assertEqual(0, code)
        # -Djava.io.tmpdir= first (tools/javacmd.py): the server's temp base, here DRISHTI_TMP_DIR, made before java starts
        self.assertEqual([f"-Djava.io.tmpdir={(self.tmp / 'jvm').resolve()} -jar {self.jar} sutra lint {self.pack} --strict"], self.calls())
        self.assertTrue((self.tmp / "jvm").is_dir())
        self.assertEqual(1, run("sutra", "test", self.pack, "--junit", self.tmp / "t.xml", *self.jvm(), env={**self.env, "FAKE_EXIT": "1"})[0])
        self.assertIn(f"sutra test {self.pack} --junit {self.tmp / 't.xml'}", self.calls()[-1])

    def test_sutra_design_each_and_extra_options_pass_through(self):
        run("sutra", "design", self.tmp, "--each", "--kind", "ticket", "--out", self.tmp / "o", "--future-flag", "x", *self.jvm(), env=self.env)
        self.assertIn("sutra design", self.calls()[0])
        self.assertIn("--each", self.calls()[0])
        self.assertIn("--kind ticket", self.calls()[0])
        self.assertTrue(self.calls()[0].endswith("--future-flag x"))

    def test_a_missing_jar_is_a_usage_error_with_the_fix(self):
        code, _, err = run("sutra", "lint", self.pack, "--jar", self.tmp / "none.jar", "--java", self.java, env=self.env)
        self.assertEqual(2, code)
        self.assertIn("jar not found", err)

    def test_pack_check_runs_lint_and_test_and_reports_json(self):
        code, out, _ = run("pack", "check", self.pack, "--strict", "--junit", self.tmp / "rep", "--json", *self.jvm(), env={**self.env, "FAKE_OUT": "ok x"})
        self.assertEqual(0, code)
        doc = json.loads(out)
        self.assertTrue(doc["ok"])
        self.assertEqual({"lint", "test"}, set(doc["packs"][0]["steps"]))
        calls = self.calls()
        self.assertIn("sutra lint", calls[0])
        self.assertIn("--strict", calls[0])
        self.assertIn(f"--junit {self.tmp / 'rep' / 'my-pack-lint.xml'}", calls[0])
        self.assertIn(f"--junit {self.tmp / 'rep' / 'my-pack-test.xml'}", calls[1])

    def test_pack_check_fails_with_exit_1_and_says_which_step(self):
        code, out, _ = run("pack", "check", self.pack, *self.jvm(), env={**self.env, "FAKE_EXIT": "1", "FAKE_OUT": "boom"})
        self.assertEqual(1, code)
        self.assertIn("sutra lint: exit 1", out)

    def test_pack_check_validates_the_ingest_block(self):
        (self.pack / "pack.yaml").write_text("pack: my-pack\nkinds: [trade]\ningest:\n  ghost: {key: a, date: b}\n  trade: {key: tradeId}\n")
        code, _, err = run("pack", "check", self.pack, *self.jvm(), env=self.env)
        self.assertEqual(1, code)
        self.assertIn("'ghost' is not one of the pack's kinds", err)
        self.assertIn("ingest.trade: needs key", err)

    def test_pack_check_refuses_a_reserved_command_word_as_a_mnemonic(self):
        for word in ("BI", "bi", "RUPAKA", "rupaka", "FORM", "FUNC", "func", "DATAMAP", "datamap", "WHAT", "what"):
            (self.pack / "pack.yaml").write_text(f"pack: my-pack\nkinds: [trade]\nmnemonics:\n  {word}: {{kind: trade}}\n")
            code, _, err = run("pack", "check", self.pack, *self.jvm(), env=self.env)
            self.assertEqual(1, code, word)
            self.assertIn(f"mnemonics.{word}: '{word}' is a reserved command word", err)
        (self.pack / "pack.yaml").write_text("pack: my-pack\nkinds: [trade]\nmnemonics:\n  TRD: {kind: trade}\n")
        self.assertEqual(0, run("pack", "check", self.pack, *self.jvm(), env=self.env)[0])

    def test_pack_check_needs_a_pack_folder(self):
        self.assertEqual(2, run("pack", "check", self.tmp, *self.jvm(), env=self.env)[0])

    def test_about_check_lists_fields_without_glossary_text(self):
        warn = f"{self.pack}/sutras/trade/t.v1.sutra.yaml:16 warning DRS-2047 field 'mtm' is shown but has no glossary entry (x)"
        code, out, _ = run("pack", "about-check", self.pack, "--json", *self.jvm(), env={**self.env, "FAKE_ERR": warn})
        self.assertEqual(1, code)
        self.assertEqual(["mtm"], json.loads(out)["packs"][0]["sutras"]["t.v1"]["missing"])
        self.assertEqual(0, run("pack", "about-check", self.pack, *self.jvm(), env=self.env)[0])

    def test_about_check_strict_counts_todo_placeholders(self):
        (self.pack / "config" / "about.yaml").write_text("about: 1\nkinds:\n  trade:\n    about: 'TODO: say'\n    glossary:\n      mtm: {term: m, means: 'TODO: x'}\n")
        self.assertEqual(0, run("pack", "about-check", self.pack, *self.jvm(), env=self.env)[0])
        code, out, _ = run("pack", "about-check", self.pack, "--strict", *self.jvm(), env=self.env)
        self.assertEqual(1, code)
        self.assertIn("trade.mtm", out)

    def test_pack_new_load_needs_a_server(self):
        code, _, err = run("pack", "new", self.tmp, "--name", "x", "--load", *self.jvm(), env={"DRISHTI_SERVER": ""})
        self.assertEqual(2, code)
        self.assertIn("--load needs --server", err)

    def test_data_ingest_writes_the_files_store(self):
        src = self.tmp / "in"
        src.mkdir()
        (src / "trade.jsonl").write_text('{"id": "T1", "d": "2026-10-01"}\n{"id": "T2", "d": "2026-10-02"}\n')
        root = self.tmp / "files"
        code, out, _ = run("data", "ingest", "--from", src, "--domain", "dm", "--date", "d", "--store", "files", "--root", root)
        self.assertEqual(0, code)
        self.assertTrue((root / "dm" / "2026-10-01" / "trade.jsonl").is_file())
        self.assertIn("total rows: 2", out)

    def test_data_load_builds_the_script_command(self):
        code, out, _ = run("data", "load", "--store", "files", "data/x", "--trades", 1000, "--days", 2, "--future-days", 3, "--dry-run")
        self.assertEqual(0, code)
        self.assertTrue(out.startswith("bash "))
        self.assertTrue(out.strip().endswith("load-files.sh data/x --trades 1000 --days 2 --future-days 3"))

    def test_data_load_reaches_the_cassandra_loader(self):
        code, out, _ = run("data", "load", "--store", "cassandra", "localhost:9042", "datacenter1", "--trades", 10000, "--create", "--dry-run")
        self.assertEqual(0, code)
        self.assertTrue(out.strip().endswith("load-cassandra.sh localhost:9042 datacenter1 --trades 10000 --create"))
        self.assertTrue((ROOT / "tools" / "load-cassandra.sh").is_file())

    def test_publish_wraps_packreg(self):
        called = {}
        reg = D.load_module("packreg", "packreg/packreg.py")
        original = reg.publish
        reg.publish = lambda *a: called.setdefault("args", a)
        try:
            code, _, _ = run("pack", "publish", self.pack, "--registry", self.tmp / "r", "--key", self.tmp / "k.pem", "--publisher", "me")
        finally:
            reg.publish = original
        self.assertEqual(0, code)
        self.assertEqual("me", called["args"][3])


class FakeServer(BaseHTTPRequestHandler):
    requests: list = []
    routes: dict = {}

    def log_message(self, *a):
        pass

    def handle_any(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else b""
        FakeServer.requests.append((self.command, self.path, self.headers.get("Authorization"), self.headers.get("X-Drishti-User"),
                                    self.headers.get("Content-Type"), body))
        route = FakeServer.routes.get((self.command, self.path.split("?")[0]), (404, {"title": "nope", "detail": "no route", "code": "DRS-1000"}))
        status, payload, more = route[0], route[1], (route[2] if len(route) > 2 else [])   # more: [(header, value)] to send as well
        raw = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        for k, v in more:
            self.send_header(k, v)
        self.send_header("Content-Type", "application/zip" if isinstance(payload, bytes) else "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = handle_any


class Rest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeServer)
        cls.url = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def setUp(self):
        FakeServer.requests, FakeServer.routes = [], {}
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.env = {"DRISHTI_SERVER": self.url, "DRISHTI_TOKEN": "s3cret-token"}

    def go(self, *argv, env=None):
        return run(*argv, env=env or self.env)

    def test_health_reads_the_token_from_the_environment_and_never_prints_it(self):
        FakeServer.routes[("GET", "/api/v1/admin/health")] = (200, {"status": "OK", "summary": {"packs": 1}, "server": {"version": "9"}, "sources": []})
        code, out, err = self.go("server", "health")
        self.assertEqual(0, code)
        self.assertEqual("Bearer s3cret-token", FakeServer.requests[0][2])
        self.assertNotIn("s3cret", out + err)

    def test_health_degraded_exits_1(self):
        FakeServer.routes[("GET", "/api/v1/admin/health")] = (200, {"status": "DEGRADED", "summary": {}, "server": {}, "sources": []})
        self.assertEqual(1, self.go("server", "health")[0])

    def test_token_file_wins_and_user_header_is_sent_without_a_token(self):
        FakeServer.routes[("GET", "/api/v1/builder/designs")] = (200, {"designs": []})
        tok = self.tmp / "tok"
        tok.write_text("from-file\n")
        self.go("design", "list", "--token-file", tok)
        self.assertEqual("Bearer from-file", FakeServer.requests[-1][2])
        self.go("design", "list", "--user", "alice", env={"DRISHTI_SERVER": self.url, "DRISHTI_TOKEN": ""})
        self.assertEqual((None, "alice"), FakeServer.requests[-1][2:4])

    def test_errors_carry_status_code_and_detail_and_exit_1(self):
        FakeServer.routes[("GET", "/api/v1/admin/packs")] = (403, {"title": "forbidden", "detail": "DRS-5002 no access", "code": "DRS-5002"})
        code, _, err = self.go("server", "packs", "list")
        self.assertEqual(1, code)
        self.assertIn("403 DRS-5002", err)
        self.assertIn("approver", err)
        self.assertNotIn("s3cret", err)

    def test_an_unreachable_server_exits_1(self):
        code, _, err = self.go("server", "health", "--server", "http://127.0.0.1:1")
        self.assertEqual(1, code)
        self.assertIn("cannot reach", err)

    def test_server_packs_actions(self):
        FakeServer.routes[("GET", "/api/v1/admin/packs")] = (200, [{"name": "a", "version": "1", "loaded": True, "enabled": True, "added": False, "kinds": ["k"]}])
        FakeServer.routes[("POST", "/api/v1/admin/packs/my-bank/load")] = (200, {"name": "my-bank", "note": "restarts"})
        FakeServer.routes[("POST", "/api/v1/admin/packs/my-bank/unload")] = (200, {"name": "my-bank", "note": "gone"})
        FakeServer.routes[("PUT", "/api/v1/admin/packs/my-bank")] = (200, {"name": "my-bank", "enabled": False, "enabledPacks": ["a"]})
        self.assertIn("a ", self.go("server", "packs", "list")[1])
        self.assertEqual("a", json.loads(self.go("server", "packs", "list", "--json")[1])[0]["name"])
        self.assertIn("restarts", self.go("server", "packs", "load", "my-bank")[1])
        self.assertIn("gone", self.go("server", "packs", "unload", "my-bank")[1])
        self.assertIn("is off", self.go("server", "packs", "off", "my-bank")[1])
        self.assertEqual({"enabled": False}, json.loads(FakeServer.requests[-1][5]))
        self.go("server", "packs", "on", "my-bank")
        self.assertEqual({"enabled": True}, json.loads(FakeServer.requests[-1][5]))

    def test_registry_list_and_install(self):
        FakeServer.routes[("GET", "/api/v1/admin/registry")] = (200, {"url": "http://r", "configured": True, "packs": [
            {"name": "p", "version": "1.0.0", "trusted": True, "installedVersion": None, "loadedVersion": None, "publisher": "me"}]})
        FakeServer.routes[("POST", "/api/v1/admin/registry/p/1.0.0/install")] = (200, {"note": "loaded", "installed": "1.0.0"})
        self.assertIn("trusted", self.go("pack", "install", "--list")[1])
        self.assertIn("installed p 1.0.0", self.go("pack", "install", "p", "1.0.0")[1])
        self.assertEqual(2, self.go("pack", "install", "p")[0])

    def test_design_create_with_samples_and_a_sutra_file(self):
        base = "/api/v1/builder/designs"
        design = {"id": "d1", "name": "n", "kind": "trade", "status": "draft", "rev": 1, "samples": [{"name": "a.json"}]}
        FakeServer.routes[("POST", base)] = (201, design)
        FakeServer.routes[("POST", base + "/d1/samples")] = (200, design)
        FakeServer.routes[("POST", base + "/d1/autodesign")] = (200, {"rev": 2})
        FakeServer.routes[("GET", base + "/d1")] = (200, design)
        (self.tmp / "s").mkdir()
        (self.tmp / "s" / "a.json").write_text('{"x": 1}')
        sutra = self.tmp / "t.sutra.yaml"
        sutra.write_text("sutra: t\nversion: 1\nmatch: {kind: trade}\n")
        code, out, _ = self.go("design", "create", "--name", "n", "--from-sutra", sutra, "--samples", self.tmp / "s", "--autodesign")
        self.assertEqual(0, code)
        self.assertIn("created d1", out)
        sent = json.loads(FakeServer.requests[0][5])
        self.assertEqual("trade", sent["kind"])
        self.assertIn("sutra: t", sent["sutra"])
        self.assertEqual([{"name": "a.json", "document": {"x": 1}}], json.loads(FakeServer.requests[1][5])["samples"])

    def test_design_save_check_and_exit_codes(self):
        base = "/api/v1/builder/designs/d1"
        FakeServer.routes[("PATCH", base)] = (200, {"id": "d1", "rev": 3, "name": "n"})
        f = self.tmp / "t.sutra.yaml"
        f.write_text("sutra: t\n")
        self.assertIn("revision 3", self.go("design", "save", "d1", f)[1])
        self.assertEqual({"sutra": "sutra: t\n"}, json.loads(FakeServer.requests[-1][5]))
        FakeServer.routes[("POST", base + "/check")] = (200, {"ok": True, "rev": 3, "samples": [{"name": "a", "status": "ok"}]})
        self.assertEqual(0, self.go("design", "check", "d1")[0])
        FakeServer.routes[("POST", base + "/check")] = (200, {"ok": False, "rev": 3, "samples": [{"name": "a", "status": "error", "message": "bad"}]})
        code, out, _ = self.go("design", "check", "d1")
        self.assertEqual(1, code)
        self.assertIn("a: error bad", out)

    def test_propose_approve_reject(self):
        FakeServer.routes[("POST", "/api/v1/builder/designs/d1/propose")] = (
            202, {"proposal": {"id": "P-1", "name": "t", "version": 2, "status": "pending"}})
        FakeServer.routes[("POST", "/api/v1/sutras/proposals/P-1/approve")] = (200, {"id": "P-1", "name": "t", "version": 2, "status": "approved"})
        FakeServer.routes[("POST", "/api/v1/sutras/proposals/P-1/reject")] = (
            403, {"title": "forbidden", "detail": "DRS-5002 you may not approve", "code": "DRS-5002"})
        FakeServer.routes[("GET", "/api/v1/sutras/proposals")] = (200, {"enabled": True, "proposals": [
            {"id": "P-1", "name": "t", "version": 2, "status": "pending", "author": "a"}]})
        self.assertIn("proposal P-1", self.go("design", "propose", "d1", "--note", "n")[1])
        self.assertEqual({"note": "n"}, json.loads(FakeServer.requests[-1][5]))
        self.assertIn("P-1  t v2  pending", self.go("design", "proposals", "--status", "pending")[1])
        self.assertIn("approved", self.go("design", "approve", "P-1", "--comment", "ok")[1])
        self.assertEqual({"comment": "ok"}, json.loads(FakeServer.requests[-1][5]))
        code, _, err = self.go("design", "reject", "P-1", "--comment", "no")
        self.assertEqual(1, code)
        self.assertIn("403", err)

    def test_export_import_and_bind(self):
        base = "/api/v1/builder/designs"
        FakeServer.routes[("GET", base + "/d1/export")] = (200, b"PK-zip-bytes")
        FakeServer.routes[("POST", base + "/import")] = (201, {"designs": [{"id": "d9"}]})
        FakeServer.routes[("GET", base + "/binding")] = (200, {"enabled": True, "dir": "dev/me"})
        FakeServer.routes[("POST", base + "/d1/bind")] = (200, {"id": "d1", "kind": "k", "status": "draft", "rev": 1, "boundFile": "t.sutra.yaml"})
        FakeServer.routes[("POST", base + "/d1/sync")] = (200, {"id": "d1", "kind": "k", "status": "draft", "rev": 2, "changed": True})
        out = self.tmp / "f.zip"
        self.assertEqual(0, self.go("design", "export", "d1", "-o", out)[0])
        self.assertEqual(b"PK-zip-bytes", out.read_bytes())
        self.assertEqual(0, self.go("design", "import", out)[0])
        self.assertEqual(("POST", "application/zip", b"PK-zip-bytes"), (FakeServer.requests[-1][0], FakeServer.requests[-1][4], FakeServer.requests[-1][5]))
        self.assertIn("binding on", self.go("design", "bind")[1])
        self.assertIn("bound t.sutra.yaml", self.go("design", "bind", "d1", "--file", "t.sutra.yaml")[1])
        self.assertIn("changed: True", self.go("design", "bind", "d1", "--sync")[1])
        self.assertEqual(2, self.go("design", "bind", "d1")[0])


class History(unittest.TestCase):
    """drishti.py history KIND ID against the contract's fixture (drishti-testkit history/)."""
    FIXTURE = ROOT / "drishti-testkit" / "src" / "main" / "resources" / "history" / "trade-1000015-30.json"

    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeServer)
        cls.url = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    go = Rest.go

    def setUp(self):
        Rest.setUp(self)
        FakeServer.routes[("GET", "/api/v1/history/trade/1000015")] = (200, json.loads(self.FIXTURE.read_text()))

    def test_help(self):
        code, out, _ = run("history", "--help")
        self.assertEqual(0, code)
        self.assertIn("drishti.py history trade 1000015 --days 30", out)

    def test_text_is_newest_first_with_changed_cells_marked(self):
        code, out, _ = self.go("history", "trade", "1000015", "--days", "30")
        self.assertEqual(0, code)
        self.assertIn("/api/v1/history/trade/1000015?days=30", FakeServer.requests[-1][1])
        lines = out.splitlines()
        self.assertIn("2026-09-07 -> 2026-10-19", lines[0])
        body = [ln for ln in lines if ln[:4] == "2026"]
        self.assertTrue(body[0].startswith("2026-10-19") and body[-1].startswith("2026-09-07"))
        self.assertIn("75K*", out)
        self.assertIn("⟲2", out)

    def test_changes_only_json_csv_and_the_query(self):
        out = self.go("history", "trade", "1000015", "--days", "30", "--changes-only")[1]
        self.assertIn("7 of 30 days changed", out)
        res = json.loads(self.go("history", "trade", "1000015", "--range", "3M", "--diff", "--json", "--changes-only")[1])
        self.assertEqual(7, len(res["rows"]))
        self.assertIn("range=3M&diff=true", FakeServer.requests[-1][1])
        csv_text = self.go("history", "trade", "1000015", "--from", "2026-09-01", "--to", "2026-09-30", "--csv")[1]
        self.assertTrue(csv_text.startswith("\ufeffDate,"))
        self.assertIn("\r\n", csv_text)
        self.assertIn("from=2026-09-01&to=2026-09-30", FakeServer.requests[-1][1])

    def test_usage_and_refusals(self):
        self.assertEqual(2, self.go("history", "trade", "1000015", "--csv", "--json")[0])
        self.assertEqual(2, self.go("history", "trade", "1000015", "--days", "0")[0])
        code, _, err = self.go("history", "trade", "NOPE-1")
        self.assertEqual(1, code)
        self.assertIn("404", err)


class RealJar(unittest.TestCase):
    def test_sutra_lint_on_a_shipped_pack(self):
        try:
            D.find_jar()
        except D.CliError:
            self.skipTest("no drishti-server exec jar built")
        import argparse
        r = D.run_java(argparse.Namespace(jar=None, java=None), ["lint", str(ROOT / "examples" / "packs" / "retail-banking")], capture=True)    # the engine's output stays quiet
        self.assertEqual(0, r.returncode, r.stderr[-500:])
        self.assertIn("ok", r.stdout)
        code, _, _ = run("sutra", "lint", ROOT / "examples" / "packs" / "retail-banking" / "pack.yaml.missing")
        self.assertEqual(2, code)


if __name__ == "__main__":
    unittest.main()
