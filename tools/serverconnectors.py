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

"""`drishti.py connector list|get|apply|delete|test|plugins|enable|disable|reset|history`: Admin -> Connectors from the terminal.

    list                    every connector: origin (file, pack, application), plugin, kinds, state, who uses it
    get NAME [--yaml]       one connector: the effective settings (credentials masked), its file text, version and history
    apply FILE [--name N]   create or change a connector from a YAML file (the name is the file's name without .yaml); the version is
                            read first and sent back as If-Match, so a concurrent edit is refused instead of overwritten (GitOps-safe)
    delete NAME             remove a connector's file (a pack's connector is reset or disabled instead)
    test NAME | --file F    start a throwaway instance on the saved (or the file's) settings and try it; exit 1 when it does not work
    plugins [NAME]          the plugins and the settings each reads (type, required, default, secret)
    enable | disable NAME   switch a connector on or off, keeping the rest of its file
    reset NAME              put the pack's suggested default back
    history NAME            the kept earlier versions

An administrator is needed; a personal API token also needs the packs:admin scope. Output is text, or JSON with --json. Exit: 0 done,
1 refused by the server or the test failed, 2 usage.
"""
from __future__ import annotations

import pathlib
import re
import urllib.parse

NAME = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")


def q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


def _table(rows: list[list], headers: list[str], say=print) -> None:
    cells = [[str(c if c is not None else "") for c in r] for r in rows]
    w = [max([len(h)] + [len(r[i]) for r in cells]) for i, h in enumerate(headers)]
    say("  " + "  ".join(h.ljust(w[i]) for i, h in enumerate(headers)).rstrip())
    say("  " + "  ".join("-" * x for x in w))
    for r in cells:
        say("  " + "  ".join(r[i].ljust(w[i]) for i in range(len(headers))).rstrip())


def _name(cli, name: str) -> str:
    if not NAME.fullmatch(name):
        raise cli.CliError(f"{name!r} is not a connector name: lower case letters, digits and hyphens, starting with a letter or digit", 2)
    return name


def _query(confirm: bool) -> str:
    return "?confirm=true" if confirm else ""


def cmd_list(cli, a, extra) -> int:
    res = cli.Api.from_args(a).json("GET", "/api/v1/admin/connectors")
    rows = [c for c in res["connectors"] if (not a.origin or c["origin"] == a.origin) and (not a.status or c["state"] == a.status.upper())]
    if a.json:
        cli.say_json({**res, "connectors": rows})
        return 0
    print(f"{len(rows)} connector(s) in {res['directory']}   (watching: {res['watch']})")
    _table([[c["name"], c["origin"], c.get("plugin") or "", ",".join(c.get("kinds") or []), c["state"].lower(),
             ",".join(u["pack"] for u in c.get("usedBy") or []), "; ".join(c.get("problems") or [])] for c in rows],
           ["name", "origin", "plugin", "kinds", "state", "used by", "problems"])
    for n in res.get("misnamed") or []:
        print(f"  not a connector file (ignored): {n}")
    for n in res.get("deprecated") or []:
        print(f"  deprecated: {n} is defined in the server's own configuration; apply a file to replace it")
    return 0


def cmd_get(cli, a, extra) -> int:
    c = cli.Api.from_args(a).json("GET", f"/api/v1/admin/connectors/{q(_name(cli, a.name))}")
    if a.yaml:
        if not c.get("text"):
            raise cli.CliError(f"{a.name} has no file (it is defined by {c['origin']}); `connector apply` writes one", 1)
        print(c["text"], end="")
        return 0
    if a.json:
        cli.say_json(c)
        return 0
    print(f"connector {c['name']}   origin {c['origin']}   plugin {c.get('plugin')}   {c['state'].lower()}   version {c.get('etag') or '-'}")
    if c.get("file"):
        print(f"  file {c['file']}")
    if c.get("kinds"):
        print(f"  kinds {', '.join(c['kinds'])}")
    for u in c.get("usedBy") or []:
        print(f"  used by pack {u['pack']}" + (f" ({', '.join(u['kinds'])})" if u["kinds"] else ""))
    for p in c.get("problems") or []:
        print(f"  PROBLEM: {p}")
    print("  settings (credentials masked):")
    _table([[k, v] for k, v in (c.get("settings") or {}).items()], ["setting", "value"])
    if c.get("history"):
        print(f"  {len(c['history'])} earlier version(s) kept: `connector history {c['name']}`")
    return 0


def cmd_apply(cli, a, extra) -> int:
    path = pathlib.Path(a.file)
    if not path.is_file():
        raise cli.CliError(f"{a.file}: no such file", 2)
    name = _name(cli, a.name or re.sub(r"\.ya?ml$", "", path.name))
    text = path.read_text(encoding="utf-8")
    api = cli.Api.from_args(a)
    etag = a.if_match
    if etag is None:
        try:
            etag = api.json("GET", f"/api/v1/admin/connectors/{q(name)}").get("etag")
        except cli.ApiError as e:
            if e.status != 404:
                raise
    if a.test_first:
        t = api.json("POST", f"/api/v1/admin/connectors/{q(name)}/test", {"text": text})
        if not t.get("ok"):
            cli.say_json(t) if a.json else print_test(t)
            if not a.json:
                print("\nnot applied: the test failed")
            return 1
    res = api.json("PUT", f"/api/v1/admin/connectors/{q(name)}" + _query(a.confirm), {"text": text}, extra={"If-Match": etag} if etag else None)
    if a.json:
        cli.say_json(res)
        return 0
    print(f"applied {name}: {res.get('state', '').lower() or 'saved'}; changed {', '.join(res.get('changed') or []) or 'nothing'}; version {res.get('etag')}")
    for w in res.get("warnings") or []:
        print(f"  note {w['field']}: {w['message']}")
    for p in res.get("problems") or []:
        print(f"  PROBLEM: {p}")
    return 0


def cmd_delete(cli, a, extra) -> int:
    api = cli.Api.from_args(a)
    name = _name(cli, a.name)
    etag = a.if_match or api.json("GET", f"/api/v1/admin/connectors/{q(name)}").get("etag")
    res = api.json("DELETE", f"/api/v1/admin/connectors/{q(name)}" + _query(a.confirm), extra={"If-Match": etag} if etag else None)
    cli.say_json(res) if a.json else print(f"deleted {name} (its text is kept in the connector folder's .history)")
    return 0


def print_test(t: dict, say=print) -> None:
    say(f"{t['connector']} ({t.get('plugin')}): " + (f"reachable, health {t.get('health')}, {t.get('ms')} ms" if t["ok"] else f"PROBLEM: {t.get('error')}"))
    if t.get("hint"):
        say(f"  hint: {t['hint']}")
    for w in t.get("warnings") or []:
        say(f"  note: {w}")
    rows = []
    for k in t.get("kinds", []):
        for d in k["dates"] or [{"date": None, "rows": 0, "none": True}]:
            when = "" if d.get("none") else (d["date"] or "(undated)")
            rows.append([k["kind"], when, ("" if k["exact"] else ">= ") + str(d["rows"]), k.get("note") or ""])
    if rows and t["ok"]:
        _table(rows, ["kind", "business date", "rows", "note"], say)


def cmd_test(cli, a, extra) -> int:
    api = cli.Api.from_args(a)
    if a.file:
        path = pathlib.Path(a.file)
        if not path.is_file():
            raise cli.CliError(f"{a.file}: no such file", 2)
        name = _name(cli, a.name or re.sub(r"\.ya?ml$", "", path.name))
        body = {"text": path.read_text(encoding="utf-8")}
    elif a.name:
        name, body = _name(cli, a.name), None
    else:
        raise cli.CliError("name a connector, or give --file", 2)
    t = api.json("POST", f"/api/v1/admin/connectors/{q(name)}/test", body)
    cli.say_json(t) if a.json else print_test(t)
    return 0 if t.get("ok") else 1


def cmd_plugins(cli, a, extra) -> int:
    res = cli.Api.from_args(a).json("GET", "/api/v1/admin/connectors/plugins")["plugins"]
    if a.json:
        cli.say_json([p for p in res if not a.name or p["name"] == a.name])
        return 0
    if not a.name:
        _table([[p["name"], "yes" if p["tls"] else "", len(p["settings"])] for p in res], ["plugin", "tls", "settings"])
        return 0
    p = next((x for x in res if x["name"] == a.name), None)
    if p is None:
        raise cli.CliError(f"no plugin {a.name!r} (installed: {', '.join(x['name'] for x in res)})", 1)
    print(f"plugin {p['name']}" + ("   (supports the tls.* settings)" if p["tls"] else ""))
    _table([[s["name"], s["type"], "required" if s["required"] else "", s["default"] or "", "secret: ${ENV} or file:" if s["secret"] else "", s["description"]] for s in p["settings"]],
           ["setting", "type", "", "default", "", "what it is"])
    return 0


def cmd_enabled(cli, a, extra) -> int:
    api = cli.Api.from_args(a)
    name = _name(cli, a.name)
    on = a.cmd == "enable"
    res = api.json("POST", f"/api/v1/admin/connectors/{q(name)}/enabled" + _query(a.confirm), {"enabled": on})
    cli.say_json(res) if a.json else print(f"{name} is {'on' if on else 'off'} ({str(res.get('state', '')).lower()})")
    return 0


def cmd_reset(cli, a, extra) -> int:
    res = cli.Api.from_args(a).json("POST", f"/api/v1/admin/connectors/{q(_name(cli, a.name))}/reset")
    cli.say_json(res) if a.json else print(f"{a.name} is back to the pack's default ({str(res.get('state', '')).lower()})")
    return 0


def cmd_history(cli, a, extra) -> int:
    c = cli.Api.from_args(a).json("GET", f"/api/v1/admin/connectors/{q(_name(cli, a.name))}")
    if a.json:
        cli.say_json(c.get("history") or [])
        return 0
    _table([[h["id"], h["at"], h["bytes"]] for h in c.get("history") or []], ["version", "kept at", "bytes"])
    return 0


def register(cli, sub, add, srv) -> None:
    """`sub` is the subparsers of the `connector` group; `add` the parser factory of drishti.py; `srv` the server-connection options."""
    def wrap(fn):
        return lambda a, extra: fn(cli, a, extra)

    p = add(sub, "list", wrap(cmd_list), "connector list: every connector, where it is defined and how it is doing", [srv],
            "example:\n  drishti.py connector list --status failed --json")
    p.add_argument("--origin", choices=["file", "pack", "application"], help="only connectors defined there")
    p.add_argument("--status", help="only connectors in this state (running, disabled, failed, idle, not_loaded)")

    p = add(sub, "get", wrap(cmd_get), "connector get: the effective settings (credentials masked), the file, the version and who uses it", [srv],
            "examples:\n  drishti.py connector get trading-lake\n  drishti.py connector get trading-lake --yaml > trading-lake.yaml")
    p.add_argument("name")
    p.add_argument("--yaml", action="store_true", help="print only the file's text")

    p = add(sub, "apply", wrap(cmd_apply), "connector apply: create or change a connector from a YAML file (the file name is the connector name)", [srv],
            "examples:\n  drishti.py connector apply config/connectors/trading-lake.yaml --test-first\n  drishti.py connector apply lake.yaml --name trading-lake --if-match 3fa9c1d20b7e4a55\n"
            "A credential must be a ${ENV_VAR} or file: reference; the server refuses a literal one. Exit 1 when refused (also: changed since --if-match).")
    p.add_argument("file", help="the connector file")
    p.add_argument("--name", help="the connector's name (default: the file name without .yaml)")
    p.add_argument("--if-match", help="the version you expect (the etag from `connector get`); default: read it now")
    p.add_argument("--test-first", action="store_true", help="try the settings on the real source first; apply only if it works")
    p.add_argument("--confirm", action="store_true", help="go ahead when the change switches off a connector that packs use")

    p = add(sub, "delete", wrap(cmd_delete), "connector delete: remove a connector's file (its text is kept in .history)", [srv])
    p.add_argument("name")
    p.add_argument("--if-match", help="the version you expect")
    p.add_argument("--confirm", action="store_true", help="go ahead although packs use this connector")

    p = add(sub, "test", wrap(cmd_test), "connector test: try a connector (or a file) on the real source; exit 1 when it fails", [srv],
            "examples:\n  drishti.py connector test trading-lake\n  drishti.py connector test new-lake --file new-lake.yaml --json")
    p.add_argument("name", nargs="?", help="the connector (with --file: the name to test the file as; default the file's name)")
    p.add_argument("--file", help="test this file's settings instead of the saved connector")

    p = add(sub, "plugins", wrap(cmd_plugins), "connector plugins: the plugins and the settings each reads", [srv], "example:\n  drishti.py connector plugins jdbc")
    p.add_argument("name", nargs="?", help="show this plugin's settings")

    for act, what in (("enable", "switch a connector on"), ("disable", "switch a connector off (asks for --confirm when packs use it)")):
        p = add(sub, act, wrap(cmd_enabled), f"connector {act}: {what}", [srv])
        p.add_argument("name")
        p.add_argument("--confirm", action="store_true", help="go ahead although packs use this connector")
        p.set_defaults(cmd=act)
    p = add(sub, "reset", wrap(cmd_reset), "connector reset: put the pack's suggested default back as the connector's file", [srv])
    p.add_argument("name")
    p = add(sub, "history", wrap(cmd_history), "connector history: the kept earlier versions of a connector's file", [srv])
    p.add_argument("name")

