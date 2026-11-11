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
"""`drishti.py module deploy|plan|apply|list|remove|disable|enable|upgrade|reject|withdraw|status|run`: module deployment from the
terminal, the same server API as Admin -> Modules -> Deploy (docs/architecture/KERNEL_AND_MODULES.md section 7.6).

    deploy FILE --reason R          uploads the .dmod to the module store (checked once: the fifteen conformance checks) and plans its
                                    install (or upgrade, when another version is active); prints the plan per node; --out writes it
    upgrade FILE --reason R         the same, for a newer version of an active module (rolling, node by node)
    plan ACTION MODULE --reason R   install | upgrade | remove | disable | enable a module already in the store (--version V)
    remove | disable | enable MODULE --reason R    short forms of plan
    apply ID|plan.json              a second administrator applies the plan (four eyes): stage on every placed node, then activate
    reject | withdraw ID            close a waiting plan (another administrator rejects, the proposer withdraws)
    status ID                       one deployment: per node, what happened
    list                            the modules (state, version, nodes), the store and the plans waiting
    run FILE                        the dev loop: the kernel's thin jar with this module only, on a free port, data under --dir

An administrator is needed (a personal token also needs the modules:deploy scope). Exit: 0 done, 1 refused or failed (rolled back),
2 usage or a stale plan (DRS-1031: plan again), 3 waiting for a second administrator (DRS-2007).
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import urllib.parse
import zipfile

ACTIONS = ("install", "upgrade", "remove", "disable", "enable")


def q(s: str) -> str:
    return urllib.parse.quote(str(s), safe="")


def _dmod(cli, path: str) -> pathlib.Path:
    p = pathlib.Path(path)
    if not p.is_file() or p.suffix != ".dmod":
        raise cli.CliError(f"{path}: not a .dmod file (pack a module folder first: drishti.py module pack FOLDER)", 2)
    return p


def _reason(cli, a) -> str:
    r = (getattr(a, "reason", None) or "").strip()
    if not r:
        raise cli.CliError("say why with --reason (it is kept in the audit log)", 2)
    return r


def print_plan(d: dict, say=print) -> None:
    """A deployment as the Admin page shows it: the header, one line per node, the warnings and the outcome."""
    say(f"{d.get('id')}  {d.get('action')} {d.get('module')} {d.get('version')}"
        + (f" (from {d['previous']})" if d.get("previous") else "") + f"  [{d.get('state')}]  against cluster {d.get('fingerprint')}")
    say(f"  proposed by {d.get('proposedBy')}: {d.get('reason')}")
    nodes = d.get("nodes") or []
    w = max([4] + [len(str(n.get("node"))) for n in nodes])
    for n in nodes:
        mark = "placed" if n.get("placed") else "not placed"
        say(f"  {str(n.get('node')).ljust(w)}  {mark:<10}  {str(n.get('now') or '-'):>7} -> {str(n.get('after') or '-'):<8}  {n.get('action')}"
            + (f"  [{n.get('status')}{': ' + str(n['detail'])[:160] if n.get('detail') else ''}]" if n.get("status") not in (None, "planned") else ""))
    for x in d.get("warnings") or []:
        say(f"  warning: {x}")
    if d.get("outcome"):
        say(f"  outcome: {d['outcome']}")
    if d.get("state") == "proposed":
        say(f"  next: a second administrator runs  drishti.py module apply {d.get('id')}")


def _show(cli, a, d: dict) -> None:
    if getattr(a, "out", None):
        pathlib.Path(a.out).write_text(json.dumps(d, indent=2), encoding="utf-8")
    if a.json:
        cli.say_json(d)
    else:
        print_plan(d)
        if getattr(a, "out", None):
            print(f"  plan written to {a.out}")


def upload(cli, api, path: pathlib.Path) -> dict:
    _, raw = api.request("POST", "/api/v1/admin/modules/store", data=path.read_bytes(), ctype="application/octet-stream")
    return json.loads(raw)


def cmd_deploy(cli, a, extra) -> int:
    path = _dmod(cli, a.file)
    reason = _reason(cli, a)
    api = cli.Api.from_args(a)
    s = upload(cli, api, path)
    if not a.json:
        print(f"stored {s['id']} {s['version']} (sha256 {s['sha'][:12]}..., {s['bytes']:,} bytes): all {len(s.get('checks') or [])} checks passed")
    d = api.json("POST", "/api/v1/admin/modules/deployments/plans",
                  {"action": a.action_name, "module": s["id"], "version": s["version"], "reason": reason})
    _show(cli, a, d)
    return 0


def cmd_plan(cli, a, extra) -> int:
    action = getattr(a, "action_name", None) or a.action
    if action not in ACTIONS:
        raise cli.CliError(f"the action is one of {', '.join(ACTIONS)}", 2)
    d = cli.Api.from_args(a).json("POST", "/api/v1/admin/modules/deployments/plans",
                                  {"action": action, "module": a.module, "version": getattr(a, "version", None) or "", "reason": _reason(cli, a)})
    _show(cli, a, d)
    return 0


def _plan_id(cli, ref: str) -> str:
    p = pathlib.Path(ref)
    if ref.endswith(".json") and p.is_file():
        try:
            return str(json.loads(p.read_text(encoding="utf-8"))["id"])
        except (ValueError, KeyError) as e:
            raise cli.CliError(f"{ref}: not a plan file ({e})", 2) from None
    return ref


def cmd_apply(cli, a, extra) -> int:
    pid = _plan_id(cli, a.plan)
    api = cli.Api.from_args(a)
    if not a.json:
        print(f"applying {pid}: stage on every placed node, then activate (an upgrade: one node at a time) ...")
    try:
        d = api.json("POST", f"/api/v1/admin/modules/deployments/{q(pid)}/apply", {"comment": a.comment or ""})
    except cli.ApiError as e:
        if e.drs == "DRS-1031":
            print(f"{e} ; plan again: drishti.py module plan ...", file=sys.stderr)
            return 2
        if e.drs == "DRS-2007":
            print(f"waiting for a second administrator: {e}", file=sys.stderr)
            return 3
        raise
    _show(cli, a, d)
    return 0 if d.get("state") == "applied" else 1


def cmd_close(cli, a, extra) -> int:
    path = f"/api/v1/admin/modules/deployments/{q(_plan_id(cli, a.plan))}/{a.close}"
    d = cli.Api.from_args(a).json("POST", path, {"comment": getattr(a, "comment", None) or ""} if a.close == "reject" else None)
    _show(cli, a, d)
    return 0


def cmd_status(cli, a, extra) -> int:
    d = cli.Api.from_args(a).json("GET", f"/api/v1/admin/modules/deployments/{q(_plan_id(cli, a.plan))}")
    _show(cli, a, d)
    return 0 if d.get("state") in ("applied", "proposed") else 1


def cmd_list(cli, a, extra) -> int:
    res = cli.Api.from_args(a).json("GET", "/api/v1/admin/modules/deployments")
    if a.json:
        cli.say_json(res)
        return 0
    mods = res.get("modules") or []
    print(f"{len(mods)} module(s) · this node {res.get('node')} · cluster {res.get('fingerprint')}")
    rows = []
    for m in mods:
        stored = ", ".join(v["version"] for v in m.get("versions") or [])
        rows.append([m["id"], m.get("version") or "-", m.get("state"), m.get("origin") or "-", ", ".join(m.get("placed") or []) or "-", stored or "-",
                     m.get("title") or ""])
    heads = ["MODULE", "VERSION", "STATE", "ORIGIN", "PLACED ON", "IN THE STORE", "TITLE"]
    w = [max([len(h)] + [len(str(r[i])) for r in rows]) for i, h in enumerate(heads)]
    print("  " + "  ".join(h.ljust(w[i]) for i, h in enumerate(heads)).rstrip())
    for r in rows:
        print("  " + "  ".join(str(r[i]).ljust(w[i]) for i in range(len(heads))).rstrip())
    for d in res.get("waiting") or []:
        print(f"waiting for a second administrator: {d['id']} {d['action']} {d['module']} {d['version']} (proposed by {d['proposedBy']}: {d['reason']})")
    return 0


# ---- module run: the dev loop ---------------------------------------------------------------------------------------------------------

def free_port() -> int:
    with socket.socket() as s:
        s.bind(("", 0))
        return s.getsockname()[1]


def manifest_of(path: pathlib.Path) -> dict:
    """The id, version and part names of a .dmod (module.yaml read without a YAML library: the top-level keys it needs)."""
    with zipfile.ZipFile(path) as z:
        text = z.read("module.yaml").decode("utf-8")
    out: dict = {"parts": []}
    in_parts = False
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith(" "):
            in_parts = line.startswith("parts:")
            key, _, val = line.partition(":")
            if key in ("id", "version"):
                out[key] = val.strip().strip('"').strip("'")
            if key == "parts" and val.strip().startswith("{"):
                out["parts"] = [p.split(":")[0].strip(" {") for p in val.strip()[1:-1].split("},") if ":" in p]
                in_parts = False
        elif in_parts and line.startswith("  ") and not line.startswith("    ") and ":" in line:
            out["parts"].append(line.split(":")[0].strip())
    if not out.get("id") or not out.get("version"):
        raise ValueError("module.yaml has no id or version")
    return out


def prepare_run(path: pathlib.Path, run_dir: pathlib.Path) -> dict:
    """Lays out a one-module kernel under ``run_dir``: the module installed and active, placed on this node; the data folder empty."""
    m = manifest_of(path)
    mid, ver = m["id"], m["version"]
    mods = run_dir / "modules"
    inst = mods / "installed" / mid
    if inst.exists():
        shutil.rmtree(inst)
    (inst / ver).mkdir(parents=True)
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            target = (inst / ver / n).resolve()
            if not str(target).startswith(str((inst / ver).resolve())):
                raise ValueError(f"{n}: a path outside the module")
        z.extractall(inst / ver)
    shutil.copy(path, inst / f"{mid}-{ver}.dmod")
    (mods / "installed" / "active.yaml").write_text(f"modules:\n  {mid}: \"{ver}\"\n", encoding="utf-8")
    parts = m["parts"] or ["api"]
    (run_dir / "placement.yaml").write_text("# module run: this module, every part on this node\nmodules:\n  " + mid + ":\n    parts:\n"
                                            + "".join(f"      {p}: {{ nodes: [local], allow-all: true }}\n" for p in parts), encoding="utf-8")
    (run_dir / "data").mkdir(exist_ok=True)
    return m


def find_kernel_jar(cli, explicit: str | None) -> str:
    jar = explicit or os.environ.get("DRISHTI_KERNEL_JAR")
    if not jar:
        found = sorted(cli.ROOT.glob("packaging/drishti-kernel-app/target/drishti-kernel-*-exec.jar"))
        jar = str(found[-1]) if found else ""
    if not jar or not os.path.isfile(jar):
        raise cli.CliError("the kernel's thin jar is not built: ./mvnw -q package -DskipTests -pl :drishti-kernel-app -am (or pass --jar)", 2)
    return jar


def cmd_run(cli, a, extra) -> int:
    path = _dmod(cli, a.file)
    keep = a.dir is not None
    run_dir = pathlib.Path(a.dir) if keep else pathlib.Path(tempfile.mkdtemp(prefix="drishti-module-run-", dir=os.environ.get("TMPDIR")))
    try:
        m = prepare_run(path, run_dir)
        port = a.port or free_port()
        jar = find_kernel_jar(cli, a.jar)
        java = cli.find_java(getattr(a, "java", None))
        (run_dir / "tmp").mkdir(exist_ok=True)
        args = cli.java_cmd(java, ["-jar", jar, f"--server.port={port}", f"--drishti.data.dir={run_dir / 'data'}",
                f"--drishti.module-deploy.dir={run_dir / 'modules'}", f"--drishti.placement.file={run_dir / 'placement.yaml'}", *extra], tmp=run_dir / "tmp")
        print(f"module run: {m['id']} {m['version']} alone on the kernel at http://localhost:{port}  (data under {run_dir}; Ctrl-C stops"
              + (" and removes it)" if not keep else ")"))
        print(f"  its API: http://localhost:{port}/api/v1/{m['id']}/...   the kernel's view of it: /api/v1/admin/modules/deployments")
        if a.dry_run:
            print("  " + " ".join(args))
            return 0
        try:
            return subprocess.run(args, cwd=run_dir).returncode
        except KeyboardInterrupt:
            return 0
    finally:
        if not keep:
            shutil.rmtree(run_dir, ignore_errors=True)


def register(cli, sub, add, srv, js) -> None:
    """``sub`` is the ``module`` group's subparsers; ``add`` drishti.py's parser factory; ``srv`` and ``js`` its server and JSON options."""
    def wrap(fn):
        return lambda a, extra: fn(cli, a, extra)

    for name, action, what in (("deploy", "install", "upload a .dmod to the module store (checked once) and plan its install or upgrade"),
                               ("upgrade", "upgrade", "upload a newer .dmod and plan a rolling upgrade, node by node")):
        p = add(sub, name, wrap(cmd_deploy), f"module {name}: {what}", [srv],
                f"examples:\n  drishti.py module {name} dist/greeter-1.1.0.dmod --reason \"new greeting\" --out plan.json\n"
                "  drishti.py module apply plan.json            # a second administrator\nexit: 0 planned, 1 refused, 2 usage")
        p.set_defaults(action_name=action)
        p.add_argument("file", help="the .dmod")
        p.add_argument("--reason", help="why (kept in the audit log)")
        p.add_argument("--out", help="write the plan (JSON) to this file")
    p = add(sub, "plan", wrap(cmd_plan), "module plan: plan an action on a module already in the store (changes nothing)", [srv],
            "examples:\n  drishti.py module plan install greeter --version 1.0.0 --reason \"first install\"\n"
            "  drishti.py module plan remove greeter --reason \"no longer used\" --out remove.json")
    p.add_argument("action", choices=ACTIONS)
    p.add_argument("module")
    p.add_argument("--version", help="the version (default: the newest in the store; remove and disable take the active one)")
    p.add_argument("--reason", help="why (kept in the audit log)")
    p.add_argument("--out", help="write the plan (JSON) to this file")
    for name in ("remove", "disable", "enable"):
        p = add(sub, name, wrap(cmd_plan), f"module {name}: plan to {name} a module on every node (a second administrator applies)", [srv],
                f"example:\n  drishti.py module {name} greeter --reason \"...\"")
        p.set_defaults(action_name=name)
        p.add_argument("module")
        p.add_argument("--reason", help="why (kept in the audit log)")
        p.add_argument("--out", help="write the plan (JSON) to this file")
    p = add(sub, "apply", wrap(cmd_apply), "module apply: apply a plan (another administrator than its proposer)", [srv],
            "examples:\n  drishti.py module apply dpl-3f9a12c0\n  drishti.py module apply plan.json --comment ok\n"
            "exit: 0 applied, 1 failed (rolled back on every node), 2 stale plan (DRS-1031), 3 waiting for a second administrator")
    p.add_argument("plan", help="the deployment id, or a plan file written with --out")
    p.add_argument("--comment", help="a comment kept with the decision")
    for name in ("reject", "withdraw"):
        p = add(sub, name, wrap(cmd_close), f"module {name}: close a waiting plan", [srv])
        p.set_defaults(close=name)
        p.add_argument("plan", help="the deployment id, or a plan file")
        if name == "reject":
            p.add_argument("--comment", help="why")
    p = add(sub, "status", wrap(cmd_status), "module status: one deployment, per node", [srv])
    p.add_argument("plan", help="the deployment id, or a plan file")
    add(sub, "list", wrap(cmd_list), "module list: the modules, their state, version and nodes; the store; the plans waiting", [srv],
        "example:\n  drishti.py module list --json")
    p = add(sub, "run", wrap(cmd_run), "module run: the kernel with this module only, on a free port (the developer's loop)", [],
            "examples:\n  drishti.py module run dist/greeter-0.1.0.dmod\n  drishti.py module run dist/greeter-0.1.0.dmod --port 18600 --dir target/run\n"
            "Unknown options are passed to the kernel (e.g. --drishti.ui.dev=true).")
    p.add_argument("file", help="the .dmod")
    p.add_argument("--port", type=int, help="the port (default: a free one, never 18480)")
    p.add_argument("--dir", help="keep the run's data here (default: a temporary folder removed on exit)")
    p.add_argument("--jar", help="the kernel's thin jar (default: DRISHTI_KERNEL_JAR, then packaging/drishti-kernel-app/target/drishti-kernel-*-exec.jar)")
    p.add_argument("--java", help="the java binary (default: JAVA_HOME, then JDK 21, then java on the PATH)")
    p.add_argument("--dry-run", action="store_true", help="lay out the run folder and print the command, without starting")
