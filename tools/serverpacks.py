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
    datasource get                  the connectors a pack reads through and their state (change them with `drishti.py connector`)

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

def print_datasource(ds: dict, say=print) -> None:
    say(f"connectors of {ds['pack']}   [files in {ds['directory']}; change them with `drishti.py connector ...`]")
    rows = [[c["name"], str(c.get("state", "")).lower().replace("_", " "), c.get("plugin") or "", ",".join(c.get("kinds") or []),
             (c.get("origin") if c.get("defined") else "nothing defines it"), "; ".join(c.get("problems") or [])] for c in ds["connectors"]]
    _table(rows, ["connector", "state", "plugin", "kinds from this pack", "defined by", "problems"], say)
    for m in ds.get("missing") or []:
        say(f"  {m} is not configured: create it with `drishti.py connector apply {m}.yaml` or in Admin -> Connectors")


def cmd_ds_get(cli, a, extra) -> int:
    ds = cli.Api.from_args(a).json("GET", f"/api/v1/admin/packs/{q(a.name)}/datasource")
    cli.say_json(ds) if a.json else print_datasource(ds)
    return 1 if ds.get("missing") else 0


def register(cli, ps, srv) -> None:
    """`ps` is the subparsers of `server packs`; `srv` the server-connection options."""
    def wrap(fn):
        return lambda a, extra: fn(cli, a, extra)

    p = ps.add_parser("deploy", help="deploy a pack archive: verify, preview the changes, deploy", parents=[srv],
                      description="Uploads a pack archive (.tar.gz from `pack bundle`/`pack make`, or .zip), shows the server's checks (checksums, manifest, path safety, "
                                  "server version, Sutra lint and tests, dependencies, signature) and the difference from the running version, then deploys it: "
                                  "the previous version is kept, the new one is put to use at once (no restart), and the old files are put back if it cannot be used. The archive carries the pack only, never data.",
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

    d = ps.add_parser("datasource", help="a pack's connectors and their state (read-only; change them with `connector`)",
                      description="The connectors a pack reads through, each one's state, and which are not configured. Connectors are files in config/connectors "
                                  "and are created, tested and changed with `drishti.py connector ...` or in Admin -> Connectors. Exit 1 when one is not configured.")
    ds = d.add_subparsers(dest="ds_cmd", metavar="ACTION", required=True)
    g = ds.add_parser("get", help="the pack's connectors with their state", parents=[srv])
    g.add_argument("name", help="the pack's name")
    g.set_defaults(func=wrap(cmd_ds_get))
