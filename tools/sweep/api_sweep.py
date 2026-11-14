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

"""The API sweep: every endpoint of the server and of the console, called as the administrator (docs/architecture/TEST_HARNESS.md, "The API sweep").

    python3 tools/sweep/api_sweep.py --console http://host:port --server http://host:port [--admin USER --admin-password PW] [--token drk_...]

The endpoints come from the source, the one place both halves are complete: the server's `@GetMapping`, `@PostMapping`, `@PutMapping`,
`@DeleteMapping` and `@PatchMapping` methods (with their class's `@RequestMapping` prefix and their `@RequestParam`s) and the console's
FastAPI route decorators (read with `ast`: the router's prefix and the function's parameters). Present in code but not in
docs/guides/API_REFERENCE.md, and the reverse, are reported (the guide's own test, drishti-console/tests/test_docs_api_endpoints.py,
supplies the matching rules).

GET endpoints are called with real values: the kinds, ids and business dates of the saved test data (data/testdata/**/*.jsonl.gz, streamed,
never copied), and for any other path variable the first id the parent list endpoint answers with (GET /bi/datasets -> /bi/datasets/{id}).
A variable no list yields is called with a deliberately missing id, which must be refused with a 4xx that says why.
Pass: 2xx with a body, or a clean refusal (a feature that is switched off, marked "refused"), or a 4xx for the missing id; each within 10 s.
FAIL: 5xx, any other 4xx, an empty body, a timeout, a slow answer. An event stream is read for two seconds (the status and the first bytes).

POST, PUT, PATCH and DELETE are never called: each is listed with the test that exercises it, and one with no test is flagged.

The server needs a bearer token: --token, or (the documented flow) one personal API token of the administrator made through the console
(POST /api/tokens, one day), revoked when the sweep ends. Without --admin the console is open and the server is asked without a token.

The report: data/sweep/api-sweep-<stamp>.md and .jsonl (not committed); exit 1 on any FAIL."""
from __future__ import annotations

import argparse
import ast
import gzip
import http.cookiejar
import importlib.util
import json
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
SKIP_DIRS = {"node_modules", "target", "__pycache__", "vendor"}        # and the repository's own top-level data/
#: a GET that changes the session or the node is never called
NO_CALL = re.compile(r"logout|signout|sign-out|shutdown|/restart|/drain|/stop$|/oidc|/callback", re.I)
#: a clean refusal: the feature is switched off, or not part of this edition
REFUSED = re.compile(r"switched off|turned off|not enabled|is off|disabled|not active|not part of|DRS-4023|DRS-1020|not configured|not available|"
                     r"not running|engine off|keeps no daily history|DRS-1012|compliance power|another Drishti.?net member|on-behalf-of|embed token|"
                     r"verified by the console only|is for people who may edit|no official|declares no settings|service (role|identity)|embedding is off", re.I)
#: a route the console does not mount (embedding, a module) answers its own "no page" 404; one a module of the source tree owns may be absent
NOT_MOUNTED = re.compile(r"has no page at|\"error\":\"Not Found\"")
#: the server says what input the call lacks
INPUT_HINT = re.compile(r"give |name the|name a|names |needs |is required|\?ref=|scope=|is needed|must name|needs at least", re.I)
#: where the ids of a collection are listed when the collection itself is not a list endpoint
ALT_LISTS = {"/api/v1/bi/items": "/api/v1/bi/catalogue", "/api/v1/admin/loads": "/api/v1/admin/packs"}
STREAM = re.compile(r"stream|/events|/channel|subscribe")
JAVA_MAP = re.compile(r"@(?:[\w.]*\.)?(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?")
JAVA_BASE = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?(?:\{\s*)?"([^"]*)"')
ENTITY_PARENTS = ("entities", "views", "view", "raw", "v", "series", "stream", "history", "impact", "explain", "export", "compare", "about", "panels")
VARIABLE = re.compile(r"\{([^}:]*)(?::[^}]*)?\}")


# ------------------------------------------------------------------ the source of truth

def walk(pattern: str, under: Path = ROOT):
    for p in sorted(under.glob(pattern)):
        parts = p.relative_to(under).parts
        if not (set(parts) & SKIP_DIRS) and parts[0] != "data":
            yield p


def java_endpoints() -> list[dict]:
    """The server's endpoints: method, full path (under /api/v1 as the controller writes it), required query parameters, the source."""
    out = []
    for path in walk("**/src/main/java/**/*.java"):
        text = path.read_text(encoding="utf-8", errors="replace")
        if not re.search(r"@(Rest)?Controller", text):
            continue
        bases = [(m.start(), m.group(1)) for m in JAVA_BASE.finditer(text)]
        for m in JAVA_MAP.finditer(text):
            prefix = next((b for pos, b in reversed(bases) if pos < m.start()), "")
            paths = [x for x in re.findall(r'"([^"]*)"', m.group(2) or "") if x.startswith("/")] or [""]
            params = java_params(text[m.end():m.end() + 2500])
            for p in paths:
                out.append({"side": "server", "method": m.group(1).upper(), "path": prefix + p, "params": params,
                            "source": f"{path.relative_to(ROOT)}:{text.count(chr(10), 0, m.start()) + 1}"})
    return out


def java_params(sig: str) -> dict[str, bool]:
    """The method's @RequestParam names (read from the text after the mapping annotation): name -> required."""
    rest = re.sub(r"^(\s*@\w+(\([^)]*\))?)+", "", sig)
    start = rest.find("(")
    if start < 0:
        return {}
    depth, i = 0, start
    while i < len(rest):
        depth += {"(": 1, ")": -1}.get(rest[i], 0)
        if depth == 0:
            break
        i += 1
    params: dict[str, bool] = {}
    for rp in re.finditer(r"@RequestParam(?:\(([^)]*)\))?\s+(?:final\s+)?[\w<>\[\],.? ]+?\s+(\w+)\s*[,)]", rest[start:i + 1]):
        args = rp.group(1) or ""
        optional = "required = false" in args or "required=false" in args or "defaultValue" in args
        name = re.search(r'(?:(?:value|name)\s*=\s*)?"(\w[\w.-]*)"', args)
        params[name.group(1) if name else rp.group(2)] = not optional
    return params


def py_endpoints() -> list[dict]:
    """The console's routes: each `@<router>.<verb>(path)` of drishti-console/routes, with the router's prefix and the function's required parameters."""
    out = []
    for path in sorted((ROOT / "drishti-console" / "routes").glob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        prefixes: dict[str, str] = {}
        for node in tree.body:
            if isinstance(node, ast.Assign) and isinstance(node.value, ast.Call) and getattr(node.value.func, "id", "") == "APIRouter":
                pre = next((k.value.value for k in node.value.keywords if k.arg == "prefix" and isinstance(k.value, ast.Constant)), "")
                for t in node.targets:
                    prefixes[getattr(t, "id", "")] = pre
        for fn in ast.walk(tree):
            if not isinstance(fn, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            for d in fn.decorator_list:
                if not (isinstance(d, ast.Call) and isinstance(d.func, ast.Attribute) and isinstance(d.func.value, ast.Name)
                        and d.func.value.id in prefixes and d.args and isinstance(d.args[0], ast.Constant)):
                    continue
                verb = d.func.attr
                methods = [verb.upper()]
                if verb == "api_route":
                    methods = next(([e.value for e in k.value.elts] for k in d.keywords if k.arg == "methods"), ["GET"])
                names = [a.arg for a in fn.args.args]
                defaults = [None] * (len(names) - len(fn.args.defaults)) + list(fn.args.defaults)
                route = prefixes[d.func.value.id] + d.args[0].value
                inpath = {m.group(1) for m in VARIABLE.finditer(route)}
                params = {n: dflt is None for n, dflt in zip(names, defaults)           # name -> required (no default)
                          if n not in inpath and n not in ("request", "websocket", "self")
                          and not (isinstance(dflt, ast.Call) and getattr(dflt.func, "id", getattr(dflt.func, "attr", "")) in ("Depends", "File", "Form", "Body"))}
                for mth in methods:
                    out.append({"side": "console", "method": mth, "path": route, "params": params, "source": f"{path.relative_to(ROOT)}:{fn.lineno}"})
    return out


def norm(p: str) -> str:
    return VARIABLE.sub("{}", re.sub(r"^/api/v1", "", p.split("?")[0])).rstrip("/") or "/"


def docs_gap(server: list[dict]) -> dict:
    """In code but not in API_REFERENCE.md, and the reverse, by the rules of the guide's own test (loaded from it: one set of rules)."""
    spec = importlib.util.spec_from_file_location("docs_api", ROOT / "drishti-console" / "tests" / "test_docs_api_endpoints.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    mapped = {(e["method"], norm(e["path"])) for e in server}
    missing = sorted(f"{m} {p}" for m, p in mapped if not mod._documented(m, p))
    patterns = [re.compile("^" + re.escape(p).replace(r"\{\}", "[^/]+") + "$") for _, p in mapped]
    unknown = []
    for ln in mod.GUIDE.splitlines():
        m = re.match(r"\|\s*`(GET|POST|PUT|PATCH|DELETE)`(?:\s*[·/]\s*`?(?:GET|POST|PUT|PATCH|DELETE)`?)*\s*\|\s*`([^`]+)`", ln)
        if m and not any(p.match(norm(m.group(2).split()[0].replace("[", "?"))) for p in patterns):
            unknown.append(f"{m.group(1)} {m.group(2)}")
    return {"in_code_not_in_guide": missing, "in_guide_not_in_code": unknown}


def console_gap(console: list[dict]) -> list[str]:
    """The console's /api JSON routes the guide's section 27 does not name (informational: most console routes are pages)."""
    guide = (ROOT / "docs" / "guides" / "API_REFERENCE.md").read_text(encoding="utf-8")
    seen = {re.sub(r"\{[^}]*\}", "{}", m) for m in re.findall(r"`(/[\w/{}.:\-]+)", guide)}
    return sorted({f"{e['method']} {e['path']}" for e in console if e["path"].startswith("/api/")
                   and re.sub(r"\{[^}]*\}", "{}", e["path"]).removeprefix("/api") not in seen
                   and re.sub(r"\{[^}]*\}", "{}", e["path"]) not in seen})


# ------------------------------------------------------------------ the tests that cover the writes

def test_index() -> list[tuple[str, int, str]]:
    """Every line of the test sources that holds a quoted path: (file, line number, text)."""
    lines = []
    files = [*walk("**/src/test/**/*.java"), *walk("drishti-console/tests/**/*.py"), *walk("tools/**/test*.py"), *walk("testing/**/*.py"),
             *walk("clients/**/test*.py"), *walk("compute/**/test*.py")]
    for p in files:
        rel = str(p.relative_to(ROOT))
        for n, ln in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if "/" in ln and ('"' in ln or "'" in ln):
                lines.append((rel, n, ln))
    return lines


def covered_by(e: dict, index: list[tuple[str, int, str]], text: dict) -> str:
    """The first test line that names this write's path (its literal pieces in order) with its method on the line or next to it."""
    route = re.sub(r"^/api/v1", "", e["path"])
    pieces = [p for p in VARIABLE.sub("\0", route).split("\0") if p.strip("/")]
    if not pieces:
        return ""
    rx = re.compile(".*?".join(re.escape(p) for p in pieces) + r"(?![\w-])")
    anchor = max(pieces, key=len)
    verb = e["method"].lower()
    mrx = re.compile(rf"\b{verb}\b|\.{verb}\(|-X\s*{e['method']}|\b{e['method']}\b", re.I)
    for rel, n, ln in index:
        if anchor in ln and rx.search(ln) and (mrx.search(ln) or mrx.search(text.get((rel, n - 1), "")) or mrx.search(text.get((rel, n + 1), ""))):
            return f"{enclosing_test(rel, n)} ({rel}:{n})"
    return ""


_FILES: dict[str, list[str]] = {}


def enclosing_test(rel: str, n: int) -> str:
    """The test method (JUnit `void name(` or pytest `def test_name`) that holds line n of a test file."""
    lines = _FILES.setdefault(rel, (ROOT / rel).read_text(encoding="utf-8", errors="replace").splitlines())
    for i in range(min(n, len(lines)) - 1, -1, -1):
        m = re.search(r"\bvoid\s+(\w+)\s*\(|\bdef\s+(test\w*|\w+)\s*\(", lines[i])
        if m and (m.group(1) or m.group(2).startswith("test") or not lines[i].startswith(" ")):
            return m.group(1) or m.group(2)
    return Path(rel).stem


# ------------------------------------------------------------------ the saved test data

def samples(data_root: Path) -> tuple[dict[str, list[str]], str]:
    """kind -> real ids (the first of each saved dataset, streamed) and the newest business date."""
    import yaml
    try:
        spec = yaml.safe_load((data_root / "testdata.yaml").read_text())
    except (OSError, ValueError):
        return {}, ""
    out: dict[str, list[str]] = {}
    for d in spec.get("datasets") or []:
        key, folder, kind = d.get("key"), d.get("folder"), d.get("kind")
        if not key or not folder or not kind or kind in out:
            continue
        files = sorted((data_root / folder).glob("*.jsonl.gz"))[:1]
        try:
            with gzip.open(files[0], "rt", encoding="utf-8") as fh:
                ids = [str(json.loads(ln).get(key)) for _, ln in zip(range(40), fh) if ln.strip()]
        except (OSError, IndexError, ValueError, AttributeError):
            continue
        ids = [i for i in dict.fromkeys(ids) if i and i != "None"]
        if ids:
            out[kind] = ids
    dates = spec.get("business-dates") or []
    return out, str(dates[-1]) if dates else ""


# ------------------------------------------------------------------ calling

class Target:
    """The server (a bearer token) or the console (the signed-in cookies)."""

    def __init__(self, name: str, base: str, headers: dict | None = None):
        self.name, self.base, self.headers = name, base.rstrip("/"), headers or {}
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path: str) -> dict:
        t0 = time.monotonic()
        body, status, ctype = b"", 0, ""
        try:
            with self.op.open(urllib.request.Request(self.base + path, headers=self.headers), timeout=4 if STREAM.search(path) else BUDGET + 5) as r:
                status, ctype = r.status, r.headers.get("Content-Type", "")
                if "event-stream" in ctype:                      # a stream is read for two seconds: its status and first bytes
                    t1 = time.monotonic()
                    while time.monotonic() - t1 < 2 and len(body) < 4096:
                        chunk = r.read1(2048)
                        if not chunk:
                            break
                        body += chunk
                    body = body or b"(event stream open)"
                else:
                    body = r.read(4_000_000)
        except urllib.error.HTTPError as e:
            status, ctype, body = e.code, e.headers.get("Content-Type", ""), e.read()[:200_000]
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            if STREAM.search(path) and (isinstance(e, TimeoutError) or isinstance(getattr(e, "reason", None), TimeoutError)):
                return {"status": 200, "ctype": "text/event-stream", "body": b"(stream open, no event within 4 s)", "secs": time.monotonic() - t0, "cut": False}
            body, status = f"{type(e).__name__}: {e}".encode(), 0
        return {"status": status, "ctype": ctype, "body": body, "secs": time.monotonic() - t0, "cut": len(body) >= 4_000_000}

    def json(self, path: str):
        r = self.call(path)
        try:
            return json.loads(r["body"]) if r["status"] == 200 else None
        except ValueError:
            return None


class Resolver:
    """Real values for path variables and query parameters: saved test data first, then the first id a parent list endpoint answers with."""

    def __init__(self, target: Target, ids: dict[str, list[str]], date: str):
        self.t, self.ids, self.date = target, ids, date
        self.cache: dict[str, list[str]] = {}
        self.kind = "trade" if "trade" in ids else (next(iter(ids), "trade"))
        self.id = (ids.get(self.kind) or ["MX-20000001"])[0]
        self.lock = threading.Lock()

    def listing(self, parent: str, var: str) -> list[str]:
        with self.lock:
            if parent in self.cache:
                return self.cache[parent]
        data = self.t.json(ALT_LISTS.get(parent, parent)) if parent else None
        found: list[str] = []

        def take(rows):
            for r in rows[:5]:
                if isinstance(r, str):
                    found.append(r)
                elif isinstance(r, dict):
                    for k in (var, "id", "name", "key", "code", "slug", "kind", "user", "username"):
                        if isinstance(r.get(k), (str, int)) and str(r[k]):
                            found.append(str(r[k]))
                            break
        if isinstance(data, list):
            take(data)
        elif isinstance(data, dict):
            for v in data.values():
                if isinstance(v, list) and v:
                    take(v)
                    break
        with self.lock:
            self.cache[parent] = found
        return found

    KNOWN = {"date", "asof", "q", "scope", "kind", "id", "limit", "name", "source", "ref"}

    def query(self, name: str) -> str:
        n = name.lower()
        if n in ("date", "asof", "as_of", "businessdate", "business_date", "on", "day", "from", "since", "start", "to", "until", "end"):
            return self.date
        if n in ("q", "query", "text", "term", "search"):
            return self.id[:2]
        if n in ("id", "entity", "ref"):
            return self.id
        if n in ("limit", "top", "n", "size", "max", "count", "page", "days"):
            return "5"
        if n in ("scope", "dataset", "source"):
            return "BANK" if n == "scope" else self.kind
        if n in ("user", "owner"):
            return "drishti-dev-admin"
        return self.kind

    def fill(self, route: str) -> tuple[str, bool, bool]:
        """The route with every variable filled; False when a variable had to be given a deliberately missing id."""
        out, real, pos, cur = "", True, 0, self.kind
        guessed = False
        if "*" in route:
            return route.replace("**", "x").replace("*", "x"), False, False
        for m in VARIABLE.finditer(route):
            out += route[pos:m.start()]
            pos, name = m.end(), m.group(1).lower()
            if name in ("kind", "entitykind") and out.rstrip("/").split("/")[-1] in ENTITY_PARENTS:
                val = cur = self.kind
            elif name in ("kind", "entitykind"):
                vals = self.listing(out.rstrip("/"), name)
                val = cur = vals[0] if vals else self.kind
                guessed = guessed or not vals
            elif name in ("date", "asof", "businessdate"):
                val = self.date
            elif name in ("id", "id_", "entityid", "ref", "path") and (out.rstrip("/").split("/")[-1] in ENTITY_PARENTS or (
                    out.rstrip("/").split("/")[-1] == cur and cur in self.ids)):
                val = (self.ids.get(cur) or [self.id])[0]
            else:
                vals = self.listing(out.rstrip("/"), name)
                val = vals[0] if vals else ""
                guessed = guessed or bool(val)
                if not val:
                    val, real = "no-such-id-0000", False
            out += urllib.parse.quote(val, safe="")
        return out + route[pos:], real, guessed


def check(t: Target, e: dict, res: Resolver) -> dict:
    route, real, guessed = res.fill(e["path"])
    q = "&".join(f"{k}={urllib.parse.quote(res.query(k))}" for k, req in e["params"].items() if req or k in Resolver.KNOWN)
    url = route + ("?" + q if q else "")
    r = t.call(url)
    status, body, secs = r["status"], r["body"], r["secs"]
    text = body.decode("utf-8", "replace")
    row = {"side": e["side"], "method": "GET", "path": e["path"], "called": url, "status": status, "secs": round(secs, 2), "result": "pass",
           "message": "", "bytes": len(body), "source": e["source"]}

    def done(result: str, msg: str = "") -> dict:
        if result != "FAIL" and secs > BUDGET:
            result, msg = "FAIL", f"slow: {secs:.1f} s over the {BUDGET:.0f} s budget"
        row["result"], row["message"] = result, msg[:220].replace("\n", " ")
        return row
    if status == 0:
        return done("FAIL", text)
    if status >= 500:
        return done("FAIL", f"HTTP {status}: {text[:160]}")
    if status >= 400 or status == 502:
        if not real:
            if status in (400, 403, 404, 405, 422):
                return done("missing-id ok", f"HTTP {status} for a missing id")
            return done("FAIL", f"HTTP {status} for a missing id")
        if REFUSED.search(text) and status in (400, 401, 403, 404, 409, 502, 503):
            return done("refused", f"HTTP {status}: {re.sub(r'<[^>]+>', ' ', text)[:140]}")
        if NOT_MOUNTED.search(text) and status == 404 and (e["path"].startswith(("/embed", "/m/")) or e["source"].startswith("modules/")):
            return done("not served", f"HTTP 404: the route is not mounted here (embedding or a module that is not installed)")
        unsupplied = any(k not in q for k in e["params"])
        if status in (400, 404, 422) and (unsupplied or (re.search(r"DRS-\d+", text) and (INPUT_HINT.search(text) or q or guessed))):
            return done("needs input", f"HTTP {status}: the call needs input the sweep cannot know: {text[:130]}")
        return done("FAIL", f"HTTP {status}: {re.sub(r'<[^>]+>', ' ', text)[:170]}")
    if status != 204 and not body.strip():
        if re.search(r"\.(jsonl|csv)$", e["path"]):
            return done("pass", "an empty export")
        return done("FAIL", f"HTTP {status} with an empty body")
    if "json" in r["ctype"] and body.strip() and not r["cut"]:
        try:
            json.loads(body)
        except ValueError:
            return done("FAIL", "the body is not JSON though it says it is")
    return done("pass", "empty JSON answer" if text.strip() in ("[]", "{}") else ("" if real else "answered for the missing id"))


# ------------------------------------------------------------------ main

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--console", required=True)
    ap.add_argument("--server", required=True)
    ap.add_argument("--admin", default="")
    ap.add_argument("--admin-password", default="")
    ap.add_argument("--token", default="", help="a bearer token for the server (otherwise one is made through the console and revoked at the end)")
    ap.add_argument("--testdata", default=str(ROOT / "data" / "testdata"))
    ap.add_argument("--out", default=str(ROOT / "data" / "sweep"))
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--only", default="", help="only endpoints whose path contains this text")
    ap.add_argument("--verbose", action="store_true", help="one line a call on stderr")
    a = ap.parse_args(argv)

    server_eps, console_eps = java_endpoints(), py_endpoints()
    gap = docs_gap(server_eps)
    ids, date = samples(Path(a.testdata))
    con = Target("console", a.console)
    if a.admin:
        data = urllib.parse.urlencode({"user": a.admin, "password": a.admin_password}).encode()
        if "/login" in con.op.open(urllib.request.Request(con.base + "/login", data=data), timeout=30).geturl():
            raise SystemExit("api sweep: console sign-in failed")
    srv = Target("server", a.server, {"Authorization": "Bearer " + a.token} if a.token else {})
    made_token = ""
    if not a.token and srv.call("/api/v1/about")["status"] == 401:
        req = urllib.request.Request(con.base + "/api/tokens", data=json.dumps({"name": "api-sweep", "days": 1}).encode(),
                                     headers={"Content-Type": "application/json"})
        made = json.loads(con.op.open(req, timeout=30).read())
        srv.headers["Authorization"] = "Bearer " + made["secret"]
        made_token = made["token"]["id"]
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()
    res = {"server": Resolver(srv, ids, date), "console": Resolver(con, ids, date)}
    targets = {"server": srv, "console": con}
    jobs, seen = [], set()
    for e in server_eps + console_eps:
        if e["method"] == "GET" and a.only in e["path"] and (e["side"], e["path"]) not in seen:
            seen.add((e["side"], e["path"]))
            jobs.append(e)

    def one(e: dict) -> dict:
        if NO_CALL.search(e["path"]):
            row = {"side": e["side"], "method": "GET", "path": e["path"], "called": "", "status": "", "secs": 0, "result": "skipped",
                   "message": "changes the session or the node on a GET", "bytes": 0, "source": e["source"]}
        else:
            row = check(targets[e["side"]], e, res[e["side"]])
        with lock:
            live.write(json.dumps(row) + "\n")
            live.flush()
        if a.verbose:
            print(f"  {row['result']:13} {row['status']!s:4} {row['secs']:5}s {e['side']:7} {row['called'] or e['path']}", file=sys.stderr, flush=True)
        return row
    t0 = time.monotonic()
    rows = []
    try:
        with (out / f"api-sweep-{stamp}.jsonl").open("w", encoding="utf-8") as live, ThreadPoolExecutor(a.workers) as ex:   # grows a call at a time
            rows = list(ex.map(one, jobs))
        for n, row in enumerate(rows):                           # a slow answer is asked again alone: eight calls at once may be what slowed it
            if row["result"] == "FAIL" and row["message"].startswith("slow"):
                again = check(targets[row["side"]], next(e for e in jobs if e["path"] == row["path"] and e["side"] == row["side"]), res[row["side"]])
                if again["result"] != "FAIL":
                    again["message"] = f"slow under load ({row['secs']} s), {again['secs']} s alone"
                    rows[n] = again
    finally:
        if made_token:
            try:
                con.op.open(urllib.request.Request(con.base + "/api/tokens/" + made_token, method="DELETE"), timeout=30).read()
            except (urllib.error.URLError, OSError):
                print(f"api sweep: could not revoke the token {made_token}; it expires in a day", file=sys.stderr)

    index = test_index()
    text = {(rel, n): ln for rel, n, ln in index}
    writes = [{**e, "test": covered_by(e, index, text)} for e in server_eps + console_eps if e["method"] != "GET"]
    tally = {k: sum(1 for r in rows if r["result"] == k) for k in ("pass", "refused", "needs input", "not served", "missing-id ok", "skipped", "FAIL")}
    untested = [w for w in writes if not w["test"]]
    lines = [f"# API sweep {stamp}", "",
             f"Server {a.server} and console {a.console}, as {a.admin or '(open console)'}: {len(server_eps)} server and {len(console_eps)} console "
             f"route declarations; {len(rows)} distinct GET endpoints called in {time.monotonic() - t0:.0f} s (pass {tally['pass']}, "
             f"refused {tally['refused']}, needs input {tally['needs input']}, not served {tally['not served']}, missing-id ok {tally['missing-id ok']}, skipped {tally['skipped']}, FAIL {tally['FAIL']}); "
             f"{len(writes)} write endpoints, {len(writes) - len(untested)} covered by a test, {len(untested)} with none.", ""]

    def cell(s) -> str:
        return str(s).replace("|", "\\|").replace("\n", " ")
    lines += ["## GET endpoints", "", "| side | path | called | result | time | message |", "|---|---|---|---|---|---|"]
    for r in sorted(rows, key=lambda r: (r["result"] != "FAIL", r["side"], r["path"])):
        lines.append(f"| {r['side']} | {cell(r['path'])} | {cell(r['called'][:90])} | {r['result']} | {r['secs']} s | {cell(r['message'])} |")
    lines += ["", "## Write endpoints (never called; covered by tests)", "", "| side | method | path | covered by tests |", "|---|---|---|---|"]
    for w in sorted(writes, key=lambda w: (bool(w["test"]), w["side"], w["path"], w["method"])):
        lines.append(f"| {w['side']} | {w['method']} | {cell(w['path'])} | {'covered by tests: ' + w['test'] if w['test'] else '**NO TEST**'} |")
    lines += ["", f"## In the code, not in API_REFERENCE.md ({len(gap['in_code_not_in_guide'])})", ""] + [f"- `{x}`" for x in gap["in_code_not_in_guide"]]
    lines += ["", f"## In API_REFERENCE.md, not in the code ({len(gap['in_guide_not_in_code'])})", ""] + [f"- `{x}`" for x in gap["in_guide_not_in_code"]]
    cg = console_gap(console_eps)
    lines += ["", f"## Console /api routes the guide does not name ({len(cg)}, informational)", ""] + [f"- `{x}`" for x in cg]
    (out / f"api-sweep-{stamp}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"api sweep: {len(rows)} GETs {tally}; writes {len(writes)} ({len(untested)} untested); docs gap "
          f"{len(gap['in_code_not_in_guide'])}/{len(gap['in_guide_not_in_code'])}; report {out}/api-sweep-{stamp}.md")
    for r in rows:
        if r["result"] == "FAIL":
            print(f"  FAIL {r['side']} {r['path']} [{r['called'][:80]}]: {r['message']}")
    return 1 if tally["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
