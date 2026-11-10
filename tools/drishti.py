#!/usr/bin/env python3
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

"""drishti: one command line for building, checking, loading and shipping Drishti packs and Sutras.

    python3 tools/drishti.py <group> <command> [options]          (or: uv run --with pyyaml python tools/drishti.py ...)

Groups: sutra (the Java `sutra` tool, plus `sutra gen`), module (pack, check, inspect a .dmod with the module tool), pack (make, new, regenerate, diff, catalogue, i18n, check, about-check, bundle, verify, deploy, rollback,
publish, keygen, install), data (profile and ingest your own JSON Lines or watch a drop folder, load the demo data), server (health, packs, smoke),
lake (build a Delta lake from a large JSON export, scan, status, maintain), feed (pull external data into the lakes: list, run, status, schedule), testdata (capture-market: a real offline snapshot), view (get, explain), history (an entity over business days), doctor, completion, design (the Screen Designer's designs over
REST: create, save, check, propose, approve, export, import, bind) and docs (screenshots).
Needs Python 3.10+ and PyYAML; deltalake and pyarrow only for `--store delta` ingests. The guide with every command,
recipes and real output is docs/guides/CLI_REFERENCE.md.

Exit codes: 0 ok, 1 problems found (a failing check, a server that said no, an unreachable server), 2 usage (a bad option,
a missing file or jar). Commands that talk to a server read it from --server or DRISHTI_SERVER (default
http://localhost:18480) and the token from DRISHTI_TOKEN or --token-file; the token is never printed.
"""
from __future__ import annotations

import argparse
import datetime
import glob
import importlib.util
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import drishti_parser
from drishti_parser import DEFAULT_SERVER
from javacmd import cli_tmp, java_cmd

TOOLS = pathlib.Path(__file__).resolve().parent
ROOT = TOOLS.parent
LOADERS = ("delta", "files", "postgres", "duckdb", "iceberg", "mongodb", "redis", "aerospike", "cassandra")
# the reusable parts, documented in the Python API reference (Help -> Developer; tools/api_docs.py checks each has a docstring and type
# hints); the cmd_* functions are the command line's own handlers
__all__ = ["CliError", "ApiError", "Api", "find_jar", "find_java", "run_java", "build_parser", "main"]


class CliError(Exception):
    """A failure to report as one line; `code` is the exit code (1 problems, 2 usage)."""

    def __init__(self, message: str, code: int = 2):
        super().__init__(message)
        self.code = code


# ------------------------------------------------------------------------------------------------ small helpers

def load_module(name: str, rel: str):
    """Imports a sibling tool by file; its heavy imports (PyYAML, deltalake, pyarrow) happen only when a command needs them."""
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, TOOLS / rel)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    sys.path.insert(0, str(TOOLS))
    try:
        spec.loader.exec_module(mod)
    finally:
        sys.path.remove(str(TOOLS))
    return mod


def cmd_testdata_capture_market(a, extra) -> int:
    return load_module("capture_market", "testdata/capture_market.py").run(a)


def sutragen():
    return load_module("sutragen", "sutragen.py")


def need_yaml():
    try:
        import yaml
        return yaml
    except ModuleNotFoundError:
        raise CliError("PyYAML is not installed: run with  uv run --with pyyaml python tools/drishti.py ...  or  pip install pyyaml") from None


def say_json(obj) -> None:
    print(json.dumps(obj, indent=2, ensure_ascii=False))


def find_jar(explicit: str | None = None) -> str:
    """The server's exec jar: ``explicit``, else ``DRISHTI_JAR``, else the newest ``drishti-server/target/drishti-server-*-exec.jar``."""
    jar = explicit or os.environ.get("DRISHTI_JAR")
    if jar:
        if not os.path.isfile(jar):
            raise CliError(f"jar not found: {jar}")
        return jar
    jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime)
    if not jars:
        raise CliError("no drishti-server-*-exec.jar under drishti-server/target: build it (./mvnw -q -DskipTests package) or pass --jar / DRISHTI_JAR")
    return jars[-1]


def find_java(explicit: str | None = None) -> str:
    """The ``java`` to run: ``explicit``, else ``$JAVA_HOME/bin/java``, else a JDK 21 at its usual place, else ``java`` on the PATH."""
    if explicit:
        return explicit
    for home in (os.environ.get("JAVA_HOME"), "/usr/lib/jvm/java-21-openjdk-amd64"):
        if home and os.path.exists(os.path.join(home, "bin", "java")):
            return os.path.join(home, "bin", "java")
    return shutil.which("java") or "java"


def run_java(a: argparse.Namespace, args: list[str], capture: bool = False) -> subprocess.CompletedProcess:
    """`java -jar <exec jar> sutra <args>` from the repository root (paths in `args` must already be absolute)."""
    java, jar = find_java(getattr(a, "java", None)), find_jar(getattr(a, "jar", None))
    with cli_tmp() as tmp:   # a temp folder outside the data folder: the command writes nothing to the site
        cmd = java_cmd(java, ["-jar", jar, "sutra", *args], tmp=tmp)
        try:
            return subprocess.run(cmd, cwd=ROOT, text=True, capture_output=capture)
        except FileNotFoundError as e:
            raise CliError(f"cannot run {cmd[0]}: {e.strerror}; pass --java or set JAVA_HOME") from None


def find_module_tool(explicit: str | None = None) -> str:
    """The module tool's jar: ``explicit``, else ``DRISHTI_MODULE_TOOL``, else ``drishti-module-runtime/target/drishti-module-tool.jar``."""
    jar = explicit or os.environ.get("DRISHTI_MODULE_TOOL") or str(ROOT / "drishti-module-runtime/target/drishti-module-tool.jar")
    if not os.path.isfile(jar):
        raise CliError(f"module tool not found: {jar}; build it (./mvnw -q package -DskipTests -pl drishti-module-runtime -am) or pass --tool / DRISHTI_MODULE_TOOL")
    return jar


def cmd_module(a, extra) -> int:
    """`module pack|check|inspect`: the standalone module tool (java -jar drishti-module-tool.jar), no Maven needed; the same code as the
    Maven plugin's goals."""
    args = [a.module_cmd, absolute(a.path)]
    for flag, val in (("--jar", getattr(a, "module_jar", None)), ("--out", getattr(a, "out", None)), ("--project", getattr(a, "project", None))):
        if val:
            args += [flag, absolute(val)]
    if getattr(a, "cycles", None) is not None:
        args += ["--cycles", str(a.cycles)]
    java = find_java(getattr(a, "java", None))
    with cli_tmp() as tmp:
        cmd = java_cmd(java, ["-jar", find_module_tool(a.tool), *args], tmp=tmp)
        try:
            return subprocess.run(cmd, cwd=ROOT).returncode
        except FileNotFoundError as e:
            raise CliError(f"cannot run {cmd[0]}: {e.strerror}; pass --java or set JAVA_HOME") from None


NOISE = re.compile(r"^(Standard Commons Logging|\d{4}-\d\d-\d\dT\S+\s+(WARN|INFO|DEBUG|TRACE)\s)")
DIAG = re.compile(r":\d+ (warning|error) DRS-")


def report_lines(r: subprocess.CompletedProcess) -> list[str]:
    """What a `sutra` run said: its stdout, plus the diagnostics (file:line warning|error DRS-nnnn ...) it wrote to stderr; engine log noise left out."""
    out = [x for x in (r.stdout or "").splitlines() if not NOISE.match(x)]
    return out + [x for x in (r.stderr or "").splitlines() if DIAG.search(x)]


def absolute(p) -> str:
    return str(pathlib.Path(p).resolve())


# ------------------------------------------------------------------------------------------------ REST client

class ApiError(CliError):
    """The server answered with an error: ``status``, its ``DRS-`` code and detail, with a hint for 401 and 403; ``body`` is the whole
    problem document when it was JSON."""

    HINTS = {401: " (no or bad token: set DRISHTI_TOKEN or --token-file)",
             403: " (your role or a server setting does not allow it: designing is open to everyone, approving needs an approver, Admin pages an administrator; a personal drk_ token also needs the scope for the call: design:write, design:approve, packs:admin, loads:write, bi:write, bi:approve or bi:admin, see docs/guides/CLI_REFERENCE.md)"}

    def __init__(self, status: int, code: str | None, detail: str):
        if code and detail and detail.startswith(code + " "):
            detail = detail[len(code) + 1:]                      # the code is said once
        super().__init__(f"the server said {status}{' ' + code if code else ''}: {detail}{self.HINTS.get(status, '')}", 1)
        self.status = status
        self.drs = code                                          # the DRS- code (self.code is the exit code)
        self.body = None
        self.retry_after = 0


BUSY_CODE = re.compile(r"^DRS-950[1-57]$")
BUSY_TRIES = 3


def problem_error(status: int, raw: bytes, headers, reason: str = "") -> ApiError:
    """The server's problem answer as an :class:`ApiError`; a busy refusal (busy protection, 503 DRS-9501 to DRS-9505) says the server's own
    words, the same the console shows, and how to wait for it."""
    try:
        j = json.loads(raw)
        j = j if isinstance(j, dict) else {}
    except ValueError:
        j = {}
    code, detail = j.get("code"), j.get("detail") or j.get("message") or j.get("title")
    err = ApiError(status, code, detail or raw[:200].decode("utf-8", "replace") or reason)
    err.body = j or None
    try:
        err.retry_after = max(1, int((headers or {}).get("Retry-After") or j.get("retryAfterSeconds") or 0))
    except (TypeError, ValueError):
        err.retry_after = 30
    if code == "DRS-1020":                                       # a compute engine running nowhere (ADMIN_GUIDE.md): exit 3, the server's words
        err.args = ((detail or "").split(code + " ", 1)[-1] + f" ({code})",)
        err.code = 3
    if status == 503 and (j.get("busy") or BUSY_CODE.match(str(code or ""))):
        err.args = (f"{(detail or '').split(code + ' ', 1)[-1]} ({code}; add --wait, or set DRISHTI_WAIT_BUSY=1, to try again by itself)",)
    return err


def busy_wait(api, err: Exception, tries: int) -> bool:
    """With --wait (or DRISHTI_WAIT_BUSY=1): a busy refusal is waited for, its Retry-After, and tried again, three times at most; says so on
    standard error in the server's words. False: give up (not busy, not waiting, or tried enough)."""
    if not getattr(api, "wait", False) or tries >= BUSY_TRIES or not isinstance(err, ApiError) or err.status != 503:
        return False
    body = err.body or {}
    if not (body.get("busy") or BUSY_CODE.match(str(err.drs or ""))):
        return False
    because = str(body.get("because") or body.get("title") or "the server is busy")
    secs = err.retry_after or 30
    print(f"{because[:1].upper() + because[1:]}; trying again in {secs} s ({err.drs}) ...", file=sys.stderr)
    time.sleep(secs)
    return True


class Api:
    """A tiny REST client: Bearer token from DRISHTI_TOKEN / --token-file (never printed), JSON in and out."""

    def __init__(self, base: str, token: str | None = None, user: str | None = None, timeout: float = 60, wait: bool = False):
        self.base, self.token, self.user, self.timeout = base.rstrip("/"), token, user, timeout
        self.wait = wait                                                   # busy protection: wait for a busy refusal's Retry-After and try again

    @classmethod
    def from_args(cls, a: argparse.Namespace) -> "Api":
        """A client from the command line's ``--server``, ``--token-file``, ``--user`` and ``--timeout``, else ``DRISHTI_SERVER``,
        ``DRISHTI_TOKEN`` and ``DRISHTI_USER``."""
        token = None
        if getattr(a, "token_file", None):
            try:
                token = pathlib.Path(a.token_file).read_text(encoding="utf-8").strip()
            except OSError as e:
                raise CliError(f"cannot read --token-file {a.token_file}: {e.strerror}") from None
        token = token or os.environ.get("DRISHTI_TOKEN") or None
        wait = getattr(a, "wait_busy", False) or os.environ.get("DRISHTI_WAIT_BUSY", "").lower() in ("1", "true", "yes")
        return cls(getattr(a, "server", None) or os.environ.get("DRISHTI_SERVER") or DEFAULT_SERVER, token,
                   getattr(a, "user", None) or os.environ.get("DRISHTI_USER"), getattr(a, "timeout", 60), wait)

    def request(self, method: str, path: str, body: object = None, data: bytes | None = None, ctype: str | None = None,
                extra: dict | None = None) -> tuple[int, bytes]:
        """One call: ``body`` sent as JSON (or ``data`` as ``ctype``), ``extra`` headers added; the status and the raw answer.
        Raises :class:`ApiError` for an error answer and :class:`CliError` when the server cannot be reached."""
        headers = {"Accept": "application/json, application/zip;q=0.9"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        elif self.user:
            headers["X-Drishti-User"] = self.user
        if body is not None:
            data, ctype = json.dumps(body).encode("utf-8"), "application/json"
        if ctype:
            headers["Content-Type"] = ctype
        tries = 0
        while True:
            req = urllib.request.Request(self.base + path, data=data, method=method, headers={**headers, **(extra or {})})
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:  # noqa: S310 - the URL is the operator's own --server
                    self.headers = r.headers                                   # the last answer's headers (a statement's X-Statement-Cut)
                    return r.status, r.read()
            except urllib.error.HTTPError as e:
                # the whole problem rides on the error (a 409 DRS-9082 carries the estimate and its confirm token)
                err = problem_error(e.code, e.read(), e.headers, str(e.reason))
                if busy_wait(self, err, tries):
                    tries += 1
                    continue
                raise err from None
            except urllib.error.URLError as e:
                raise CliError(f"cannot reach {self.base}: {e.reason}; is the server running? (--server / DRISHTI_SERVER)", 1) from None
            except OSError as e:
                raise CliError(f"cannot reach {self.base}: {e}", 1) from None

    def json(self, method: str, path: str, body: object = None, **kw: object) -> object:
        """One call (as :meth:`request`), its answer parsed as JSON; ``{}`` for an empty answer."""
        _, raw = self.request(method, path, body, **kw)
        return json.loads(raw) if raw.strip() else {}


def q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


# ------------------------------------------------------------------------------------------------ sutra

def cmd_sutra(a, extra: list[str]) -> int:
    if a.sutra_cmd == "design" and getattr(a, "schema", None):
        SP = load_module("schemapack", "schemapack.py")
        try:
            return SP.design(a, sys.modules[__name__])
        except SP.SchemaPackError as e:
            raise CliError(str(e), e.code) from None
    if not a.paths:
        raise CliError(f"sutra {a.sutra_cmd}: give the PATH to work on (or, for design, --schema FILE...)")
    args = [a.sutra_cmd, *[absolute(p) for p in a.paths]]
    for flag, val in (("--out", a.out), ("--junit", a.junit), ("--samples", a.samples)):
        if val:
            args += [flag, absolute(val)]
    if a.kind:
        args += ["--kind", a.kind]
    if a.strict:
        args.append("--strict")
    if a.each:
        args.append("--each")
    return run_java(a, args + extra).returncode


def cmd_sutra_gen(a, extra) -> int:
    SG = sutragen()
    a.jar, a.java = find_jar(a.jar), find_java(a.java)
    kinds = SG.load_folder(a.inputs, a.recursive, a.kind)
    groups = SG.generate(kinds, a, a.out, a.tests_dir)
    SG.summary(groups)
    return 0 if a.no_lint else (1 if SG.lint(a, a.out) else 0)


# ------------------------------------------------------------------------------------------------ pack

def cmd_pack_new(a, extra) -> int:
    need_yaml()
    PFJ = load_module("pack_from_jsonl", "packgen/pack_from_jsonl.py")
    if a.load and not (a.server or os.environ.get("DRISHTI_SERVER")):
        raise CliError("--load needs --server URL (or DRISHTI_SERVER)")
    a.jar, a.java = find_jar(a.jar), find_java(a.java)
    rc = PFJ.run(a)
    if rc or not a.load:
        return rc
    out = (a.out or ROOT / "config" / "packs" / (getattr(a, "pack_domain", None) or "core") / a.name).resolve()
    if out.parent.parent != (ROOT / "config" / "packs").resolve():           # config/packs/<domain>/<pack>
        print(f"drishti: --load: the server reads packs from its own packs folder (drishti.packs.dir) or installed-dir; {out} is elsewhere, "
              f"so the server only finds it if that folder is its pack folder.", file=sys.stderr)
    return server_pack_change(a, "load", a.name)


def cmd_pack_make(a, extra) -> int:
    PM = load_module("packmake", "packmake.py")
    try:
        return PM.make(a, sys.modules[__name__])
    except PM.MakeError as e:
        raise CliError(str(e), e.code) from None


def check_ingest_block(pack: pathlib.Path) -> list[str]:
    """The optional tooling key `ingest: {kind: {key, date}}` (read by tools/ingest_jsonl.py; the server ignores it)."""
    try:
        m = need_yaml().safe_load((pack / "pack.yaml").read_text(encoding="utf-8")) or {}
    except OSError:
        return [f"{pack}/pack.yaml: not found"]
    ing = m.get("ingest")
    if ing is None:
        return []
    if not isinstance(ing, dict):
        return ["pack.yaml ingest: must be a map of kind: {key, date}"]
    problems, kinds = [], set(m.get("kinds") or [])
    for k, v in ing.items():
        if k not in kinds:
            problems.append(f"pack.yaml ingest: '{k}' is not one of the pack's kinds")
        if not isinstance(v, dict) or not isinstance(v.get("key"), str) or not (isinstance(v.get("date"), str) or v.get("snapshot") is True):
            problems.append(f"pack.yaml ingest.{k}: needs key: <field> and date: <field> (or snapshot: true, stored under the day of the load)")
    return problems


def check_form_block(pack: pathlib.Path) -> list[str]:
    """The optional `form:` section (query by form, `FORM <pack>`): each kind's fields, their types and operators (tools/packform.py)."""
    try:
        m = need_yaml().safe_load((pack / "pack.yaml").read_text(encoding="utf-8")) or {}
    except OSError:
        return [f"{pack}/pack.yaml: not found"]
    return load_module("packform", "packform.py").validate(m)


RESERVED_WORDS = ("ADMIN", "BI", "DATA", "DATAMAP", "EXPORT", "FORM", "FUNC", "RUPAKA", "WHAT")      # command words the console owns (the server's PackLoader.RESERVED_WORDS)


def check_reserved_mnemonics(pack: pathlib.Path) -> list[str]:
    """A pack may not define a mnemonic that is a reserved command word, in any case (ARCHITECTURE.md §28 › Rūpaka: the original design and every phase as built, section 4): the server refuses to load it."""
    try:
        m = need_yaml().safe_load((pack / "pack.yaml").read_text(encoding="utf-8")) or {}
    except OSError:
        return []
    return [f"{pack}/pack.yaml: mnemonics.{k}: '{k}' is a reserved command word ({', '.join(RESERVED_WORDS)}): choose another mnemonic"
            for k in (m.get("mnemonics") or {}) if str(k).upper() in RESERVED_WORDS]


def cmd_pack_check(a, extra) -> int:
    junit = pathlib.Path(a.junit).resolve() if a.junit else None
    if junit:
        junit.mkdir(parents=True, exist_ok=True)
    report, rc = [], 0
    for p in a.packs:
        pack = pathlib.Path(p).resolve()
        if not (pack / "pack.yaml").is_file():
            raise CliError(f"{p} is not a pack folder (no pack.yaml)")
        entry = {"pack": pack.name, "path": str(pack), "steps": {}}
        for step in ("lint", "test"):
            args = [step, str(pack)] + (["--strict"] if step == "lint" and a.strict else [])
            if junit:
                args += ["--junit", str(junit / f"{pack.name}-{step}.xml")]
            r = run_java(a, args, capture=True)
            lines = report_lines(r)
            entry["steps"][step] = {"exit": r.returncode, "output": "\n".join(lines), "error": (r.stderr or "").strip()[-1500:]}
            rc = rc or r.returncode
            if not a.json:
                print(f"== {pack.name}: sutra {step}{' --strict' if step == 'lint' and a.strict else ''}: exit {r.returncode}")
                if a.tail:
                    print("\n".join(lines[-a.tail:]))
                if r.returncode and r.stderr.strip() and not any(DIAG.search(x) for x in lines):
                    print(r.stderr.strip()[-800:], file=sys.stderr)        # nothing but engine output to explain the failure: show it
        entry["packYaml"] = check_ingest_block(pack) + check_form_block(pack) + check_reserved_mnemonics(pack)
        for pr in entry["packYaml"]:
            print(pr, file=sys.stderr)
        entry["ok"] = all(s["exit"] == 0 for s in entry["steps"].values()) and not entry["packYaml"]
        rc = rc or (0 if entry["ok"] else 1)
        report.append(entry)
    if a.json:
        say_json({"ok": rc == 0, "packs": report})
    return 1 if rc else 0


WARNING = re.compile(r"^(?P<file>.+?):(?P<line>\d+) warning (?P<code>DRS-204[567]) (?P<text>.*)$")
FIELD_TEXT = re.compile(r"field '([^']+)'")


def about_todos(pack: pathlib.Path) -> list[str]:
    """Placeholder texts ('TODO: ...') still in config/about.yaml, as `kind.field` or `kind` (what pack new writes for you to fill in)."""
    f = pack / "config" / "about.yaml"
    if not f.is_file():
        return []
    about = need_yaml().safe_load(f.read_text(encoding="utf-8")) or {}
    todo = [f"vocabulary.{k}" for k, v in (about.get("vocabulary") or {}).items() if str((v or {}).get("means", "")).startswith("TODO")]
    for kind, ka in (about.get("kinds") or {}).items():
        ka = ka or {}
        if str(ka.get("about", "")).startswith("TODO"):
            todo.append(kind)
        todo += [f"{kind}.{fld}" for fld, v in (ka.get("glossary") or {}).items() if str((v or {}).get("means", "")).startswith("TODO")]
    return todo


def cmd_pack_about_check(a, extra) -> int:
    reports, bad = [], 0
    for p in a.packs:
        pack = pathlib.Path(p).resolve()
        if not (pack / "pack.yaml").is_file():
            raise CliError(f"{p} is not a pack folder (no pack.yaml)")
        r = run_java(a, ["lint", str(pack)], capture=True)
        shown: dict[str, dict] = {}
        for line in report_lines(r):
            m = WARNING.match(line)
            if m:
                sutra = pathlib.Path(m["file"]).name.replace(".sutra.yaml", "")
                fld = FIELD_TEXT.search(m["text"])
                entry = shown.setdefault(sutra, {"missing": [], "other": []})
                if m["code"] == "DRS-2047" and fld:
                    entry["missing"].append(fld.group(1))
                else:
                    entry["other"].append(f"{m['code']} {m['text']}")
        todos = about_todos(pack)
        gaps = sum(len(v["missing"]) + len(v["other"]) for v in shown.values()) + (len(todos) if a.strict else 0)
        bad += gaps
        reports.append({"pack": pack.name, "sutras": shown, "todo": todos, "gaps": gaps, "lintExit": r.returncode})
        if not a.json:
            print(f"== {pack.name}: {sum(len(v['missing']) for v in shown.values())} field(s) shown without glossary text in {len(shown)} Sutra(s); "
                  f"{len(todos)} placeholder TODO text(s) in config/about.yaml")
            for name, v in sorted(shown.items()):
                print(f"  {name}: " + ", ".join(v["missing"] + v["other"]))
            if todos:
                print("  TODO: " + ", ".join(todos[:30]) + (" ..." if len(todos) > 30 else ""))
    if a.json:
        say_json({"ok": bad == 0, "strict": a.strict, "gaps": bad, "packs": reports})
    elif bad:
        print(f"{bad} gap(s): add glossary entries under kinds.<kind>.glossary (or vocabulary) in config/about.yaml; see PACK_DEVELOPER_GUIDE.")
    return 1 if bad else 0


def cmd_pack_publish(a, extra) -> int:
    pr = load_module("packreg", "packreg/packreg.py")
    try:
        pr.publish(pathlib.Path(a.pack), pathlib.Path(a.registry), pathlib.Path(a.key), a.publisher)
    except (OSError, subprocess.CalledProcessError, KeyError) as e:
        raise CliError(f"publish failed: {e}", 1) from None
    if a.json:
        say_json({"published": True, "pack": a.pack, "registry": a.registry, "publisher": a.publisher})
    return 0


def cmd_pack_keygen(a, extra) -> int:
    load_module("packreg", "packreg/packreg.py").keygen(pathlib.Path(a.out))
    return 0


def pack_bundle():
    return load_module("packbundle", "packbundle.py")


def tool_versions() -> dict:
    return {"python": ".".join(map(str, sys.version_info[:3])), "server": pack_bundle().server_version(ROOT)}


def cmd_pack_bundle(a, extra) -> int:
    PB = pack_bundle()
    need_yaml()
    pack = pathlib.Path(a.pack).resolve()
    out = pathlib.Path(a.out).resolve() if a.out else pathlib.Path.cwd() / "dist"
    if not a.no_check:
        chk = argparse.Namespace(packs=[str(pack)], strict=False, junit=None, tail=0, json=False, jar=a.jar, java=a.java)
        if cmd_pack_check(chk, extra):
            raise CliError("the pack fails `pack check`: fix it first (or --no-check to bundle anyway)", 1)
    try:
        res = PB.bundle(pack, out, tool_versions(), a.requires_server or PB.server_version(ROOT))
    except PB.BundleError as e:
        raise CliError(str(e), e.code) from None
    if a.json:
        say_json({k: v for k, v in res.items() if k != "manifest"})
    else:
        print(f"bundled {res['pack']} {res['version']}: {res['files']} files")
        print(f"  {res['archive']}\n  sha256 {res['sha256']}")
    return 0


def verify_bundle(a, source: pathlib.Path, server: str | None) -> tuple[dict, "object"]:
    """Runs every offline check; returns (report, Opened). The caller cleans up the Opened."""
    PB = pack_bundle()
    need_yaml()
    try:
        op = PB.Opened(source)
    except PB.BundleError as e:
        raise CliError(str(e), e.code) from None
    rep = {"source": str(source), "checks": {}, "ok": True}

    def record(step: str, problems: list[str]):
        rep["checks"][step] = problems
        rep["ok"] = rep["ok"] and not problems
    meta = PB.read_pack_yaml(op.pack)
    rep.update(pack=str(meta.get("pack")), version=str(meta.get("version")))
    man, probs = PB.check_manifest(op.pack)
    record("checksums", probs)
    if man is None:
        rep["notes"] = ["no MANIFEST.json (a plain folder): checksums not checked"]
    record("schema", PB.check_schema(op.pack) + check_ingest_block(op.pack) + check_form_block(op.pack))
    record("server", PB.check_server(man, server))
    if a.no_sutra:
        rep["checks"]["sutra"] = ["skipped (--no-sutra)"]
    else:
        probs = []
        for step in ("lint", "test"):
            r = run_java(a, [step, str(op.pack)] + (["--strict"] if step == "lint" and a.strict else []), capture=True)
            if r.returncode:
                probs.append(f"sutra {step} failed (exit {r.returncode}): " + " | ".join(report_lines(r)[-3:]))
        record("sutra", probs)
    rep["notes"] = rep.get("notes", []) + op.notes
    return rep, op


def print_verify(rep: dict) -> None:
    print(f"verify {rep['source']}: {rep.get('pack')} {rep.get('version')}")
    for step, probs in rep["checks"].items():
        print(f"  {step:<10}{'ok' if not probs else ('skipped' if probs[0].startswith('skipped') else 'FAILED')}")
        for pr in probs:
            if not pr.startswith("skipped"):
                print(f"    - {pr}")
    for n in rep.get("notes", []):
        print(f"  note: {n}")
    print("verified" if rep["ok"] else "NOT verified")


def cmd_pack_verify(a, extra) -> int:
    if a.registry:
        if not (a.publisher and a.public_key):
            raise CliError("registry verification needs --registry, --publisher and --public-key")
        return 1 if load_module("packreg", "packreg/packreg.py").verify(pathlib.Path(a.registry), a.publisher, a.public_key) else 0
    if not a.source:
        raise CliError("pack verify BUNDLE|FOLDER   (or --registry R --publisher P --public-key K for a registry)")
    rep, op = verify_bundle(a, pathlib.Path(a.source), a.server_version or pack_bundle().server_version(ROOT))
    op.cleanup()
    if a.json:
        say_json(rep)
    else:
        print_verify(rep)
    return 0 if rep["ok"] else 1


def cmd_pack_deploy(a, extra) -> int:
    PB = pack_bundle()
    rep, op = verify_bundle(a, pathlib.Path(a.source), a.server_version or PB.server_version(ROOT))
    try:
        if not a.json:
            print_verify(rep)
        if not rep["ok"]:
            if a.json:
                say_json(rep)
            raise CliError("not deploying: verification failed", 1)
        res = PB.deploy(op, pathlib.Path(a.to).resolve(), a.backup, a.dry_run)
    except PB.BundleError as e:
        raise CliError(str(e), e.code) from None
    finally:
        op.cleanup()
    if a.json:
        say_json(res)
    elif a.dry_run:
        print(f"dry run: would copy {res['pack']} {res['version']} to {res['target']}"
              + (f", moving {res['replaces']} to {res['backupDir']}" if res["replaces"] else " (a new pack)"))
    else:
        print(f"deployed {res['pack']} {res['version']} to {res['target']}" + (f"; previous {res['replaces']} kept at {res['backup']}" if res["backup"] else ""))
        print("next: a running server watches its packs folder (drishti.packs.watch): it checks the copied pack and loads it by itself within seconds, no restart; a pack that does not check out is shown in Admin > Packs and not loaded. Admin > Packs > Load does it at once, and DRISHTI_PACKS keeps it for every start")
    return 0


def cmd_pack_rollback(a, extra) -> int:
    PB = pack_bundle()
    need_yaml()
    try:
        res = PB.rollback(a.name, pathlib.Path(a.to).resolve(), a.backup, a.version, a.dry_run)
    except PB.BundleError as e:
        raise CliError(str(e), e.code) from None
    if a.json:
        say_json(res)
    else:
        print(("dry run: would restore " if a.dry_run else "restored ") + f"{res['pack']} {res['version']} from {res['restore']}"
              + (f"; the {res['replaces']} it replaced " + (f"is kept at {res['backup']}" if res["backup"] else "stays") if res["replaces"] else ""))
    return 0


def cmd_pack_install(a, extra) -> int:
    api = Api.from_args(a)
    if a.list:
        res = api.json("GET", "/api/v1/admin/registry")
        if a.json:
            say_json(res)
            return 0
        print(f"registry: {res.get('url')}  ({'configured' if res.get('configured') else 'not configured'})")
        for p in res.get("packs", []):
            print(f"  {p['name']:<24}{p['version']:<10}{'trusted' if p.get('trusted') else 'UNTRUSTED':<10}"
                  f"installed {p.get('installedVersion') or '-':<8} loaded {p.get('loadedVersion') or '-':<8} {p.get('publisher')}")
        return 0
    if not a.name or not a.version:
        raise CliError("pack install NAME VERSION (or --list to see the registry)")
    res = api.json("POST", f"/api/v1/admin/registry/{q(a.name)}/{q(a.version)}/install")
    if a.json:
        say_json(res)
    else:
        print(f"installed {a.name} {a.version}" + (f" (replaced {res['replaced']})" if res.get("replaced") else "") + f": {res.get('note', '')}")
    return 0


# ------------------------------------------------------------------------------------------------ data

def cmd_data_ingest(a, extra) -> int:
    need_yaml()
    if a.watch:
        return load_module("ingest_jsonl", "ingest_jsonl.py").ingest_watch_run(a)
    return 1 if load_module("ingest_jsonl", "ingest_jsonl.py").ingest(a).bad else 0


def cmd_data_load(a, extra) -> int:
    cmd = ["bash", str(TOOLS / f"load-{a.store}.sh"), *a.target]
    if a.trades is not None:
        cmd += ["--trades", str(a.trades)]
    if a.days is not None:
        cmd += ["--days", str(a.days)]
    cmd += extra
    if a.dry_run:
        print(" ".join(cmd))
        return 0
    return subprocess.run(cmd, cwd=ROOT).returncode


def load_date(text: str) -> str:
    """--date: yyyy-MM-dd, or today / yesterday (this machine's calendar day)."""
    t = text.strip().lower()
    if t in ("today", "yesterday"):
        d = datetime.date.today() - datetime.timedelta(days=1 if t == "yesterday" else 0)
        return d.isoformat()
    try:
        return datetime.date.fromisoformat(t).isoformat()
    except ValueError:
        raise CliError(f"--date {text!r} is not yyyy-MM-dd (or today, yesterday)") from None


def print_load(r: dict) -> None:
    print(r.get("summary", ""))
    print(f"  load {r.get('id')}  status {r.get('status')}  verified {r.get('verified')}  attempt {r.get('attempt')}"
          f"{'  (a reload)' if r.get('reload') else ''}{'  (already announced: nothing ran again)' if r.get('duplicate') else ''}")
    for st in r.get("steps", []):
        print(f"  {st.get('name'):<8}{st.get('status'):<8}{st.get('detail', '')}")


def cmd_data_landed(a, extra) -> int:
    """Tells the server a batch landed (or failed). Exit 1 when a ready load is not verified (the data is not readable for the date)."""
    body = {"kind": a.kind, "businessDate": load_date(a.date), "status": a.status}
    for key, val in (("rows", a.rows), ("rejected", a.rejected), ("batchId", a.batch), ("source", a.source), ("note", a.note)):
        if val is not None:
            body[key] = val
    r = Api.from_args(a).json("POST", f"/api/v1/packs/{q(a.pack)}/loads", body)
    if a.json:
        say_json(r)
    else:
        print_load(r)
    return 1 if r.get("status") == "ready" and r.get("verified") != "verified" else 0


def cmd_data_loads(a, extra) -> int:
    params = [f"limit={a.limit}"] + [f"{k}={q(v)}" for k, v in (("kind", a.kind), ("date", a.date and load_date(a.date)), ("status", a.status)) if v]
    api = Api.from_args(a)
    h = api.json("GET", f"/api/v1/packs/{q(a.pack)}/loads?" + "&".join(params))
    ex = api.json("GET", f"/api/v1/packs/{q(a.pack)}/loads/expectations") if a.expectations else None
    if a.json:
        say_json({**h, **({"expectations": ex["expectations"]} if ex else {})})
        return 0
    rows = h.get("loads", [])
    print(f"{'received':<21}{'kind':<18}{'date':<12}{'status':<8}{'rows':>10}{'rej':>7}  {'verified':<10}{'alerts':>6}{'told':>5}  batch / source")
    for r in rows:
        print(f"{r.get('receivedAt', '')[:19].replace('T', ' '):<21}{r.get('kind', ''):<18}{r.get('businessDate', ''):<12}{r.get('status', ''):<8}"
              f"{str(r.get('rows', '')):>10}{str(r.get('rejected', '')):>7}  {str(r.get('verified', '')):<10}{r.get('alerts', 0):>6}{r.get('notices', 0):>5}"
              f"  {r.get('batchId', '') or ''} {r.get('source', '') or ''}".rstrip())
    if not rows:
        print("no loads recorded")
    if ex:
        print("expectations:")
        for s in ex.get("expectations", []):
            print(f"  {s['businessDate']}  {s['kind']:<18}by {s['by']} {s['zone']:<20}{s['state']}")
    return 0


# ------------------------------------------------------------------------------------------------ bi (Rupaka phase 1)

def bi_item_line(i, width: int = 16) -> str:
    return (f"{i['id']:<{width}} {i['kind']:<9}{(i.get('endorsement') or 'none'):<10}{i.get('status', ''):<10}"
            f"{('v' + str(i['publishedVersion'])) if i.get('publishedVersion') else '-':<5}{i['title']}  ({i.get('workspaceName') or i.get('workspace')}, {i.get('owner')})")


def cmd_bi_workspace(a, extra) -> int:
    api = Api.from_args(a)
    if a.action == "list":
        rows = api.json("GET", "/api/v1/bi/workspaces")
        if a.json:
            say_json(rows)
            return 0
        print(f"{'id':<30}{'role':<13}{'items':>6}  name")
        for w in rows:
            print(f"{w['id']:<30}{w['role']:<13}{w['items']:>6}  {w['name']}{'  [personal]' if w.get('personal') else ''}{'  [review before publish]' if w.get('reviewRequired') else ''}")
        return 0
    if not a.id:
        raise CliError("bi workspace get needs a workspace id (or 'me' for your personal workspace)", 2)
    w = api.json("GET", f"/api/v1/bi/workspaces/{q(a.id)}")
    items = api.json("GET", f"/api/v1/bi/workspaces/{q(a.id)}/items")
    if a.json:
        say_json({**w, "itemList": items})
        return 0
    print(f"{w['name']}  ({w['id']}, your role: {w['role']}){'  review before publish' if w.get('reviewRequired') else ''}")
    for m in w.get("members") or []:
        print(f"  member {m['username']:<20}{m['role']}")
    width = max([len(i["id"]) for i in items] + [14]) + 2
    for i in items:
        print("  " + bi_item_line(i, width))
    return 0


def cmd_bi_dax(a, extra) -> int:
    r = Api.from_args(a).json("POST", "/api/v1/tables/editor/dax", {"dax": a.text})
    if a.json:
        say_json(r)
        return 0 if r.get("rachana") else 2
    if r.get("rachana"):
        print(r["rachana"])
    for x in r.get("notes") or []:
        print("note: " + x)
    for x in r.get("notConverted") or []:
        print("not converted: " + x)
    return 0 if r.get("rachana") else 2


def cmd_bi_catalogue(a, extra) -> int:
    api = Api.from_args(a)
    if a.action == "list":
        params = [f"{k}={q(v)}" for k, v in (("q", a.q), ("kind", a.kind), ("workspace", a.workspace), ("owner", a.owner), ("endorsement", a.endorsement), ("tag", a.tag),
                                              ("sort", a.sort), ("fresh", a.fresh and str(a.fresh)), ("limit", str(a.limit))) if v]
        r = api.json("GET", "/api/v1/bi/catalogue?" + "&".join(params))
        if a.json:
            say_json(r)
            return 0
        width = max([len(i["id"]) for i in r["items"]] + [14]) + 2
        print(f"{'id':<{width}}{'kind':<9}{'endorsed':<10}{'status':<10}{'ver':<5}title")
        for i in r["items"]:
            print(bi_item_line(i, width))
        print(f"{r['total']} item(s)" + (f", showing {len(r['items'])}" if r["total"] > len(r["items"]) else ""))
        return 0
    if not a.id:
        raise CliError(f"bi catalogue {a.action} needs an item id", 2)
    if a.action == "get":
        i = api.json("GET", f"/api/v1/bi/items/{q(a.id).replace('%3A', ':')}")
        if a.json:
            say_json(i)
            return 0
        print(bi_item_line(i))
        print(f"  {i.get('description') or ''}\n  tags: {', '.join(i.get('tags') or []) or '-'}   views: {i.get('views')}   audience: {i.get('audience')}")
        if a.body:
            print(i.get("body") or "")
        return 0
    i = api.json("POST", f"/api/v1/bi/items/{q(a.id)}/publish", {"note": a.note or ""})        # publish: needs the bi-author power, a member role, and a token with bi:write
    if a.json:
        say_json(i)
    else:
        print(f"published {i['title']} as version {i['publishedVersion']}")
    return 0


# ------------------------------------------------------------------------------------------------ server

def cmd_server_health(a, extra) -> int:
    h = Api.from_args(a).json("GET", "/api/v1/admin/health")
    if a.json:
        say_json(h)
    else:
        s, v = h.get("summary", {}), h.get("server", {})
        print(f"status {h.get('status')}  version {v.get('version')}  java {v.get('java')}  uptime {v.get('uptimeSeconds')}s")
        print(f"packs {s.get('packs')}  sources {s.get('sources')} (degraded {s.get('sourcesDegraded')}, down {s.get('sourcesDown')})  "
              f"packs with problems {s.get('packsWithProblems')}  failed to start {s.get('failedToStart')}")
        sys.stdout.write("".join(x + "\n" for x in load_module("enginescli", "enginescli.py").health_lines(h)))   # linear algebra (engine openblas), then each source
    return 0 if h.get("status") == "OK" else 1


def _mb(b) -> str:
    return "" if b is None else f"{b / 1048576:,.1f} MB"


def cmd_server_caches(a, extra) -> int:
    """server caches [purge NAME|all [--date D] [--cluster]] [--dates NAME]: every cache with where it lives and its size against its limit
    (2.9.7); purge drops a cache, all, or one business date of it, on this server or every member."""
    api = Api.from_args(a)
    if a.action == "purge":
        if not a.name:
            print("server caches purge: give a cache name (see `server caches`) or all", file=sys.stderr)
            return 2
        query = "&".join(x for x in (f"date={q(a.date)}" if a.date else "", "scope=cluster" if a.cluster else "") if x)
        res = api.json("POST", f"/api/v1/admin/caches/{q(a.name)}/purge" + (f"?{query}" if query else ""))
        if a.json:
            say_json(res)
        else:
            print(f"purged: {', '.join(res.get('purged', [])) or a.name}" + (f" (date {res['date']})" if res.get("date") else "")
                  + (f"; whole, no purge by date: {', '.join(res['purgedWhole'])}" if res.get("purgedWhole") else ""))
            for m in res.get("members") or []:
                print(f"  member {m['node']}: {'done' if m['status'] == 200 else 'failed (' + str(m['status']) + ') ' + m.get('answer', '')}")
        return 0
    if a.dates:
        rows = api.json("GET", f"/api/v1/admin/caches/{q(a.dates)}/dates")
        if a.json:
            say_json(rows)
        else:
            for d in rows:
                print(f"{d.get('date') or d.get('businessDate', ''):<12}{d.get('state', ''):<8}{_mb(d.get('sizeBytes')):>12}  "
                      f"{' '.join(f'{k} {v}' for k, v in (d.get('versions') or {}).items())}  {(d.get('digest') or '')[:16]}")
        return 0
    data = api.json("GET", "/api/v1/admin/caches")
    if a.json:
        say_json(data)
        return 0
    rows = data if isinstance(data, list) else data.get("caches", [])
    if isinstance(data, dict):
        m, d = data.get("memory") or {}, data.get("disk") or {}
        print(f"memory: caches {_mb(m.get('usedBytes'))} of {_mb(m.get('budgetBytes'))}, heap {_mb(m.get('heapUsedBytes'))} of {_mb(m.get('heapMaxBytes'))}")
        print(f"disk:   {_mb(d.get('freeBytes'))} free, guard {_mb(d.get('guardBytes'))}")
    for c in rows:
        size = _mb(c.get("sizeBytes")) + (f" / {_mb(c.get('limitBytes'))}" if c.get("limitBytes") else "")
        stats = "  ".join(f"{k} {v}" for k, v in (c.get("stats") or {}).items())
        print(f"{c['name']:<26}{c.get('where') or '':<7}{size:<26}{stats or c.get('type', '')}")
    return 0


def server_pack_change(a, action: str, name: str) -> int:
    api = Api.from_args(a)
    if action in ("load", "unload"):
        res = api.json("POST", f"/api/v1/admin/packs/{q(name)}/{action}")
        text = f"{action} {name}: {res.get('note', '')}"
    else:
        res = api.json("PUT", f"/api/v1/admin/packs/{q(name)}", {"enabled": action == "on"})
        text = f"{name} is {'on' if res.get('enabled') else 'off'}; enabled packs: {', '.join(res.get('enabledPacks', []))}"
    say_json(res) if getattr(a, "json", False) else print(text)
    return 0


def cmd_server_packs(a, extra) -> int:
    if a.packs_cmd != "list":
        return server_pack_change(a, a.packs_cmd, a.name)
    rows = Api.from_args(a).json("GET", "/api/v1/admin/packs")
    if a.json:
        say_json(rows)
        return 0
    print(f"{'pack':<22}{'version':<9}{'loaded':<8}{'on':<5}{'added':<7}kinds")
    for r in rows:
        print(f"{r['name']:<22}{str(r.get('version', '')):<9}{'yes' if r.get('loaded') else 'no':<8}{'yes' if r.get('enabled') else 'no':<5}"
              f"{'yes' if r.get('added') else '':<7}{len(r.get('kinds') or [])}")
    return 0


def cmd_server_commands(a, extra) -> int:
    """Admin -> Functions (docs/guides/ADMIN_GUIDE.md): list the commands with their state, turn some off or on with a reason, or the history."""
    api = Api.from_args(a)
    if a.commands_cmd in ("on", "off"):
        changes = [{"scope": "pack" if a.pack else "command", "name": n if a.pack else n.upper(), "on": a.commands_cmd == "on"} for n in a.names]
        res = api.json("POST", "/api/v1/admin/commands", {"reason": a.reason or "", "changes": changes})
        if getattr(a, "json", False):
            say_json(res)
        else:
            print(f"{', '.join(c['name'] for c in changes)} {'on' if a.commands_cmd == 'on' else 'off'}; "
                  f"{res.get('off', 0)} of {res.get('total', 0)} commands off; every server of the cluster follows within seconds")
        return 0
    if a.commands_cmd == "history":
        res = api.json("GET", "/api/v1/admin/commands/history?limit=" + str(a.limit))
        if getattr(a, "json", False):
            say_json(res)
            return 0
        for e in res.get("items", []):
            print(f"{str(e.get('at', ''))[:19]:<20}{e.get('scope', ''):<8}{e.get('name', ''):<20}{'on' if e.get('on') else 'off':<5}"
                  f"{e.get('actor', ''):<16}{e.get('reason', '')}")
        return 0
    res = api.json("GET", "/api/v1/admin/commands")
    if getattr(a, "json", False):
        say_json(res)
        return 0
    print(f"{res.get('total', 0)} commands, {res.get('off', 0)} off; switches {res.get('hash', '')[:8] or 'none'} on {res.get('node', '')}")
    for p in res.get("packs", []):
        print(f"\n{p['name']}{'' if p.get('on') else '  (pack off)'}")
        for c in p.get("commands", []):
            if a.off and c.get("on"):
                continue
            print(f"  {c['code']:<8}{'on' if c.get('on') else 'OFF':<5}{c.get('label', ''):<34}{c.get('description', '')[:70]}")
    return 0


# ------------------------------------------------------------------------------------------------ design (the Screen Designer's designs)

def design_summary(d: dict) -> str:
    samples = ", ".join(s["name"] for s in d.get("samples", [])) or "none"
    return (f"{d['id']}  {d.get('name') or '(scratch)'}  kind {d.get('kind')}  status {d.get('status')}  rev {d.get('rev')}"
            f"{'  bound ' + d['boundFile'] if d.get('boundFile') else ''}\n  samples: {samples}")


def read_json_files(paths) -> list[dict]:
    files = []
    for p in paths:
        p = pathlib.Path(p)
        if p.is_dir():
            files += sorted(list(p.glob("*.json")) + list(p.glob("*.jsonl")))      # a pack's samples/<kind>.jsonl: a sample a line
        elif p.is_file():
            files.append(p)
        else:
            raise CliError(f"{p}: no such file or folder")
    out = []
    for f in files:
        if f.name in ("catalog.json", "catalog.jsonl"):           # a pack's catalogue, never a sample
            continue
        try:
            if f.suffix == ".jsonl":
                lines = [x for x in f.read_text(encoding="utf-8").splitlines() if x.strip()]
                out += [{"name": f"{f.stem}-{n}.json", "document": json.loads(x)} for n, x in enumerate(lines, 1)]
            else:
                out.append({"name": f.name, "document": json.loads(f.read_text(encoding="utf-8"))})
        except ValueError as e:
            raise CliError(f"{f}: not JSON ({e})") from None
    if not out:
        raise CliError("no .json or .jsonl sample files found")
    return out


def emit(a, obj, text: str) -> None:
    say_json(obj) if a.json else print(text)


def cmd_design(a, extra) -> int:
    api, c = Api.from_args(a), a.design_cmd
    base = "/api/v1/builder/designs"
    if c == "list":
        res = api.json("GET", base)
        emit(a, res, "\n".join(f"{d['id']}  {d.get('name') or '(scratch)':<28}{d.get('kind') or '':<14}{d.get('status'):<10}rev {d.get('rev'):<4}"
                               f"{len(d.get('samples', []))} sample(s)" for d in res.get("designs", [])) or "no designs")
    elif c == "create":
        body: dict = {k: getattr(a, k) for k in ("name", "kind", "notes", "base") if getattr(a, k)}
        if a.from_sutra:
            text = pathlib.Path(a.from_sutra).read_text(encoding="utf-8")
            body["sutra"] = text
            kind = ((need_yaml().safe_load(text) or {}).get("match") or {}).get("kind")
            if kind and "kind" not in body:
                body["kind"] = kind
        d = api.json("POST", base, body)
        if a.samples:
            api.json("POST", f"{base}/{q(d['id'])}/samples", {"samples": read_json_files(a.samples)})
        if a.autodesign:
            api.json("POST", f"{base}/{q(d['id'])}/autodesign")
        if a.samples or a.autodesign:
            d = api.json("GET", f"{base}/{q(d['id'])}")
        emit(a, d, "created " + design_summary(d))
    elif c == "get":
        d = api.json("GET", f"{base}/{q(a.id)}")
        if a.out:
            pathlib.Path(a.out).write_text(d.get("yaml") or "", encoding="utf-8")
        if a.yaml:
            sys.stdout.write(d.get("yaml") or "")
        else:
            emit(a, d, design_summary(d) + (f"\n  Sutra written to {a.out}" if a.out else ""))
    elif c == "save":
        d = api.json("PATCH", f"{base}/{q(a.id)}", {"sutra": pathlib.Path(a.file).read_text(encoding="utf-8")})
        emit(a, d, f"saved {a.file} as revision {d.get('rev')} of {d.get('name') or d['id']}")
    elif c == "check":
        m = api.json("POST", f"{base}/{q(a.id)}/check")
        ok = bool(m.get("ok"))
        bad = [x for x in (m.get("samples") or []) if x.get("status") not in ("ok", None)]
        emit(a, m, f"check of rev {m.get('rev')}: {'all samples ok' if ok else 'PROBLEMS'}" + "".join(
            f"\n  {x.get('name')}: {x.get('status')} {x.get('message') or x.get('error') or ''}".rstrip() for x in bad[:20]))
        return 0 if ok else 1
    elif c == "propose":
        res = api.json("POST", f"{base}/{q(a.id)}/propose", {"note": a.note or ""})
        p = res.get("proposal")
        emit(a, res, f"proposed: proposal {p['id']} for {p['name']} v{p['version']} is {p['status']}; an approver decides it" if p
             else f"saved {res.get('name')} v{res.get('version')} (governance is off: no review needed)")
    elif c == "export":
        _, raw = api.request("GET", f"{base}/{q(a.id)}/export")
        out = pathlib.Path(a.out or f"{a.id}-fragment.zip")
        out.write_bytes(raw)
        emit(a, {"file": str(out), "bytes": len(raw)}, f"wrote {out} ({len(raw)} bytes)")
    elif c == "import":
        _, raw = api.request("POST", f"{base}/import", data=pathlib.Path(a.file).read_bytes(), ctype="application/zip")
        res = json.loads(raw or b"{}")
        emit(a, res, "imported: " + json.dumps(res)[:300])
    elif c == "bind":
        if not a.id:
            res = api.json("GET", f"{base}/binding")
            emit(a, res, f"binding {'on' if res.get('enabled') else 'off'}; development directory: {res.get('dir')}")
            return 0
        if a.sync:
            res = api.json("POST", f"{base}/{q(a.id)}/sync")
        elif a.save:
            res = api.json("POST", f"{base}/{q(a.id)}/save-file")
        elif a.unbind:
            res = api.json("DELETE", f"{base}/{q(a.id)}/bind")
        elif a.file:
            res = api.json("POST", f"{base}/{q(a.id)}/bind", {"file": a.file})
        else:
            raise CliError("design bind ID needs one of --file, --sync, --save, --unbind")
        emit(a, res, design_summary(res) + (f"\n  changed: {res.get('changed')}" if "changed" in res else ""))
    elif c == "autodesign":
        res = api.json("POST", f"{base}/{q(a.id)}/autodesign")
        emit(a, res, f"auto-designed: revision {res.get('rev')}")
    elif c == "delete":
        api.request("DELETE", f"{base}/{q(a.id)}")
        emit(a, {"deleted": a.id}, f"deleted {a.id}")
    elif c == "proposals":
        res = api.json("GET", "/api/v1/sutras/proposals" + (f"?status={q(a.status)}" if a.status else ""))
        emit(a, res, "\n".join(f"{p['id']}  {p['name']} v{p['version']}  {p['status']}  by {p.get('author')}" for p in res.get("proposals", []))
             or ("no proposals" if res.get("enabled") else "governance is off: nothing to review"))
    return 0


# ------------------------------------------------------------------------------------------------ docs

def cmd_docs_shots(a, extra) -> int:
    cmd = [sys.executable, str(TOOLS / "docs" / "screenshots.py")]
    for flag, val in (("--base", a.base), ("--guide", a.guide), ("--only", a.only)):
        if val:
            cmd += [flag, val]
    if a.list:
        cmd.append("--list")
    return subprocess.run(cmd, cwd=ROOT).returncode


# ------------------------------------------------------------------------------------------------ smoke, doctor, view, completion

def cmd_server_smoke(a, extra) -> int:
    smoke = load_module("smoke", "smoke.py")
    rep = smoke.run(Api.from_args(a), a.pack, a.ids, a.dates, a.kind)
    emit(a, rep, "") if a.json else smoke.render(rep)
    return 0 if rep["ok"] else 1


def cmd_doctor(a, extra) -> int:
    doctor = load_module("doctor", "doctor.py")
    api = Api.from_args(a) if a.server or os.environ.get("DRISHTI_SERVER") else None
    results = doctor.run(os.environ, ROOT, find_jar, find_java, api, a.java)
    say_json({"ok": doctor.exit_code(results) == 0, "checks": results}) if a.json else doctor.render(results)
    return doctor.exit_code(results)


def cmd_view(a, extra) -> int:
    vc = load_module("viewcmd", "viewcmd.py")
    try:
        explain = a.view_cmd == "explain"
        path = vc.path_for(a.ref, a.date, "/explain" if explain else "", getattr(a, "panel", None))
    except ValueError as e:
        raise CliError(str(e)) from None
    res = Api.from_args(a).json("GET", path)
    if a.json:
        say_json(res)
    else:
        (vc.render_explain if explain else vc.render_view)(res)
    return 0


def cmd_completion(a, extra) -> int:
    print(load_module("completion", "completion.py").generate(a.shell, build_parser()), end="")
    return 0


# ------------------------------------------------------------------------------------------------ the parser

def build_parser() -> argparse.ArgumentParser:
    """The whole command line: every group, command and option (``drishti.py --help``), built by ``drishti_parser.py``."""
    return drishti_parser.build_parser(sys.modules[__name__])


def use_tmp_dir() -> None:
    """``DRISHTI_TMP_DIR``, when set, is this command's temp folder too: Python's own temporary files and every program it starts
    (``TMPDIR``), so a site whose system temp folder is small or in memory points all of Drishti at one folder of its choosing."""
    base = (os.environ.get("DRISHTI_TMP_DIR") or "").strip()
    if not base:
        return
    import tempfile
    pathlib.Path(base).mkdir(parents=True, exist_ok=True)
    os.environ["TMPDIR"] = base
    tempfile.tempdir = base


def main(argv: list[str] | None = None) -> int:
    """Runs one command (``argv``, else the process's arguments); the exit code: 0 ok, 1 problems found, 2 usage."""
    try:
        ap = build_parser()
        a, extra = ap.parse_known_args(argv)
        a._argv = list(sys.argv[1:] if argv is None else argv)
        passthrough = (a.group == "sutra" and a.cmd != "gen") or (a.group == "data" and a.cmd == "load") or (a.group == "lake" and a.cmd == "maintain") or (a.group == "feed" and a.cmd == "run")
        if extra and not passthrough:
            ap.error("unrecognized arguments: " + " ".join(extra))
        if extra and extra[0] == "--":
            extra = extra[1:]
        return a.func(a, extra) or 0
    except CliError as e:
        print(f"drishti: {e}", file=sys.stderr)
        return e.code
    except SystemExit as e:
        if isinstance(e.code, str):
            print(e.code, file=sys.stderr)
            return 2
        return e.code or 0
    except ModuleNotFoundError as e:
        print(f"drishti: {e.name} is not installed; run  uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py ...", file=sys.stderr)
        return 2
    except FileNotFoundError as e:
        print(f"drishti: {e.filename or e}: no such file", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    use_tmp_dir()
    sys.exit(main())
