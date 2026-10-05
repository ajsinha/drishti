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

Groups: sutra (the Java `sutra` tool, plus `sutra gen`), pack (make, new, check, about-check, bundle, verify, deploy, rollback, publish, keygen, install),
data (ingest your own JSON Lines, load the demo data), server (health, packs), design (the Screen Designer's designs over
REST: create, save, check, propose, approve, export, import, bind) and docs (screenshots).
Needs Python 3.10+ and PyYAML; deltalake and pyarrow only for `--store delta` ingests. The guide with every command,
recipes and real output is docs/guides/CLI_GUIDE.md.

Exit codes: 0 ok, 1 problems found (a failing check, a server that said no, an unreachable server), 2 usage (a bad option,
a missing file or jar). Commands that talk to a server read it from --server or DRISHTI_SERVER (default
http://localhost:18480) and the token from DRISHTI_TOKEN or --token-file; the token is never printed.
"""
from __future__ import annotations

import argparse
import glob
import importlib.util
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

TOOLS = pathlib.Path(__file__).resolve().parent
ROOT = TOOLS.parent
DEFAULT_SERVER = "http://localhost:18480"
LOADERS = ("delta", "files", "postgres", "duckdb", "iceberg", "mongodb", "redis", "aerospike")


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
    if explicit:
        return explicit
    for home in (os.environ.get("JAVA_HOME"), "/usr/lib/jvm/java-21-openjdk-amd64"):
        if home and os.path.exists(os.path.join(home, "bin", "java")):
            return os.path.join(home, "bin", "java")
    return shutil.which("java") or "java"


def run_java(a, args: list[str], capture: bool = False) -> subprocess.CompletedProcess:
    """`java -jar <exec jar> sutra <args>` from the repository root (paths in `args` must already be absolute)."""
    cmd = [find_java(getattr(a, "java", None)), "-jar", find_jar(getattr(a, "jar", None)), "sutra", *args]
    try:
        return subprocess.run(cmd, cwd=ROOT, text=True, capture_output=capture)
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
    HINTS = {401: " (no or bad token: set DRISHTI_TOKEN or --token-file)",
             403: " (your role or a server setting does not allow it: designing is open to everyone, approving needs an approver, Admin pages an administrator; a personal drk_ token also needs the scope for the call: design:write, design:approve or packs:admin, see CLI_GUIDE)"}

    def __init__(self, status: int, code: str | None, detail: str):
        super().__init__(f"the server said {status}{' ' + code if code else ''}: {detail}{self.HINTS.get(status, '')}", 1)
        self.status = status


class Api:
    """A tiny REST client: Bearer token from DRISHTI_TOKEN / --token-file (never printed), JSON in and out."""

    def __init__(self, base: str, token: str | None = None, user: str | None = None, timeout: float = 60):
        self.base, self.token, self.user, self.timeout = base.rstrip("/"), token, user, timeout

    @classmethod
    def from_args(cls, a) -> "Api":
        token = None
        if getattr(a, "token_file", None):
            try:
                token = pathlib.Path(a.token_file).read_text(encoding="utf-8").strip()
            except OSError as e:
                raise CliError(f"cannot read --token-file {a.token_file}: {e.strerror}") from None
        token = token or os.environ.get("DRISHTI_TOKEN") or None
        return cls(getattr(a, "server", None) or os.environ.get("DRISHTI_SERVER") or DEFAULT_SERVER, token,
                   getattr(a, "user", None) or os.environ.get("DRISHTI_USER"), getattr(a, "timeout", 60))

    def request(self, method: str, path: str, body=None, data: bytes | None = None, ctype: str | None = None) -> tuple[int, bytes]:
        headers = {"Accept": "application/json, application/zip;q=0.9"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        elif self.user:
            headers["X-Drishti-User"] = self.user
        if body is not None:
            data, ctype = json.dumps(body).encode("utf-8"), "application/json"
        if ctype:
            headers["Content-Type"] = ctype
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:      # noqa: S310 - the URL is the operator's own --server
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            raw = e.read()
            code = detail = None
            try:
                j = json.loads(raw)
                code, detail = j.get("code"), j.get("detail") or j.get("message") or j.get("title")
            except ValueError:
                pass
            raise ApiError(e.code, code, detail or raw[:200].decode("utf-8", "replace") or str(e.reason)) from None
        except urllib.error.URLError as e:
            raise CliError(f"cannot reach {self.base}: {e.reason}; is the server running? (--server / DRISHTI_SERVER)", 1) from None
        except OSError as e:
            raise CliError(f"cannot reach {self.base}: {e}", 1) from None

    def json(self, method: str, path: str, body=None):
        _, raw = self.request(method, path, body)
        return json.loads(raw) if raw.strip() else {}


def q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


# ------------------------------------------------------------------------------------------------ sutra

def cmd_sutra(a, extra: list[str]) -> int:
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
    out = (a.out or ROOT / "packs" / a.name).resolve()
    if out.parent != (ROOT / "packs").resolve():
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
        if not isinstance(v, dict) or not isinstance(v.get("key"), str) or not isinstance(v.get("date"), str):
            problems.append(f"pack.yaml ingest.{k}: needs key: <field> and date: <field>")
    return problems


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
        entry["packYaml"] = check_ingest_block(pack)
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
    record("schema", PB.check_schema(op.pack) + check_ingest_block(op.pack))
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
        print("next: DRISHTI_PACKS must name it (restart) or Admin > Packs > Load; Sutra edits of a loaded pack hot-reload")
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
        for src in h.get("sources", []):
            print(f"  {src['name']:<28}{src['status']:<10}{src.get('health', '')}")
    return 0 if h.get("status") == "OK" else 1


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
            files += sorted(p.glob("*.json"))
        elif p.is_file():
            files.append(p)
        else:
            raise CliError(f"{p}: no such file or folder")
    out = []
    for f in files:
        try:
            out.append({"name": f.name, "document": json.loads(f.read_text(encoding="utf-8"))})
        except ValueError as e:
            raise CliError(f"{f}: not JSON ({e})") from None
    if not out:
        raise CliError("no .json sample files found")
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
    else:                                                           # approve | reject
        res = api.json("POST", f"/api/v1/sutras/proposals/{q(a.proposal)}/{c}", {"comment": a.comment} if a.comment else None)
        emit(a, res, f"{c}: proposal {res.get('id')}: {res.get('name')} v{res.get('version')} is {res.get('status')}")
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


# ------------------------------------------------------------------------------------------------ the parser

def server_parent(with_json: bool = True) -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(add_help=False)
    g = p.add_argument_group("server connection")
    g.add_argument("--server", help=f"the server's base URL (default: DRISHTI_SERVER, then {DEFAULT_SERVER})")
    g.add_argument("--token-file", help="a file holding the API token (default: the DRISHTI_TOKEN environment variable); never printed")
    g.add_argument("--user", help="the user name to send when the server has sign-in off (default: DRISHTI_USER)")
    g.add_argument("--timeout", type=float, default=60, help="seconds to wait for the server (default 60)")
    if with_json:
        p.add_argument("--json", action="store_true", help="machine-readable output")
    return p


def json_parent() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(add_help=False)
    p.add_argument("--json", action="store_true", help="machine-readable output")
    return p


def jvm_parent() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(add_help=False)
    g = p.add_argument_group("the Java tool")
    g.add_argument("--jar", help="the drishti-server exec jar (default: DRISHTI_JAR, then the newest drishti-server/target/drishti-server-*-exec.jar)")
    g.add_argument("--java", help="the java binary (default: JAVA_HOME, then JDK 21, then java on the PATH)")
    return p


SUTRA_EXAMPLES = ("examples:\n  drishti.py sutra lint packs/market-risk --strict\n  drishti.py sutra test packs/market-risk --junit build/market-risk.xml\n"
                  "  drishti.py sutra shape samples/ --out build\n  drishti.py sutra design samples/ --kind ticket\n"
                  "Unknown options are passed to the Java tool. Exit codes as the Java tool: 0 ok, 1 problems, 2 usage.")


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="drishti.py", description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    groups = ap.add_subparsers(dest="group", metavar="GROUP", required=True)
    srv, jvm, js = server_parent(), jvm_parent(), json_parent()
    PFJ, IJ, SG = load_module("pack_from_jsonl", "packgen/pack_from_jsonl.py"), load_module("ingest_jsonl", "ingest_jsonl.py"), sutragen()

    def group(name, help_):
        g = groups.add_parser(name, help=help_, description=help_, formatter_class=argparse.RawDescriptionHelpFormatter)
        return g.add_subparsers(dest="cmd", metavar="COMMAND", required=True)

    def add(sub, name, func, help_, parents=(), epilog=None):
        p = sub.add_parser(name, help=help_, description=help_, parents=list(parents), epilog=epilog, formatter_class=argparse.RawDescriptionHelpFormatter)
        p.set_defaults(func=func)
        return p

    # sutra ------------------------------------------------------------------------------------------------
    s = group("sutra", "The Sutra tool: shape, design, lint, test, preview (Java) and gen (one Sutra per group of JSON Lines)")
    for name, what, paths in (("shape", "infer the shape (JSON Schema) of JSON samples", "samples.json or a folder"),
                              ("design", "draft a Sutra from samples (--each: one per subfolder)", "samples.json, a folder, or with --each a folder of folders"),
                              ("lint", "parse and check every Sutra of a pack or file (--strict: help warnings DRS-2045..2047 fail)", "a pack folder or .sutra.yaml"),
                              ("test", "render each Sutra against its tests/ samples and check expect.yaml", "a pack folder or .sutra.yaml"),
                              ("preview", "render samples; --out writes HTML snapshots", "a pack folder or .sutra.yaml")):
        p = add(s, name, cmd_sutra, f"sutra {name}: {what}", [jvm], SUTRA_EXAMPLES)
        p.set_defaults(sutra_cmd=name)
        p.add_argument("paths", nargs="+", metavar="PATH", help=paths)
        p.add_argument("--out", help="write results (and for design, the Sutra) here")
        p.add_argument("--junit", help="write a JUnit XML report to this file")
        p.add_argument("--strict", action="store_true", help="lint: help warnings (DRS-2045 to 2047) fail the run")
        p.add_argument("--samples", help="JSON samples to use instead of the pack's tests/ folder or the Sutra's sibling .json")
        p.add_argument("--kind", help="design: the kind to name the Sutra for")
        p.add_argument("--each", action="store_true", help="design: one Sutra per subfolder of .json samples")
    g = add(s, "gen", cmd_sutra_gen, "sutra gen: one Sutra per group of JSON Lines documents, with samples (tools/sutragen.py)", [],
            "example:\n  drishti.py sutra gen data/jsonl --key trade=tradeId --match trade=productType --out build/sutras")
    g.add_argument("inputs", type=pathlib.Path, nargs="+", help="folders of *.jsonl and/or single .jsonl files")
    SG.add_arguments(g)
    g.add_argument("--out", type=pathlib.Path, required=True, help="writes <out>/<kind>/<kind>-<slug>.v1.sutra.yaml and <out>/tests/<sutra>/sample-*.json")
    g.add_argument("--tests-dir", type=pathlib.Path, help="put the samples here instead of <out>/tests")
    g.add_argument("--no-lint", action="store_true", help="do not run `sutra lint` on the result")

    # pack -------------------------------------------------------------------------------------------------
    k = group("pack", "Packs: generate from JSON Lines, check in CI, find missing help text, publish and install")
    pn = add(k, "new", cmd_pack_new, "pack new: create a complete pack from JSON Lines (kinds, Sutras, samples, tests, about text, a lake or files store)",
             [PFJ.build_parser(add_help=False), server_parent(with_json=False)],
             "examples:\n  drishti.py pack new data/jsonl --name my-bank --key trade=tradeId --date trade=businessDate --lake data/delta\n"
             "  drishti.py pack new data/jsonl --name my-bank --date businessDate --store files --files-root data/files "
             "--load --server http://localhost:18977")
    pm = add(k, "make", cmd_pack_make, "pack make: ONE folder to deploy from JSON Lines: pack, Delta data, bundle, server config, run scripts and README.txt (tools/packmake.py)", [],
             "example:\n  drishti.py pack make data/jsonl --kind trade --match productType --name my-bank --out build/my-bank\n"
             "Chooses --key and --date itself when you do not (and says why), then runs pack check and pack verify.")
    load_module("packmake", "packmake.py").add_arguments(pm)
    pn.add_argument("--load", action="store_true", help="afterwards ask the running server to load the pack (administrator; the server restarts in place)")
    pc = add(k, "check", cmd_pack_check, "pack check: sutra lint + sutra test (with help coverage) on packs, for CI", [jvm, js],
             "examples:\n  drishti.py pack check packs/my-bank --strict --junit build/reports\n  drishti.py pack check packs/a packs/b --json\n"
             "exit: 0 all passed, 1 any failure, 2 usage")
    pc.add_argument("packs", nargs="+", metavar="PACK", help="pack folder(s)")
    pc.add_argument("--strict", action="store_true", help="help warnings (DRS-2045 to 2047: a shown field without glossary text) fail the run")
    pc.add_argument("--junit", metavar="DIR", help="write DIR/<pack>-lint.xml and DIR/<pack>-test.xml")
    pc.add_argument("--tail", type=int, default=8, help="lines of each step's output to show (default 8; 0 none)")
    pa = add(k, "about-check", cmd_pack_about_check, "pack about-check: fields the Sutras show that config/about.yaml does not explain (DRS-2047), and TODO placeholders", [jvm, js],
             "Runs `sutra lint` and lists its help warnings per Sutra. Exit 1 when any field is unexplained (with --strict, TODO placeholders count too).")
    pa.add_argument("packs", nargs="+", metavar="PACK")
    pa.add_argument("--strict", action="store_true", help="placeholder 'TODO' texts in config/about.yaml count as gaps too")
    pp = add(k, "publish", cmd_pack_publish, "pack publish: sign a pack and add it to a registry folder (tools/packreg)", [js],
             "example:\n  drishti.py pack publish packs/my-bank --registry /srv/registry --key keys/me.pem --publisher me")
    pp.add_argument("pack")
    pp.add_argument("--registry", required=True)
    pp.add_argument("--key", required=True, help="the private key (make one with `pack keygen`)")
    pp.add_argument("--publisher", required=True)
    pg = add(k, "keygen", cmd_pack_keygen, "pack keygen: make a signing key (needs openssl)")
    pg.add_argument("--out", required=True)
    pb = add(k, "bundle", cmd_pack_bundle, "pack bundle: make a versioned, checksummed <name>-<version>.tar.gz (+ .sha256, MANIFEST) to copy to servers", [jvm],
             "Runs `pack check` first (--no-check to skip). The archive is reproducible: the same content gives the same bytes.\n"
             "example:\n  drishti.py pack bundle packs/my-bank --out dist\nGuide: docs/guides/OPERATIONALISING.md")
    pb.add_argument("pack")
    pb.add_argument("--out", metavar="DIR", help="where to write the bundle (default ./dist)")
    pb.add_argument("--requires-server", metavar="VERSION", help="the minimum server version recorded in the manifest (default: this checkout's version)")
    pb.add_argument("--no-check", action="store_true", help="do not run lint/test before bundling")
    pb.add_argument("--json", action="store_true", help="print the result as JSON")
    vparent = argparse.ArgumentParser(add_help=False)
    vparent.add_argument("--no-sutra", action="store_true", help="skip `sutra lint` and `sutra test` (no Java needed: checksums, schema and server version only)")
    vparent.add_argument("--strict", action="store_true", help="lint: help warnings fail the run")
    vparent.add_argument("--server-version", metavar="V", help="the target server's version (default: this checkout's); a bundle that needs a newer one fails")
    vparent.add_argument("--json", action="store_true", help="print the report as JSON")
    pv = add(k, "verify", cmd_pack_verify, "pack verify: check a bundle or pack folder offline (checksums, schema, sutra lint/test, server version); or --registry for a registry's signatures",
             [jvm, vparent], "examples:\n  drishti.py pack verify dist/my-bank-1.2.0.tar.gz\n  drishti.py pack verify packs/my-bank --no-sutra\n"
             "  drishti.py pack verify --registry /srv/registry --publisher me --public-key keys/me.pub")
    pv.add_argument("source", nargs="?", metavar="BUNDLE|FOLDER")
    pv.add_argument("--registry")
    pv.add_argument("--publisher")
    pv.add_argument("--public-key")
    pd = add(k, "deploy", cmd_pack_deploy, "pack deploy: verify, then copy a bundle or folder into a packs folder atomically, keeping the previous version (no server API)",
             [jvm, vparent], "example:\n  drishti.py pack deploy dist/my-bank-1.2.0.tar.gz --to /opt/drishti/packs --backup /opt/drishti/backups\n"
             "It never touches a running server: load it with DRISHTI_PACKS + restart or Admin > Packs > Load.")
    pd.add_argument("source", metavar="BUNDLE|FOLDER")
    pd.add_argument("--to", required=True, metavar="DIR", help="the server's packs folder (DRISHTI_PACKS_DIR) or drishti.packs.installed-dir")
    pd.add_argument("--backup", metavar="DIR", help="keep the replaced version here (default DIR/.previous under --to)")
    pd.add_argument("--dry-run", action="store_true", help="verify and show what would change; copy nothing")
    pr_ = add(k, "rollback", cmd_pack_rollback, "pack rollback: put back the newest (or --version) backup of a pack; the version it replaces is kept too",
              epilog="example:\n  drishti.py pack rollback my-bank --to /opt/drishti/packs --backup /opt/drishti/backups")
    pr_.add_argument("name")
    pr_.add_argument("--to", required=True, metavar="DIR")
    pr_.add_argument("--backup", metavar="DIR")
    pr_.add_argument("--version", help="restore this version rather than the newest backup")
    pr_.add_argument("--dry-run", action="store_true")
    pr_.add_argument("--json", action="store_true")
    pi = add(k, "install", cmd_pack_install, "pack install: install a registry pack on the running server (administrator), or --list the registry", [srv],
             "examples:\n  drishti.py pack install --list\n  drishti.py pack install my-bank 1.2.0")
    pi.add_argument("name", nargs="?")
    pi.add_argument("version", nargs="?")
    pi.add_argument("--list", action="store_true", help="show the registry's packs")

    # data -------------------------------------------------------------------------------------------------
    d = group("data", "Data: ingest your own JSON Lines into a lake or files store; load the demo data into any store")
    add(d, "ingest", cmd_data_ingest, "data ingest: write JSON Lines documents into a Delta lake or the File connector's layout (tools/ingest_jsonl.py)",
        [IJ.build_parser(add_help=False)], "examples:\n  drishti.py data ingest --from data/new --pack packs/my-bank --lake data/delta\n"
        "  drishti.py data ingest --from day1.jsonl --from day2.jsonl --pack packs/my-bank --store files --root data/files --dry-run")
    dl = add(d, "load", cmd_data_load, "data load: load the demo data (1,791 sample documents x 10 days, optionally a trade book) into a store (tools/load-<store>.sh)",
             epilog="examples:\n  drishti.py data load --store delta\n  drishti.py data load --store files --trades 10000 --days 3\n"
                    "  drishti.py data load --store postgres jdbc:postgresql://localhost:5433/drishti --trades 10000 --user u --password p\n"
                    "Other options are passed to the script unchanged. Keep local runs small (10,000 trades); see docs/connectors/DEMO_DATA.md.")
    dl.add_argument("--store", required=True, choices=LOADERS)
    dl.add_argument("target", nargs="*", help="the store's root, URL or database (the script's default when omitted; mongodb takes a URI and a database)")
    dl.add_argument("--trades", type=int, help="also generate a trade book of this many trades a day")
    dl.add_argument("--days", type=int, help="business days the book covers (script default 3)")
    dl.add_argument("--dry-run", action="store_true", help="print the command instead of running it")

    # server -----------------------------------------------------------------------------------------------
    sv = group("server", "A running server over REST: health and pack management (administrator)")
    add(sv, "health", cmd_server_health, "server health: GET /api/v1/admin/health (exit 1 unless the status is OK)", [srv])
    sp = sv.add_parser("packs", help="server packs list|load|unload|on|off", description="Pack management; load, unload, on and off need an administrator.")
    ps = sp.add_subparsers(dest="packs_cmd", metavar="ACTION", required=True)
    for act, what in (("list", "all packs: loaded, on, added from Admin, kinds"), ("load", "load a pack that is on disk (the server restarts in place)"),
                      ("unload", "unload a pack an administrator loaded"), ("on", "turn a loaded pack on for users"), ("off", "turn a loaded pack off for users")):
        p = ps.add_parser(act, help=what, description=what, parents=[srv])
        p.set_defaults(func=cmd_server_packs)
        if act != "list":
            p.add_argument("name", help="the pack's name")

    # design -----------------------------------------------------------------------------------------------
    ds = group("design", "Screen Designer designs over REST: create, edit, check, propose, approve, ship (anyone may design; approving needs an approver)")
    for name, what in (("list", "your designs"), ("create", "a new design, from samples and/or a Sutra file"), ("get", "show a design (or its Sutra with --yaml / -o)"),
                       ("save", "replace the design's Sutra with a .sutra.yaml file (a new revision)"), ("check", "run the Sutra against every sample (exit 1 on problems)"),
                       ("propose", "send the design for review (or save it, when governance is off)"), ("approve", "approve a proposal (approver role)"),
                       ("reject", "reject a proposal (approver role)"), ("proposals", "list proposals"), ("export", "download the design as a pack-fragment zip"),
                       ("import", "import a pack-fragment zip as designs"), ("bind", "bind to, sync with or save to a file in your development directory"),
                       ("autodesign", "draft a Sutra from the samples"), ("delete", "delete a design")):
        p = add(ds, name, cmd_design, f"design {name}: {what}", [srv])
        p.set_defaults(design_cmd=name)
        if name in ("get", "save", "check", "propose", "export", "autodesign", "delete"):
            p.add_argument("id", help="the design's id (from `design list`)")
        if name == "bind":
            p.add_argument("id", nargs="?", help="the design's id (omit to show whether binding is on)")
            m = p.add_mutually_exclusive_group()
            m.add_argument("--file", help="bind to this file in your development directory (an existing file's text becomes the Sutra)")
            m.add_argument("--sync", action="store_true", help="read the bound file (an IDE edit becomes a step of the design)")
            m.add_argument("--save", action="store_true", help="write the Sutra to the bound file")
            m.add_argument("--unbind", action="store_true")
        if name == "create":
            p.add_argument("--name")
            p.add_argument("--kind")
            p.add_argument("--notes")
            p.add_argument("--base", help="start from a live Sutra: name@version")
            p.add_argument("--from-sutra", metavar="FILE", help="a .sutra.yaml to start from (the kind is read from it)")
            p.add_argument("--samples", nargs="+", metavar="PATH", help="JSON sample files or folders")
            p.add_argument("--autodesign", action="store_true", help="draft a Sutra from the samples afterwards")
        if name == "get":
            p.add_argument("--yaml", action="store_true", help="print only the Sutra text")
            p.add_argument("-o", "--out", help="write the Sutra text to this file")
        if name == "save":
            p.add_argument("file", help="the .sutra.yaml to store")
        if name == "propose":
            p.add_argument("--note", help="what changed, for the reviewer")
        if name == "export":
            p.add_argument("-o", "--out", help="the zip to write (default <id>-fragment.zip)")
        if name == "import":
            p.add_argument("file", help="a pack-fragment zip")
        if name == "proposals":
            p.add_argument("--status", help="pending, approved, rejected or withdrawn")
        if name in ("approve", "reject"):
            p.add_argument("proposal", help="the proposal id (from `design proposals` or `design propose`)")
            p.add_argument("--comment", help="the reason (a rejection needs one)")

    # docs -------------------------------------------------------------------------------------------------
    dc = group("docs", "Documentation tooling")
    ss = add(dc, "shots", cmd_docs_shots, "docs shots: regenerate the guides' screenshots (Playwright, scratch ports; tools/docs/screenshots.py)")
    ss.add_argument("--base", help="the console URL (default the scratch console)")
    ss.add_argument("--guide", help="one guide only")
    ss.add_argument("--only", help="comma list of picture-name fragments")
    ss.add_argument("--list", action="store_true", help="list the pictures")
    return ap


def main(argv=None) -> int:
    try:
        ap = build_parser()
        a, extra = ap.parse_known_args(argv)
        passthrough = (a.group == "sutra" and a.cmd != "gen") or (a.group == "data" and a.cmd == "load")
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
    sys.exit(main())
