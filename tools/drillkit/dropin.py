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
"""The drill's drop-in test (lane ``dropin``; docs/architecture/TEST_HARNESS.md, "The drop-in test"): plug-and-play proven every release.

  dropin.py run [--log FILE] [--keep] [--engine-jar JAR]     the six steps below against a server of the install; the result table goes to FILE
                                                             (the drill: data/drill/dropin.log) and to the screen; exit 0 only when every row passed
  dropin.py jar                                              build and print the sample engine JAR path (what ``run`` builds first)

The install is the drill clone as the anywhere lane makes it (``git archive HEAD`` plus the gate's server jar, in a scratch folder on the
disk), started with its own ``tools/drishti.py start server``. One server jar, never rebuilt: its hash is recorded first and checked last, so
every step proves the same jar runs engine and pack and connector changes (acceptance tests A3 to A7 of the drishti-engine specification,
spec/1/SPEC.md section 0, run on the sample engine's two engines, ``wmean`` and ``group-totals``).

  1  copy         the sample engine JAR into <home>/lib/engines and its engine file into config/engines, restart: RUNNING (lifecycle),
                  health UP, the recorded answer for the recorded request, stats counted that one request
  2  suspend      suspend: requests are refused "suspended" (ENG-5023) and never reach the engine (its counters stay); resume: the same
                  answer, bit for bit
  3  licence      a licence that does not grant the engine's product: start refuses with ENG-1014 naming the product; the neighbour
                  engine and the server's own VaR are unaffected
  4  version      a JAR built for an incompatible engine API is blocked in one sentence (ENG-1002) naming both versions; nothing else fails
  5  removal      JAR and engine file deleted, restart: no registration, no thread, no file in the work folders; the pack that uses the
                  engine says which dependency is missing, in words
  6  pack and     a pack and a connector dropped in the same way: RUNNING, health UP, a counted request; suspend answers DRS-1037 in
     connector    words; resume; removed, nothing left

The routes are in tools/drillkit/dropin.json (``api``), not in this code. Standard library only (run it with the console's Python).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PIN = json.loads((HERE / "dropin.json").read_text(encoding="utf-8"))
API = PIN["api"]
FIXTURES = HERE / "dropin"


# ---- results ----------------------------------------------------------------------------------------------------------------------

@dataclass
class Row:
    step: str
    check: str
    ok: bool
    detail: str = ""


@dataclass
class Report:
    rows: list[Row] = field(default_factory=list)

    def add(self, step: str, check: str, ok: bool, detail: str = "") -> bool:
        self.rows.append(Row(step, check, bool(ok), detail))
        print(f"dropin: {'ok  ' if ok else 'FAIL'} {step} {check}" + (f": {detail}" if detail and not ok else ""), flush=True)
        return bool(ok)

    def passed(self) -> bool:
        return bool(self.rows) and all(r.ok for r in self.rows)

    def table(self) -> str:
        w = max([len(r.check) for r in self.rows] + [5])
        out = [f"{'STEP':<14}{'CHECK':<{w + 2}}{'RESULT':<8}DETAIL"]
        out += [f"{r.step:<14}{r.check:<{w + 2}}{'pass' if r.ok else 'FAIL':<8}{r.detail}" for r in self.rows]
        n = sum(r.ok for r in self.rows)
        out.append(f"{n} of {len(self.rows)} checks passed")
        return "\n".join(out) + "\n"


# ---- the engine JAR: built from drishti-engine at the pin, and the incompatible one made from it -------------------------------------

def build_engine_jar(work: Path, override: Path | None = None) -> Path:
    """The sample engine's JAR. ``override`` (or DRISHTI_ENGINE_JAR) names a built one; else drishti-engine is cloned at the pinned ref
    (DRISHTI_ENGINE_REPO: a local clone to read instead of GitHub) and built with its own wrapper, tests skipped (the engine repository's drill proves it)."""
    given = override or (Path(os.environ["DRISHTI_ENGINE_JAR"]) if os.environ.get("DRISHTI_ENGINE_JAR") else None)
    if given:
        if not given.is_file():
            raise SystemExit(f"dropin: the engine JAR {given} does not exist")
        return given
    src = work / "drishti-engine"
    repo = os.environ.get("DRISHTI_ENGINE_REPO") or PIN["engineRepo"]
    subprocess.run(["git", "clone", "-q", repo, str(src)], check=True)
    subprocess.run(["git", "-C", str(src), "checkout", "-q", PIN["engineRef"]], check=True)
    mvnw = src / "java" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    subprocess.run([str(mvnw), "-q", "-B", "-pl", PIN["engineModule"], "-am", "package", "-DskipTests", "-DskipITs"], cwd=src / "java", check=True)
    jars = [j for j in (src / "java" / PIN["engineModule"] / "target").glob(f"{PIN['engineModule']}-*.jar")
            if not re.search(r"-(sources|javadoc|tests)\.jar$", j.name)]
    if len(jars) != 1:
        raise SystemExit(f"dropin: expected one {PIN['engineModule']} JAR after the build, found {[j.name for j in jars]}")
    return jars[0]


def rewrite_jar(src: Path, dst: Path, engine: str, old: str, new: str) -> None:
    """A copy of ``src`` whose descriptor of ``engine`` has ``old`` replaced by ``new`` (the kit's own way to make a JAR built for another API)."""
    name = f"META-INF/drishti-engine/{engine}.engine.yaml"
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        hit = False
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == name:
                text = data.decode("utf-8")
                hit = old in text
                data = text.replace(old, new).encode("utf-8")
            zout.writestr(item, data)
    if not hit:
        raise SystemExit(f"dropin: {name} in {src.name} does not hold {old!r}")


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---- the install and its server ---------------------------------------------------------------------------------------------------

def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def clean_env(**more: str) -> dict[str, str]:
    keep = {"DRISHTI_JAVA_HOME", "DRISHTI_LAKE_CACHE", "DRISHTI_PYTHON", "DRISHTI_RESOLVED_CACHE"}
    env = {k: v for k, v in os.environ.items() if not k.startswith("DRISHTI_") or k in keep}
    env.update(more)
    return env


class Install:
    """The release unpacked in a scratch folder, its server, and the folders a step reads (the lane's own, removed at the end)."""

    def __init__(self, scratch: Path, lake: Path):
        self.root = scratch
        self.home = scratch / "drishti"
        self.data = scratch / "data"
        self.cwd = scratch / "cwd"
        self.lake = lake
        self.port = free_port()
        self.base = f"http://127.0.0.1:{self.port}"
        for p in (self.home, self.data, self.cwd):
            p.mkdir(parents=True, exist_ok=True)

    def unpack(self) -> str:
        """git archive of HEAD + the gate's server jar and modules, as tests/test_release_from_other_folder.py does; returns the server jar's sha256."""
        tar = self.root / "release.tar"
        subprocess.run(["git", "archive", "--format=tar", "-o", str(tar), "HEAD"], cwd=ROOT, check=True)
        subprocess.run(["tar", "-xf", str(tar), "-C", str(self.home)], check=True)
        tar.unlink()
        jars = sorted((ROOT / "lib").glob("drishti-server-*-exec.jar"))
        if not jars:
            raise SystemExit("dropin: no server jar in lib/ (the gate's build makes it: python tools/drishti.py build all)")
        (self.home / "lib").mkdir(exist_ok=True)
        shutil.copy2(jars[-1], self.home / "lib" / jars[-1].name)
        for f in [*sorted((ROOT / "modules").glob("*.dmod")), ROOT / "modules" / "first-party.yaml"]:
            shutil.copy2(f, self.home / "modules" / f.name)
        self.jar = self.home / "lib" / jars[-1].name
        return sha256(self.jar)

    # one command of the install's own tool, from another folder
    def tool(self, command: str, *args: str, timeout: float = 600) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(self.home / "tools" / "drishti.py"), *command.split(), *args], cwd=self.cwd,
                              capture_output=True, text=True, timeout=timeout, env=self.env())

    def env(self) -> dict[str, str]:
        return clean_env(DRISHTI_DEMO_ENABLED="false", DRISHTI_OFFLINE="true", DRISHTI_VAR_SYSTEM_BASKETS_SEED="true",
                         DRISHTI_DROPIN_FILES=str(self.data / "dropin-files"),
                         DRISHTI_VAR_HOT_DIR=str(self.data / "var" / "hot"), DRISHTI_VAR_OFFICIAL_ROOT=str(self.data / "var" / "official"),
                         DRISHTI_CCR_EXPOSURE_OFFICIAL_ROOT=str(self.data / "ccr" / "official"))

    def start(self) -> subprocess.CompletedProcess:
        return self.tool("start server", "--background", "--port", str(self.port), "--data-dir", str(self.data), "--lake-root", str(self.lake),
                         "--heap", "3072m", "--workdir", str(self.cwd), "--wait", "420", timeout=480)

    def stop(self) -> None:
        self.tool("stop server", "--data-dir", str(self.data), timeout=120)

    def restart(self) -> subprocess.CompletedProcess:
        self.stop()
        return self.start()

    # HTTP (the drill's servers run with sign-in off: every request is the administrator's)
    def call(self, method: str, path: str, body: object = None, timeout: float = 120) -> tuple[int, object]:
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method, headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                text = r.read().decode()
                return r.status, json.loads(text) if text[:1] in "{[" else text
        except urllib.error.HTTPError as e:
            text = e.read().decode(errors="replace")
            try:
                return e.code, json.loads(text)
            except ValueError:
                return e.code, text
        except (urllib.error.URLError, OSError) as e:
            return 0, str(e)

    def log(self) -> str:
        parts = [f.read_text(errors="replace") for f in (self.data / "logs" / "server.log", self.data / "logs" / "server.out") if f.is_file()]
        return "\n".join(parts)

    def pid(self) -> int | None:
        """The server's process: the Java process whose command line names this install's data folder (Linux; None elsewhere)."""
        proc = Path("/proc")
        if not proc.is_dir():
            return None
        for d in proc.iterdir():
            if d.name.isdigit():
                try:
                    cmd = (d / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
                except OSError:
                    continue
                if str(self.data) in cmd and "java" in cmd and int(d.name) != os.getpid():
                    return int(d.name)
        return None

    def threads(self) -> list[str] | None:
        pid = self.pid()
        if pid is None:
            return None
        out = []
        for t in Path(f"/proc/{pid}/task").iterdir():
            try:
                out.append((t / "comm").read_text().strip())
            except OSError:
                pass
        return out


def fill(template: str, **kw: str) -> str:
    return template.format(**kw)


def as_text(x: object) -> str:
    return x if isinstance(x, str) else json.dumps(x, sort_keys=True)


def find(obj: object, key: str):
    """The first value under ``key`` anywhere in a JSON answer (the routes' bodies differ in nesting; the key names do not)."""
    if isinstance(obj, dict):
        if key in obj:
            return obj[key]
        for v in obj.values():
            r = find(v, key)
            if r is not None:
                return r
    elif isinstance(obj, list):
        for v in obj:
            r = find(v, key)
            if r is not None:
                return r
    return None


def table_of(answer: object) -> dict | None:
    """The answer's table of the recorded output as {columns, rows}; None when the answer holds none."""
    t = find(answer, PIN["output"])
    if isinstance(t, dict) and "rows" in t:
        return {"columns": t.get("columns"), "rows": t["rows"]}
    return None


def same_figures(got: dict | None, want: dict) -> bool:
    if not got or [c if isinstance(c, str) else c.get("name") for c in got["columns"] or want["columns"]] != want["columns"]:
        return False
    rows = sorted(got["rows"], key=lambda r: str(r[0]))
    return len(rows) == len(want["rows"]) and all(
        len(a) == len(b) and all((abs(x - y) == 0 if isinstance(y, (int, float)) and isinstance(x, (int, float)) else x == y) for x, y in zip(a, b))
        for a, b in zip(rows, want["rows"]))


def counters(stats: object) -> dict[str, int]:
    since = find(stats, "sinceStart") or {}
    return {k: int(since.get(k, 0) or 0) for k in ("asked", "answered", "refused", "failed")} if isinstance(since, dict) else {}


def engine_entry(inst: Install, eid: str) -> dict | None:
    st, body = inst.call("GET", API["engines"])
    rows = body.get("engines") if isinstance(body, dict) else body
    if st != 200 or not isinstance(rows, list):
        return None
    return next((e for e in rows if isinstance(e, dict) and e.get("id") == eid), None)


def words(entry: dict | None) -> str:
    return " ".join(str(entry.get(k, "")) for k in ("state", "code", "words", "reason", "reasons") if entry and entry.get(k)) if entry else ""


def wait_state(inst: Install, eid: str, state: str, seconds: float = 60) -> dict | None:
    end = time.monotonic() + seconds
    e = None
    while time.monotonic() < end:
        e = engine_entry(inst, eid)
        if e and str(e.get("state", "")).lower() == state:
            return e
        time.sleep(2)   # wait-ok: the server registers the engines a moment after its liveness
    return e


def ask(inst: Install) -> tuple[int, object]:
    return inst.call("POST", fill(API["ask"], id=PIN["engine"], output=PIN["output"]), {"snapshot": PIN["snapshot"], "params": {}, "outputs": [PIN["output"]]})


def lifecycle(inst: Install, route: str, ident: str, transition: str) -> tuple[int, object]:
    return inst.call("POST", fill(API[route], id=ident), {"transition": transition, "reason": "drop-in test"})


def load_observations(inst: Install) -> None:
    """The sample's three observations into the connector's folder (a file the drop-in connector reads)."""
    d = inst.data / "dropin-files" / "surveys"
    d.mkdir(parents=True, exist_ok=True)
    shutil.copy2(FIXTURES / "dropin-observation.jsonl", d / "dropin_observation.jsonl")
    shutil.copy2(FIXTURES / "dropin-observation.jsonl", d / "observation.jsonl")


# ---- the steps -------------------------------------------------------------------------------------------------------------------

def put_engine(inst: Install, jar: Path) -> None:
    (inst.home / "lib" / "engines").mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, inst.home / "lib" / "engines" / jar.name)
    (inst.home / "config" / "engines").mkdir(parents=True, exist_ok=True)
    shutil.copy2(FIXTURES / "wmean.engine.yaml", inst.home / "config" / "engines" / f"{PIN['engine']}.yaml")


def remove_engine(inst: Install) -> None:
    for j in (inst.home / "lib" / "engines").glob("*.jar"):
        j.unlink()
    (inst.home / "config" / "engines" / f"{PIN['engine']}.yaml").unlink(missing_ok=True)


def step1_copy(rep: Report, inst: Install, jar: Path) -> dict | None:
    s = "1 copy"
    base = engine_entry(inst, PIN["engine"])
    rep.add(s, "before: the engine is not installed", base is None or str(base.get("state", "")).lower() in ("not installed", "not-installed"), words(base))
    put_engine(inst, jar)
    load_observations(inst)
    r = inst.restart()
    if not rep.add(s, "restart with the JAR in lib/engines", r.returncode == 0, (r.stdout + r.stderr)[-300:]):
        return None
    e = wait_state(inst, PIN["engine"], "running")
    rep.add(s, "lifecycle: RUNNING", e and str(e.get("state", "")).lower() == "running", words(e))
    st, h = inst.call("GET", fill(API["health"], id=PIN["engine"]))
    rep.add(s, "health(): UP", st == 200 and str(find(h, "state") or find(h, "status")).upper() == "UP", f"{st} {as_text(h)[:200]}")
    st0, s0 = inst.call("GET", fill(API["stats"], id=PIN["engine"]))
    before = counters(s0)
    st, ans = ask(inst)
    want = PIN["answer"]
    rep.add(s, "the request answers the recorded answer", st == 200 and same_figures(table_of(ans), want), f"{st} {as_text(ans)[:240]}")
    st1, s1 = inst.call("GET", fill(API["stats"], id=PIN["engine"]))
    after = counters(s1)
    rep.add(s, "stats() counted that one request", st0 == 200 and st1 == 200 and after.get("asked", 0) - before.get("asked", 0) == 1
            and after.get("answered", 0) - before.get("answered", 0) == 1, f"before {before} after {after}")
    return table_of(ans)


def step2_suspend(rep: Report, inst: Install, recorded: dict | None) -> None:
    s = "2 suspend"
    st, body = lifecycle(inst, "lifecycle", PIN["engine"], "suspend")
    rep.add(s, "suspend accepted", st in (200, 202, 204), f"{st} {as_text(body)[:200]}")
    st, h = inst.call("GET", fill(API["health"], id=PIN["engine"]))
    rep.add(s, "health(): SUSPENDED", str(find(h, "state") or find(h, "status")).upper() == "SUSPENDED", as_text(h)[:200])
    _, s0 = inst.call("GET", fill(API["stats"], id=PIN["engine"]))
    st, ans = ask(inst)
    text = as_text(ans)
    rep.add(s, "a request is refused, in the word 'suspended' (ENG-5023)", st >= 400 and "suspended" in text.lower() and "ENG-5023" in text, f"{st} {text[:240]}")
    _, s1 = inst.call("GET", fill(API["stats"], id=PIN["engine"]))
    rep.add(s, "the engine was not asked (its counters unchanged)", counters(s0) == counters(s1), f"{counters(s0)} {counters(s1)}")
    st, body = lifecycle(inst, "lifecycle", PIN["engine"], "resume")
    rep.add(s, "resume accepted", st in (200, 202, 204), f"{st} {as_text(body)[:200]}")
    st, ans = ask(inst)
    again = table_of(ans)
    rep.add(s, "the same answer, bit for bit", st == 200 and recorded is not None and json.dumps(again, sort_keys=True) == json.dumps(recorded, sort_keys=True),
            f"{st} {as_text(ans)[:240]}")


def step3_licence(rep: Report, inst: Install, jar: Path) -> None:
    s = "3 licence"
    lic = inst.home / "config" / "licence.yaml"
    shutil.copy2(FIXTURES / "licence-without-the-product.yaml", lic)
    r = inst.restart()
    rep.add(s, "the server starts", r.returncode == 0, (r.stdout + r.stderr)[-300:])
    e = wait_state(inst, PIN["engine"], "blocked")
    w = words(e)
    rep.add(s, "the engine is blocked with ENG-1014", e is not None and str(e.get("state", "")).lower() == "blocked" and "ENG-1014" in w, w[:300])
    rep.add(s, "the words name the product", PIN["product"] in w, w[:300])
    st, rows = inst.call("GET", API["engines"])
    rows = rows.get("engines") if isinstance(rows, dict) else rows
    others = [e for e in rows or [] if isinstance(e, dict) and e.get("id") not in (PIN["engine"], PIN["neighbour"]) and str(e.get("state", "")).lower() == "running"]
    failed = [e.get("id") for e in rows or [] if isinstance(e, dict) and e.get("id") not in (PIN["engine"], PIN["neighbour"])
              and str(e.get("state", "")).lower() == "blocked"]
    rep.add(s, "nothing else fails: the server's own engines still run", st == 200 and bool(others) and not failed, f"running {len(others)}, blocked {failed}")
    st, _ = inst.call("GET", "/actuator/health", timeout=30)
    rep.add(s, "nothing else fails: the server's health answers", st == 200, f"{st}")
    lic.unlink(missing_ok=True)


def step4_version(rep: Report, inst: Install, jar: Path, scratch: Path) -> None:
    s = "4 version"
    remove_engine(inst)
    bad = scratch / jar.name.replace(".jar", "-next.jar")
    rewrite_jar(jar, bad, PIN["engine"], f'api: "{PIN["engineApi"]}"', 'api: "0.9"')
    put_engine(inst, bad)
    r = inst.restart()
    rep.add(s, "the server starts", r.returncode == 0, (r.stdout + r.stderr)[-300:])
    e = wait_state(inst, PIN["engine"], "blocked")
    w = str(e.get("words") or e.get("reason") or "") if e else ""
    rep.add(s, "blocked with ENG-1002", e is not None and "ENG-1002" in words(e), words(e)[:300])
    one = len(re.findall(r"[.!?](\s|$)", w.strip())) <= 3 and "\n" not in w.strip()
    rep.add(s, "refused in words: both versions and what to do", "0.9" in w and PIN["engineApi"] in w and "Install" in w and one, w[:300])
    n = engine_entry(inst, PIN["neighbour"])
    rep.add(s, "nothing else fails: the other engine of the JAR is running", n is not None and str(n.get("state", "")).lower() == "running", words(n)[:200])
    bad.unlink(missing_ok=True)


def work_files(inst: Install, token: str) -> list[str]:
    """Every file or folder under the data and install work folders whose name carries ``token`` (logs excepted: they may name it)."""
    out = []
    for base in (inst.data, inst.home / "lib", inst.home / "config"):
        for p in base.rglob("*"):
            if token in p.name and "logs" not in p.relative_to(base).parts[:1]:
                out.append(str(p.relative_to(inst.root)))
    return out


def step5_removal(rep: Report, inst: Install, jar: Path) -> None:
    s = "5 removal"
    remove_engine(inst)
    put_engine(inst, jar)          # a good one first, so the removal is of something that ran
    inst.restart()
    wait_state(inst, PIN["engine"], "running")
    remove_engine(inst)
    r = inst.restart()
    rep.add(s, "the server starts without it", r.returncode == 0, (r.stdout + r.stderr)[-300:])
    for eid in (PIN["engine"], PIN["neighbour"]):
        e = engine_entry(inst, eid)
        rep.add(s, f"no registration of {eid}", e is None or str(e.get("state", "")).lower() in ("not installed", "not-installed"), words(e)[:200])
    th = inst.threads()
    if th is None:
        rep.add(s, "no thread of the engines", True, "skipped: no /proc on this machine")
    else:
        mine = [t for t in th if PIN["engine"] in t or PIN["neighbour"] in t]
        rep.add(s, "no thread of the engines", not mine, ", ".join(mine))
    left = work_files(inst, PIN["engine"]) + work_files(inst, PIN["neighbour"]) + work_files(inst, "engine-sample")
    left = [f for f in left if "dropin" not in f]
    rep.add(s, "no file left in the work folders", not left, ", ".join(left[:8]))
    st, pack = inst.call("GET", fill(API["packHealth"], id=PIN["pack"]["name"]))
    w = as_text(pack)
    rep.add(s, "the pack that uses the engine says the dependency is missing, in words",
            st == 200 and PIN["engine"] in w and re.search(r"not installed|missing|no JAR|ENG-5030", w) is not None, f"{st} {w[:300]}")


def put_pack_and_connector(inst: Install) -> None:
    p = inst.home / "config" / "packs" / PIN["pack"]["domain"] / PIN["pack"]["name"]
    p.mkdir(parents=True, exist_ok=True)
    shutil.copy2(FIXTURES / "pack.yaml", p / "pack.yaml")
    (inst.home / "config" / "connectors").mkdir(parents=True, exist_ok=True)
    shutil.copy2(FIXTURES / "dropin-files.connector.yaml", inst.home / "config" / "connectors" / f"{PIN['connector']['name']}.yaml")
    load_observations(inst)


def remove_pack_and_connector(inst: Install) -> None:
    shutil.rmtree(inst.home / "config" / "packs" / PIN["pack"]["domain"] / PIN["pack"]["name"], ignore_errors=True)
    (inst.home / "config" / "connectors" / f"{PIN['connector']['name']}.yaml").unlink(missing_ok=True)


def lifecycle_cycle(rep: Report, inst: Install, step: str, label: str, ident: str, health: str, stats: str, route: str, probe) -> None:
    """The kernel Lifecycle contract (kernel/drishti-api .../lifecycle/Lifecycle.java) read through the part's routes."""
    st, h = inst.call("GET", fill(API[health], id=ident))
    rep.add(step, f"{label}: health UP", st == 200 and str(find(h, "status") or find(h, "state")).upper() == "UP", f"{st} {as_text(h)[:200]}")
    st0, s0 = inst.call("GET", fill(API[stats], id=ident))
    ok, detail = probe()
    st1, s1 = inst.call("GET", fill(API[stats], id=ident))
    rep.add(step, f"{label}: a request answers and is counted", ok and st0 == 200 and st1 == 200 and as_text(s0) != as_text(s1), f"{detail} | {as_text(s0)[:100]} -> {as_text(s1)[:100]}")
    st, body = lifecycle(inst, route, ident, "suspend")
    rep.add(step, f"{label}: suspend", st in (200, 202, 204), f"{st} {as_text(body)[:200]}")
    _, h = inst.call("GET", fill(API[health], id=ident))
    rep.add(step, f"{label}: health SUSPENDED", str(find(h, "status") or find(h, "state")).upper() == "SUSPENDED", as_text(h)[:200])
    ok, detail = probe()
    rep.add(step, f"{label}: a request answers DRS-1037 in words", (not ok) and "DRS-1037" in detail and "suspended" in detail.lower(), detail[:240])
    st, body = lifecycle(inst, route, ident, "resume")
    rep.add(step, f"{label}: resume", st in (200, 202, 204), f"{st} {as_text(body)[:200]}")
    ok, detail = probe()
    rep.add(step, f"{label}: the same request answers again", ok, detail[:240])
    st, body = lifecycle(inst, route, ident, "resume")
    rep.add(step, f"{label}: an illegal transition is refused DRS-1036 and changes nothing", st == 409 and "DRS-1036" in as_text(body), f"{st} {as_text(body)[:200]}")


def step6_pack_connector(rep: Report, inst: Install) -> None:
    s = "6 pack+conn"
    put_pack_and_connector(inst)
    r = inst.restart()
    if not rep.add(s, "restart with the pack and the connector dropped in", r.returncode == 0, (r.stdout + r.stderr)[-300:]):
        return
    pack, conn, kind = PIN["pack"]["name"], PIN["connector"]["name"], PIN["pack"]["kind"]

    def read() -> tuple[bool, str]:
        st, body = inst.call("GET", fill(API["connectorRead"], kind=kind))
        return st == 200, f"{st} {as_text(body)[:200]}"

    lifecycle_cycle(rep, inst, s, "pack " + pack, pack, "packHealth", "packStats", "packLifecycle", read)
    lifecycle_cycle(rep, inst, s, "connector " + conn, conn, "connectorHealth", "connectorStats", "connectorLifecycle", read)
    remove_pack_and_connector(inst)
    inst.restart()
    st, _ = inst.call("GET", fill(API["packHealth"], id=pack))
    rep.add(s, "removed: the pack is gone", st in (404, 410), f"{st}")
    st, _ = inst.call("GET", fill(API["connectorHealth"], id=conn))
    rep.add(s, "removed: the connector is gone", st in (404, 410), f"{st}")
    left = work_files(inst, pack) + work_files(inst, conn)
    rep.add(s, "removed: no file left in the work folders", not [f for f in left if "dropin-files" not in f], ", ".join(left[:8]))
    th = inst.threads()
    rep.add(s, "removed: no thread of the connector", th is None or not [t for t in th if "dropin" in t], "")


# ---- the lane --------------------------------------------------------------------------------------------------------------------

def run(log: Path | None, keep: bool, engine_jar: Path | None) -> int:
    sys.path.insert(0, str(HERE))
    import container_smoke  # the drill's shared test lake (cached per hash of its sources)

    rep = Report()
    base = Path(os.environ.get("DRISHTI_DRILL_TMP") or ROOT / "data" / "tmp")
    base.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix="dropin-", dir=base))
    inst = None
    try:
        jar = build_engine_jar(scratch, engine_jar)
        inst = Install(scratch, container_smoke.lake())
        hash0 = inst.unpack()
        recorded = None
        s = "0 server"
        r = inst.start()
        rep.add(s, "the server jar starts with an empty lib/engines", r.returncode == 0, (r.stdout + r.stderr)[-300:])
        st, plat = inst.call("GET", API["platform"])
        rep.add(s, "the server states the engine API it provides", st == 200 and PIN["engineApi"] in as_text(plat), f"{st} {as_text(plat)[:200]}")
        recorded = step1_copy(rep, inst, jar)
        step2_suspend(rep, inst, recorded)
        step3_licence(rep, inst, jar)
        step4_version(rep, inst, jar, scratch)
        step5_removal(rep, inst, jar)
        step6_pack_connector(rep, inst)
        rep.add("7 one jar", "the one server jar, by hash, ran every step", sha256(inst.jar) == hash0, f"{hash0[:16]} {sha256(inst.jar)[:16]}")
    except subprocess.CalledProcessError as e:
        rep.add("setup", "the lane's own commands", False, f"{e.cmd[:3]} exit {e.returncode}")
    finally:
        if inst is not None:
            inst.stop()
            (scratch / "server.log").write_text(inst.log()[-20000:], encoding="utf-8") if keep else None
        if not keep:
            shutil.rmtree(scratch, ignore_errors=True)
    text = rep.table()
    print(text)
    if log:
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(text, encoding="utf-8")
    return 0 if rep.passed() else 1


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--log", type=Path)
    r.add_argument("--keep", action="store_true", help="leave the scratch install and the server's log")
    r.add_argument("--engine-jar", type=Path, help="a built engine-sample JAR instead of building one from the pin")
    sub.add_parser("jar")
    a = ap.parse_args(argv)
    if a.cmd == "jar":
        with tempfile.TemporaryDirectory(prefix="dropin-") as t:
            j = build_engine_jar(Path(t))
            keep = Path(tempfile.gettempdir()) / j.name
            shutil.copy2(j, keep)
            print(keep)
        return 0
    return run(a.log, a.keep, a.engine_jar)


if __name__ == "__main__":
    sys.exit(main())
