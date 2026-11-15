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

"""The command sweep: every function a signed-in user can type, run, and checked (docs/architecture/TEST_HARNESS.md, "The command sweep").

    python3 tools/sweep/command_sweep.py --base http://host:port [--admin USER --admin-password PW] [--role USER --role-password PW]

The functions come from the FUNC directory itself (/func as the administrator: every command of every module that is on, with its
example), the top bar's module commands, the reserved words (FORM, WHAT, BI, FUNC, DATAMAP, DATA, EXPORT, PIVOT, ADMIN <page>) and
VAR's sub-commands. Each is typed as <WORD> <GO> (bare), with its FUNC example, and with a real id from the saved test data
(data/testdata/**/*.jsonl.gz, read streamed, never copied: the first id of its kind and a random one; with --as-of, an id whose dates the
demo lake moves, an exposure run's RUN_ID, is moved the same way: the lake built --as-of holds XR-<that date>-01), as the administrator
and as one role user. HTTP only (the pages are drawn by the server; what the browser draws is checked through the API it reads: VaR's headline).

Each run is checked: status and title; no DRS error, "not found" or server error unless the person legitimately is refused (a clean
refusal is a pass, marked "refused"); content present (rows on a list, a headline number on a VaR page, a main area that is not empty);
the load time (budget 10 s). Without --admin the console is open (no sign-in: the drill's stack) and runs as that one user.

The report: data/sweep/sweep-<stamp>.md and .jsonl (not committed); exit 1 on any FAIL."""
from __future__ import annotations

import argparse
import gzip
import html as _html
import http.cookiejar
import json
import random
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUDGET = 10.0
READ_SECONDS = 45
#: a clean refusal: the person is told, in words, that the function is not theirs (or is switched off)
REFUSED = re.compile(r"not available to you|not part of|not active|is not in |not allowed|not permitted|not entitled|forbidden|"
                     r"permission|may not open|may not |not enabled|switched off|turned off|DRS-1016|DRS-1020|DRS-4023|"
                     r"requires (the )?(role|admin)|administrator|no access to any kind of pack|no access to|is not running on this server", re.I)
EXPLAINED_CODE = re.compile(r"(?:is|are) refused \( DRS-\d+ \)")
FAULT = re.compile(r"DRS-\d+|not found|no such|has no columns|Traceback|Internal Server Error|Server error|Unhandled exception|cannot read command", re.I)
#: a connector's failure (DRS-5034 ...) as an Admin page lists it: the connector's own state, which the config sweep owns; one message: its connector, its reason, the parenthesis that follows
CONNECTOR_ALERT = re.compile(r"DRS-5034 connector [^:]+:[^()]*(?:\([^)]*\))?")
#: runs the owner parked, each with its reason: reported as "parked" (listed in the report), never a FAIL and never silently skipped
PARKED = {"XRUN XR-2026-09-30-01": "parked until the CCR engine is built (owner 2026-11-15): the example names the test data's run, a lake "
                                   "built --as-of holds XR-<that date>-01",
          "XPATH XR-2026-09-30-01~CLY-3000280~0": "parked until the CCR engine is built (owner 2026-11-15), with XRUN"}
#: functions the owner parked as a whole: a run of theirs that fails is "parked" with the reason (a pass stays a pass)
PARKED_FUNCTIONS = {"STR": "parked: stress testing out of i4 scope (owner 2026-11-15)",
                    "SSPNL": "parked: stress testing out of i4 scope (owner 2026-11-15)",
                    "TRACK": "parked: public US equities data, not the owner's flows (owner 2026-11-15)"}
RESERVED = ["BI", "RUPAKA", "FORM", "FUNC", "WHAT", "DATAMAP", "DATA", "EXPORT", "PIVOT"]
ADMIN_PAGES = ["users", "roles", "packs", "commands", "tokens", "access", "connectors", "artifacts", "ai", "loads", "lakes", "collab",
               "embedding", "bi", "audit", "net", "edits", "compute", "engines", "health", "caches", "capacity", "scopes", "data-out",
               "configuration", "bindings", "white-label"]
VAR_SUB = ["VAR BANK", "VAR BANK EXPLAIN", "VAR BANK BACKTEST", "VAR REPORTS", "VAR DESK", "PLAY VAR", "SMAT", "SWIN BANK", "FRSA BANK",
           "PLAY FRTB EXAMPLE", "PLAY SIMM EXAMPLE", "VRES", "VLIM", "VOFF"]


class Client:
    """One signed-in person (or the open console): cookies kept, redirects followed, never raising on HTTP errors."""

    def __init__(self, base: str, user: str = "", password: str = ""):
        self.base, self.user = base.rstrip("/"), user or "(open console)"
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        if user:
            data = urllib.parse.urlencode({"user": user, "password": password}).encode()
            r = self.get("/login", data=data)
            if r["status"] != 200 or "/login" in r["url"]:
                raise SystemExit(f"sweep: sign-in as {user} failed: HTTP {r['status']} at {r['url']}")

    def get(self, path: str, data: bytes | None = None) -> dict:
        t0 = time.monotonic()
        try:
            with self.op.open(urllib.request.Request(self.base + path if path.startswith("/") else path, data=data), timeout=READ_SECONDS) as r:
                body, status, url = r.read().decode("utf-8", "replace"), r.status, r.geturl()
        except urllib.error.HTTPError as e:
            body, status, url = e.read().decode("utf-8", "replace"), e.code, e.url
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            body, status, url = f"{type(e).__name__}: {e}", 0, self.base + path
        return {"status": status, "url": url, "body": body, "secs": time.monotonic() - t0}

    def json(self, path: str):
        r = self.get(path)
        try:
            return json.loads(r["body"]) if r["status"] == 200 else None
        except ValueError:
            return None


def text_of(body: str) -> str:
    m = re.search(r"<main.*?</main>", body, re.S)
    t = re.sub(r"<script.*?</script>|<style.*?</style>|<[^>]+>", " ", m.group(0) if m else body, flags=re.S)
    return re.sub(r"\s+", " ", _html.unescape(t)).strip()


def title_of(body: str) -> str:
    m = re.search(r"<title>(.*?)</title>", body, re.S)
    return _html.unescape(m.group(1)).strip() if m else ""


def catalogue(c: Client) -> list[dict]:
    """Every function: {word, kind, example, on, source}. FUNC's rows, the top bar's module commands, the reserved words, VaR's sub-commands."""
    for _ in range(4):                                  # FUNC is the sweep's source: asked again when a busy server did not answer it
        page = c.get("/func")["body"]
        if 'data-command="' in page:
            break
        time.sleep(5)
    else:
        raise SystemExit("sweep: the FUNC directory (/func) did not answer with its commands")
    out: dict[str, dict] = {}
    for m in re.finditer(r'<tr data-command="([^"]*)" data-kind="([^"]*)" data-on="([^"]*)".*?</tr>', page, re.S):
        ex = re.search(r'func-ex">(.*?)</td>', m.group(0), re.S)
        example = re.sub(r"<[^>]+>", "", ex.group(1)).strip() if ex else ""
        out[m.group(1)] = {"word": m.group(1), "kind": m.group(2), "on": m.group(3) == "true", "source": "FUNC",
                           "example": "" if example == "search form" else _html.unescape(example)}
    for cmd in sorted(set(re.findall(r'mega-cmd mono">([^<]*)<', page))):
        cmd = _html.unescape(cmd).replace("…", "").strip()
        if cmd and cmd not in out:
            out[cmd] = {"word": cmd, "kind": "", "on": True, "source": "top bar", "example": cmd}
    for w in RESERVED + VAR_SUB:
        out.setdefault(w, {"word": w, "kind": "", "on": True, "source": "reserved", "example": w})
    for p in ADMIN_PAGES:
        out[f"ADMIN {p}"] = {"word": f"ADMIN {p}", "kind": "", "on": True, "source": "admin page", "example": f"ADMIN {p}"}
    for p in sorted(set(re.findall(r'href="/form/([a-z0-9-]+)"', page))):
        out[f"FORM {p}"] = {"word": f"FORM {p}", "kind": "", "on": True, "source": "form", "example": f"FORM {p}"}
    return sorted(out.values(), key=lambda f: f["word"])


def mover(spec: dict, as_of: str):
    """How the demo lake moved an id of the saved test data when it was built ``--as-of`` (tools/lake/demo.py): the key fields it moves
    (DemoLake.DATED_FIELDS: an exposure run's RUN_ID, a path row's PATH_ROW_ID) and the function that moves each test-data date in an id to
    the lake's; None when the lake keeps the test data's own dates."""
    dates = [str(d) for d in spec.get("business-dates") or []]
    if not as_of or as_of == "data" or not dates:
        return None
    from datetime import date
    sys.path.insert(0, str(ROOT / "tools"))
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    from demo import DemoLake
    from samplegen.dates import Calendar
    from samplegen.lake import business_days
    shift = dict(zip(dates, (d.isoformat() for d in business_days(date.fromisoformat(as_of), len(dates), Calendar.of("USNY")))))
    iso = re.compile(r"\d{4}-\d{2}-\d{2}")
    return set(DemoLake.DATED_FIELDS), lambda v: iso.sub(lambda m: shift.get(m.group(0), m.group(0)), v)


def samples(data_root: Path, as_of: str = "") -> dict[str, list[str]]:
    """kind -> [first id, a random id] from the saved test data (testdata.yaml names each folder, kind and key); streamed, nothing copied.
    ``as_of``: the last business date of the lake the server reads, when it was built --as-of (ids the lake moves are moved the same way)."""
    import yaml
    try:
        spec = yaml.safe_load((data_root / "testdata.yaml").read_text())
    except OSError:
        return {}
    moved = mover(spec, as_of)
    out: dict[str, list[str]] = {}
    for d in spec.get("datasets") or []:
        key, folder = d.get("key"), d.get("folder")
        if not key or not folder or (d.get("kind") in out and d.get("kind") != "trade"):
            continue
        files = sorted((data_root / folder).glob("*.jsonl.gz"))[:1]
        if not files:
            continue
        ids, rng = [], random.Random(d["kind"])
        try:
            with gzip.open(files[0], "rt", encoding="utf-8") as fh:
                for i, line in enumerate(fh):
                    if i >= 5000:
                        break
                    try:
                        v = json.loads(line).get(key)
                    except (ValueError, AttributeError):
                        continue
                    if v not in (None, ""):
                        ids.append(str(v))
        except OSError:
            continue
        if ids and moved and key in moved[0]:
            ids = [moved[1](i) for i in ids]
        if ids:
            out.setdefault(d["kind"], [])
            out[d["kind"]] += [ids[0], rng.choice(ids)]
    return {k: list(dict.fromkeys(v)) for k, v in out.items()}


def plan(funcs: list[dict], ids: dict[str, list[str]]) -> list[tuple[str, str, str]]:
    """(word, argument label, typed command): bare, the FUNC example, and the saved data's first and a random id."""
    runs: list[tuple[str, str, str]] = []
    seen: set[str] = set()

    def add(word: str, label: str, cmd: str) -> None:
        if cmd not in seen:
            seen.add(cmd)
            runs.append((word, label, cmd))
    for f in funcs:
        w = f["word"]
        add(w, "(bare)", w)
        if f["example"] and f["example"].upper() != w.upper():
            add(w, "example: " + f["example"][len(w):].strip(), f["example"])
        for n, i in enumerate(ids.get(f["kind"], [])[:2]):
            add(w, f"{'first' if n == 0 else 'random'} id: {i}", f"{w} {i}")
    return runs


def check(c: Client, word: str, cmd: str, has_data: bool, expect_off: bool) -> dict:
    r = c.get("/go?q=" + urllib.parse.quote(cmd + " <GO>"))
    path = urllib.parse.urlsplit(r["url"])
    body, status = r["body"], r["status"]
    err = urllib.parse.parse_qs(path.query).get("error", [""])[0]
    text = err or text_of(body)
    row = {"function": word, "argument": cmd[len(word):].strip() or "(bare)", "command": cmd, "user": c.user, "status": status,
           "landed": path.path + (("?" + path.query[:60]) if path.query and not err else ""), "title": title_of(body),
           "secs": round(r["secs"], 2), "result": "pass", "message": ""}

    def done(result: str, msg: str = "") -> dict:
        if result != "FAIL" and r["secs"] > BUDGET:                      # a refusal that takes 30 s is no clean refusal
            result, msg = "FAIL", f"slow: {r['secs']:.1f} s over the {BUDGET:.0f} s budget ({result}: {msg[:80]})"
        if result == "FAIL" and (cmd in PARKED or word in PARKED_FUNCTIONS):
            result, msg = "parked", (PARKED.get(cmd) or PARKED_FUNCTIONS[word]) + f" (it was: {msg[:120]})"
        row["result"], row["message"] = result, msg[:240]
        return row
    if status == 0:
        return done("FAIL", body)
    if status >= 500:
        return done("FAIL", f"HTTP {status}: {text[:160]}")
    if status in (401, 403, 404) or err or "/login" in r["url"]:
        if REFUSED.search(text) or expect_off:
            return done("refused", text[:160])
        return done("FAIL", f"HTTP {status}: {text[:180]}")
    own = CONNECTOR_ALERT.sub(" ", text) if path.path.startswith("/admin/") else text      # DRS-5034 on an Admin page is a connector's state it lists (config's matter), not this page failing
    if path.path.startswith("/admin/"):
        own = EXPLAINED_CODE.sub(" ", own)                                                  # a help sentence that names the code of a refusal ("is refused ( DRS-9196 ): migrate it ...") is not a refusal
    if path.path == "/admin/audit":                                                         # the audit trail lists past requests and their errors: data, not this page failing
        own = ""
    hit = FAULT.search(own)
    if hit:
        found = re.search(r".{0,60}" + re.escape(hit.group(0)) + r".{0,100}", own)
        msg = found.group(0) if found else hit.group(0)
        return done("refused" if REFUSED.search(msg) else "FAIL", msg)
    if r["secs"] > BUDGET:
        return done("FAIL", f"slow: {r['secs']:.1f} s over the {BUDGET:.0f} s budget")
    if path.path == "/s":                                               # a list: rows present where the demo has data
        m = re.search(r"of ([\d,]+)</span>", body) or re.search(r'data-live-total="(\d+)"', body)
        n = int(m.group(1).replace(",", "")) if m else None
        if n == 0 or (n is None and "data-spec" not in body):
            return done("FAIL" if has_data else "pass", "no rows" + ("" if has_data else " (no demo data for its kind)"))
        row["rows"] = n
    elif path.path == "/var":                                           # a risk page: the headline number its page draws from the API
        scope = urllib.parse.parse_qs(path.query).get("scope", ["BANK"])[0]
        head = c.json("/api/var/scope?scope=" + urllib.parse.quote(scope))
        if not head or head.get("var") in (None, 0):
            return done("FAIL", f"no VaR headline for {scope}")
        row["headline"] = head["var"]
    elif path.path == "/explain":                                       # drawn in the browser from /api/explain: checked through that
        api = c.get("/api/explain?" + path.query + "&part=all")
        try:
            got = json.loads(api["body"])
        except ValueError:
            got = None
        if api["status"] != 200 or not isinstance(got, dict) or got.get("code"):
            why = (got.get("code", "") + " " + str(got.get("detail", ""))) if isinstance(got, dict) else api["body"][:120]
            return done("FAIL", f"the explanation's API answered HTTP {api['status']} in {api['secs']:.1f} s: {why.strip()[:160]}")
    elif len(text) < 80 and "data-spec" not in body and "data-explain-page" not in body:
        return done("FAIL", "the main area is empty")
    return done("pass")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--base", required=True)
    ap.add_argument("--admin", default="")
    ap.add_argument("--admin-password", default="")
    ap.add_argument("--role", default="")
    ap.add_argument("--role-password", default="")
    ap.add_argument("--testdata", default=str(ROOT / "data" / "testdata"))
    ap.add_argument("--as-of", default="", help="the last business date of the lake the server reads, when it was built lake demo --as-of")
    ap.add_argument("--out", default=str(ROOT / "data" / "sweep"))
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--verbose", action="store_true", help="one line a run on stderr")
    ap.add_argument("--report-only", default="", help="this user's FAILs are reported but never make the exit code 1")
    ap.add_argument("--only", default="", help="only functions whose word contains this text")
    a = ap.parse_args(argv)
    clients = [Client(a.base, a.admin, a.admin_password)]
    if a.role:
        clients.append(Client(a.base, a.role, a.role_password))
    funcs = [f for f in catalogue(clients[0]) if a.only.upper() in f["word"].upper()]
    ids = samples(Path(a.testdata), a.as_of)
    runs = plan(funcs, ids)
    by_word = {f["word"]: f for f in funcs}
    t0 = time.monotonic()
    jobs = [(c, w, label, cmd) for c in clients for (w, label, cmd) in runs]

    def one(j):
        c, w, label, cmd = j
        f = by_word[w]
        r = check(c, w, cmd, bool(ids.get(f["kind"])), expect_off=not f["on"])
        r["argument"] = label
        with lock:
            live.write(json.dumps(r) + "\n")
            live.flush()
        if a.verbose:
            print(f"  {r['result']:7} {r['secs']:6.1f}s {c.user[:12]:12} {cmd}", file=sys.stderr, flush=True)
        return r
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()
    with (out / f"sweep-{stamp}.jsonl").open("w", encoding="utf-8") as live, ThreadPoolExecutor(a.workers) as ex:   # the JSONL grows a run at a time
        rows = list(ex.map(one, jobs))
    tally = {k: sum(1 for r in rows if r["result"] == k) for k in ("pass", "refused", "parked", "FAIL")}
    lines = [f"# Command sweep {stamp}", "", f"{a.base}: {len(funcs)} functions, {len(rows)} runs in {time.monotonic() - t0:.0f} s "
             f"(pass {tally['pass']}, refused {tally['refused']}, parked {tally['parked']}, FAIL {tally['FAIL']}).", "",
             "| function | argument | user | result | time | message |", "|---|---|---|---|---|---|"]

    def cell(s) -> str:
        return str(s).replace("|", "\\|").replace("\n", " ")
    for r in sorted(rows, key=lambda r: (r["result"] != "FAIL", r["function"], r["argument"], r["user"])):
        lines.append(f"| {cell(r['function'])} | {cell(r['argument'])} | {cell(r['user'])} | {r['result']} | {r['secs']} s | {cell(r['message'])} |")
    (out / f"sweep-{stamp}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"sweep: {len(funcs)} functions, {len(rows)} runs, {tally}; report {out}/sweep-{stamp}.md")
    for r in rows:
        if r["result"] == "FAIL":
            print(f"  FAIL {r['function']} [{r['argument']}] as {r['user']}: {r['message']}")
    return 1 if any(r["result"] == "FAIL" and r["user"] != a.report_only for r in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
