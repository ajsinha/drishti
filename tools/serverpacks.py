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

"""`drishti.py server packs deploy|history|rollback|datasource`: Admin → Packs from the terminal.

    deploy ARCHIVE [--preview]      upload a pack archive (tools/packbundle.py's .tar.gz, or a .zip), see the server's verification and
                                    the difference from the running version (breaking changes marked), then deploy it
    history [--pack P]              deployments, rollbacks and reverted attempts, newest first, with the versions kept
    rollback PACK [--version V]     go back to a kept version (V may be 'shipped')
    datasource get|set|test|reset   a pack's data source: pack default, administrator override, site value; try it; change it; reset it

An administrator is needed; a personal API token also needs the packs:admin scope.
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import sys
import time
import urllib.parse


def q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


def _table(rows: list[list], headers: list[str], say=print) -> None:
    cells = [[str(c if c is not None else "") for c in r] for r in rows]
    w = [max([len(h)] + [len(r[i]) for r in cells]) for i, h in enumerate(headers)]
    say("  " + "  ".join(h.ljust(w[i]) for i, h in enumerate(headers)).rstrip())
    say("  " + "  ".join("-" * x for x in w))
    for r in cells:
        say("  " + "  ".join(r[i].ljust(w[i]) for i in range(len(headers))).rstrip())


def print_verification(rep: dict, say=print) -> None:
    """The server's checks, then the preview: counts per level and every finding, breaking ones first and marked."""
    say(f"archive {rep.get('file') or ''} ({rep.get('size')} bytes, sha256 {str(rep.get('sha256'))[:16]}...)")
    for c in rep.get("checks", []):
        mark = "FAIL" if not c["ok"] else ("warn" if c.get("warn") else "ok  ")
        say(f"  {mark}  {c['name']:<16}{c['detail']}")
    pv = rep.get("preview")
    if not pv:
        return
    run = pv.get("running")
    say(f"\nPreview: {pv['pack']} " + (f"{run['version']} ({run['where']}) -> {pv['newVersion']} ({pv.get('versionOrder')})" if run else f"{pv['newVersion']} is a new pack"))
    counts = pv.get("counts") or {}
    found = pv.get("findings") or []
    if not found:
        say("  no differences from the running version" if run else "  nothing to compare")
        return
    for lv in ("breaking", "selection", "layout", "change"):
        rows = [f for f in found if f["level"] == lv]
        if not rows:
            continue
        say(f"  {lv.upper()} ({len(rows)})" + ("   <- monitors, workspaces, alerts or saved links may stop working" if lv == "breaking" else ""))
        for f in rows:
            say(f"    {f['what']:<26} {f['name']}" + (f"   {f['detail']}" if f.get("detail") else ""))
    say("  summary: " + ", ".join(f"{n} {lv}" for lv, n in counts.items() if n))


def wait_for_server(api, seconds: float) -> bool:
    """After a restart in place: polls until the server answers again (it goes down for a moment first)."""
    end = time.time() + seconds
    time.sleep(min(2.0, seconds))
    while time.time() < end:
        try:
            api.request("GET", "/actuator/health")
            return True
        except Exception:                      # noqa: BLE001 - down or restarting: keep waiting
            time.sleep(1)
    return False


def cmd_deploy(cli, a, extra) -> int:
    path = pathlib.Path(a.archive)
    if not path.is_file():
        raise cli.CliError(f"{a.archive}: no such archive", 2)
    data = path.read_bytes()
    headers = {"X-Drishti-Filename": path.name}
    side = path.with_name(path.name + ".sha256")           # `pack bundle` writes it: the server then checks the file arrived intact
    want = a.sha256 or (side.read_text(encoding="utf-8").split()[0] if side.is_file() else None)
    if want:
        if hashlib.sha256(data).hexdigest() != want.lower():
            raise cli.CliError(f"{path.name}: sha256 does not match {'--sha256' if a.sha256 else side.name} (the file changed after it was bundled)", 1)
        headers["X-Drishti-Sha256"] = want
    if a.signature_file:
        headers["X-Drishti-Signature"] = pathlib.Path(a.signature_file).read_text(encoding="utf-8").strip()
        headers["X-Drishti-Publisher"] = a.publisher or ""
    api = cli.Api.from_args(a)
    rep = api.json("POST", "/api/v1/admin/packs/deploy", data=data, ctype="application/octet-stream", extra=headers)
    out = {"verification": rep}
    if not a.json:
        print_verification(rep)
    if not rep.get("ok"):
        if a.json:
            cli.say_json(out)
        else:
            print("\nrefused: nothing was changed")
        return 1
    uid = rep["uploadId"]
    breaking = ((rep.get("preview") or {}).get("counts") or {}).get("breaking", 0)
    if a.preview or (breaking and not a.accept_breaking):
        api.json("DELETE", f"/api/v1/admin/packs/deploy/{q(uid)}")           # a preview leaves nothing staged
        if a.json:
            out["deployed"] = False
            cli.say_json(out)
        elif a.preview:
            print("\npreview only: nothing was deployed")
        else:
            print(f"\nnot deployed: {breaking} breaking change(s); read them above, then run again with --accept-breaking")
        return 0 if a.preview else 1
    res = api.json("POST", f"/api/v1/admin/packs/deploy/{q(uid)}" + ("?acceptBreaking=true" if a.accept_breaking else ""))
    out["result"] = res
    if a.json:
        cli.say_json(out)
    else:
        print(f"\ndeployed {rep['pack']} {res.get('deployed')}" + (f" (replacing {res['previous']})" if res.get("previous") else " (new)") + f": {res.get('note', '')}")
    if res.get("restarting") and a.wait:
        up = wait_for_server(api, a.wait)
        if not a.json:
            print("the server is back" if up else f"the server did not answer within {a.wait:g}s; check it (the old files are restored if it could not start)")
        return 0 if up else 1
    return 0


def cmd_history(cli, a, extra) -> int:
    res = cli.Api.from_args(a).json("GET", "/api/v1/admin/packs/history?limit=" + str(a.limit) + (f"&pack={q(a.pack)}" if a.pack else ""))
    if a.json:
        cli.say_json(res)
        return 0
    rows = res.get("history", [])
    if rows:
        _table([[r.get("at", "")[:19].replace("T", " "), r.get("action"), r.get("pack"), r.get("version"), r.get("previous") or "", r.get("by")] for r in rows],
               ["when (UTC)", "action", "pack", "version", "replaced", "by"])
    else:
        print("  no deployments yet")
    for name, kept in (res.get("kept") or {}).items():
        if kept:
            print(f"  kept for rollback, {name}: " + ", ".join(k["version"] for k in kept))
    return 0


def cmd_rollback(cli, a, extra) -> int:
    res = cli.Api.from_args(a).json("POST", f"/api/v1/admin/packs/{q(a.name)}/rollback" + (f"?version={q(a.version)}" if a.version else ""))
    if a.json:
        cli.say_json(res)
    else:
        print(f"rolled {a.name} back to {res.get('restored')} (replacing {res.get('replaced')}): {res.get('note', '')}")
    return 0


# -- data source -------------------------------------------------------------------------------------------------

def parse_assignments(cli, items: list[str]) -> dict[str, str]:
    out = {}
    for it in items:
        k, sep, v = it.partition("=")
        if not sep or not k:
            raise cli.CliError(f"expected KEY=VALUE, got {it!r}", 2)
        out[k] = v
    return out


def current_override(ds: dict) -> dict:
    """The stored override as the PUT body wants it: connector -> {enabled?, settings{key: value}}."""
    out: dict = {}
    for c in ds.get("connectors", []):
        entry: dict = {}
        if c["enabled"].get("override") is not None:
            entry["enabled"] = c["enabled"]["override"] == "True" or c["enabled"]["override"] == "true"
        st = {s["key"]: s["override"] for s in c["settings"] if s.get("override") is not None}
        if st:
            entry["settings"] = st
        if entry:
            out[c["name"]] = entry
    return out


def print_datasource(ds: dict, say=print) -> None:
    say(f"data source of {ds['pack']}: " + ("an administrator override is in force" if ds.get("overridden") else "the pack's own settings (no override)")
        + f"   [override file {ds['file']}]")
    for c in ds["connectors"]:
        en = c["enabled"]
        say(f"\n  connector {c['name']}   plugin {c['plugin']}   kinds {', '.join(c['kinds'])}   {'on' if en['on'] else 'OFF'} ({en['source']})")
        rows = []
        for s in c["settings"]:
            shown = "(from the environment)" if s["secret"] and not s["effective"].startswith("(") else s["effective"]
            if s["secret"] and s["effective"].startswith("${"):
                shown = s["effective"]
            note = {"pack": "", "override": "overridden", "site": "site (environment or configuration)"}[s["source"]]
            if s["overridden"] and s["pack"] is not None:
                note += f"; pack default {s['pack']}"
            if not s["resolvable"]:
                note += "; variable NOT SET"
            rows.append([s["key"], shown, note.strip("; ")])
        _table(rows, ["setting", "in force", "where from"], say)


def cmd_ds_get(cli, a, extra) -> int:
    ds = cli.Api.from_args(a).json("GET", f"/api/v1/admin/packs/{q(a.name)}/datasource")
    cli.say_json(ds) if a.json else print_datasource(ds)
    return 0


def desired(cli, api, a) -> dict:
    ds = api.json("GET", f"/api/v1/admin/packs/{q(a.name)}/datasource")
    names = {c["name"] for c in ds["connectors"]}
    if a.connector not in names:
        raise cli.CliError(f"pack {a.name} has no connector {a.connector!r} (it has: {', '.join(sorted(names))})", 2)
    over = current_override(ds)
    entry = over.setdefault(a.connector, {})
    st = dict(entry.get("settings", {}))
    st.update(parse_assignments(cli, a.assign))
    for k in getattr(a, "unset", None) or []:
        st.pop(k, None)
    if st:
        entry["settings"] = st
    else:
        entry.pop("settings", None)
    if a.enabled is not None:
        entry["enabled"] = a.enabled == "true"
    if not entry:
        over.pop(a.connector)
    return over


def cmd_ds_set(cli, a, extra) -> int:
    api = cli.Api.from_args(a)
    over = desired(cli, api, a)
    if a.test_first:
        t = api.json("POST", f"/api/v1/admin/packs/{q(a.name)}/datasource/test", {"connector": a.connector, "connectors": over})
        if not t.get("ok"):
            print_test(t) if not a.json else cli.say_json(t)
            print("\nnot saved: the test failed")
            return 1
    res = api.json("PUT", f"/api/v1/admin/packs/{q(a.name)}/datasource", {"connectors": over})
    if a.json:
        cli.say_json(res)
    else:
        print(f"saved the override for {a.name}: changed {', '.join(res.get('changed') or []) or 'nothing'}; {res.get('note', '')}")
    return 0


def print_test(t: dict, say=print) -> None:
    say(f"tested {t['pack']}: {t['tested']}")
    for c in t["connectors"]:
        if c.get("skipped"):
            say(f"\n  {c['connector']}: {c['error']}")
            continue
        say(f"\n  {c['connector']} ({c['plugin']}): " + (f"reachable, health {c.get('health')}, {c.get('ms')} ms" if c["ok"] else f"PROBLEM: {c.get('error')}"))
        rows = []
        for k in c.get("kinds", []):
            if k["dates"]:
                for d in k["dates"]:
                    rows.append([k["kind"], d["date"] or "(undated)", ("" if k["exact"] else ">= ") + str(d["rows"]), k.get("note") or ""])
            else:
                rows.append([k["kind"], "", "0", k.get("note") or ""])
        if rows:
            _table(rows, ["kind", "business date", "rows", "note"], say)


def cmd_ds_test(cli, a, extra) -> int:
    api = cli.Api.from_args(a)
    body: dict = {}
    if a.connector:
        body["connector"] = a.connector
    if a.assign:
        if not a.connector:
            raise cli.CliError("--set needs --connector (which connector the settings are for)", 2)
        ds = api.json("GET", f"/api/v1/admin/packs/{q(a.name)}/datasource")
        over = current_override(ds)
        entry = over.setdefault(a.connector, {})
        entry["settings"] = {**entry.get("settings", {}), **parse_assignments(cli, a.assign)}
        body["connectors"] = over
    t = api.json("POST", f"/api/v1/admin/packs/{q(a.name)}/datasource/test", body or None)
    cli.say_json(t) if a.json else print_test(t)
    return 0 if t.get("ok") else 1


def cmd_ds_reset(cli, a, extra) -> int:
    res = cli.Api.from_args(a).json("DELETE", f"/api/v1/admin/packs/{q(a.name)}/datasource" + (f"?connector={q(a.connector)}" if a.connector else ""))
    cli.say_json(res) if a.json else print(f"reset {a.name}{' ' + a.connector if a.connector else ''}: the pack's own settings apply again; {res.get('note', '')}")
    return 0


def register(cli, ps, srv) -> None:
    """`ps` is the subparsers of `server packs`; `srv` the server-connection options."""
    def wrap(fn):
        return lambda a, extra: fn(cli, a, extra)

    p = ps.add_parser("deploy", help="deploy a pack archive: verify, preview the changes, deploy", parents=[srv],
                      description="Uploads a pack archive (.tar.gz from `pack bundle`/`pack make`, or .zip), shows the server's checks (checksums, manifest, path safety, "
                                  "server version, Sutra lint and tests, dependencies, signature) and the difference from the running version, then deploys it: "
                                  "the previous version is kept, the server restarts in place, and puts the old files back if it cannot start. The archive carries the pack only, never data.",
                      epilog="examples:\n  drishti.py server packs deploy dist/my-bank-1.1.0.tar.gz --preview\n  drishti.py server packs deploy dist/my-bank-1.1.0.tar.gz --accept-breaking --wait 60\n"
                             "Exit 0 deployed (or previewed); 1 refused, breaking changes not accepted, or the server not back; 2 usage.",
                      formatter_class=__import__("argparse").RawDescriptionHelpFormatter)
    p.add_argument("archive", help="the archive file (a .sha256 file next to it is sent too, so the server checks it arrived intact)")
    p.add_argument("--preview", action="store_true", help="verify and show the changes, then discard the upload: nothing is deployed")
    p.add_argument("--accept-breaking", action="store_true", help="deploy even if kinds or mnemonics are removed or renamed (otherwise exit 1 after the preview)")
    p.add_argument("--sha256", help="the expected sha256 of the archive (default: the .sha256 file beside it)")
    p.add_argument("--signature-file", help="a file with the base64 Ed25519 signature of the archive (tools/packreg sign)")
    p.add_argument("--publisher", help="the publisher the signature belongs to (a key in drishti.packs.registry.trusted-keys)")
    p.add_argument("--wait", type=float, default=0, help="after the restart, wait up to this many seconds for the server to answer again (default: do not wait)")
    p.set_defaults(func=wrap(cmd_deploy))

    p = ps.add_parser("history", help="deployments and rollbacks, newest first, and the versions kept", parents=[srv])
    p.add_argument("--pack", help="only this pack")
    p.add_argument("--limit", type=int, default=50, help="rows (default 50)")
    p.set_defaults(func=wrap(cmd_history))

    p = ps.add_parser("rollback", help="go back to a kept version of a pack", parents=[srv])
    p.add_argument("name", help="the pack's name")
    p.add_argument("--version", help="which kept version (default: the newest kept; 'shipped' for the copy that ships with the server)")
    p.set_defaults(func=wrap(cmd_rollback))

    d = ps.add_parser("datasource", help="a pack's data source: get, set, test, reset",
                      description="Where a pack's data comes from. The pack's own settings can be overridden by an administrator override file "
                                  "(data/packs/settings/<pack>.yaml, survives redeploys) and by the site's environment; precedence: environment > override file > pack. "
                                  "Credentials are never stored: a secret setting must be an environment reference such as ${LAKE_PASSWORD}.")
    ds = d.add_subparsers(dest="ds_cmd", metavar="ACTION", required=True)
    g = ds.add_parser("get", help="each connector's settings: in force, pack default, override, site", parents=[srv])
    g.add_argument("name", help="the pack's name")
    g.set_defaults(func=wrap(cmd_ds_get))
    s = ds.add_parser("set", help="override settings of one connector (saved, then the server restarts in place)", parents=[srv],
                      epilog="example:\n  drishti.py server packs datasource set my-bank bank-store root=/mnt/lake password='${LAKE_PW}' --test-first",
                      formatter_class=__import__("argparse").RawDescriptionHelpFormatter)
    s.add_argument("name", help="the pack's name")
    s.add_argument("connector", help="the connector's name (see `datasource get`)")
    s.add_argument("assign", nargs="*", metavar="KEY=VALUE", help="settings to override")
    s.add_argument("--unset", action="append", metavar="KEY", help="drop one override (that setting returns to the pack's value)")
    s.add_argument("--enabled", choices=["true", "false"], help="switch the connector on or off")
    s.add_argument("--test-first", action="store_true", help="try the settings against the real source first; save only if it works")
    s.set_defaults(func=wrap(cmd_ds_set))
    t = ds.add_parser("test", help="try the settings in force (or --set ones): dates and row counts per kind; exit 1 on a problem", parents=[srv])
    t.add_argument("name", help="the pack's name")
    t.add_argument("--connector", help="only this connector")
    t.add_argument("--set", dest="assign", action="append", default=[], metavar="KEY=VALUE", help="try these settings instead (needs --connector); nothing is saved")
    t.set_defaults(func=wrap(cmd_ds_test))
    r = ds.add_parser("reset", help="remove the override (all, or one connector's): the pack's own settings apply again", parents=[srv])
    r.add_argument("name", help="the pack's name")
    r.add_argument("--connector", help="only this connector's override")
    r.set_defaults(func=wrap(cmd_ds_reset))
