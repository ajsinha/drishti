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

"""`drishti.py live`: the live feeds from the command line, as Admin -> Data bindings (the Live column) and Admin -> Health (Live feeds)
show them (docs/architecture/LIVE_CONNECTORS_DESIGN.md sections 7.9 and 9.1; the API: /api/v1/admin/live and /api/v1/admin/bindings,
administrators only).

    list [--json]                                  every live feed: state, messages a minute, rows, rejects, kinds, last reconcile
    show KIND                                      the kind's feeds and its contract's rules in words
    on KIND [--connector C ...] --reason R         propose the kind's live feeds on (all its companions, or those named); four eyes
    off KIND [--connector C ...] --reason R        propose them off (all, or those named); four eyes
    pause CONNECTOR | resume CONNECTOR             an administrator's pause: a queue's messages wait at the broker, a topic's are skipped
    apply-now KIND                                 land the kind's waiting windows at once (a landed kind: apply land or both)
    rejects KIND                                   the newest rejects of the kind's feeds, with their reasons

Exit codes: 0 done, 2 refused or usage (no such kind or feed, no reason given)."""
from __future__ import annotations

import urllib.parse

EPILOG = ("examples:\n  drishti.py live list\n  drishti.py live show trade\n"
          "  drishti.py live on trade --connector trade-wss-live --reason \"WSS go-live\"\n"
          "  drishti.py live pause trade-endur-live\n  drishti.py live apply-now scenario-pv\n  drishti.py live rejects collateral-balance")


def _q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


def _rejected(f: dict) -> int:
    if f.get("rejected") is not None:
        return int(f["rejected"])
    return sum(int(v or 0) for k, v in (f.get("counts") or {}).items() if str(k).startswith("rejected"))


def table(feeds: list) -> list[str]:
    """The list as Health's grid shows it: CONNECTOR STATE /MIN ROWS REJECTED KINDS, then the last reconcile's line."""
    out = [f"{'CONNECTOR':<26}{'STATE':<16}{'/MIN':>7}{'ROWS':>10}{'REJECTED':>10}  KINDS"]
    for f in feeds:
        state = f.get("state", "") + (" *" if f.get("attention") not in (None, "ok") else "")
        out.append(f"{f.get('connector', ''):<26}{state:<16}{f.get('perMinute') if f.get('perMinute') is not None else '-':>7}"
                   f"{f.get('rows') if f.get('rows') is not None else '-':>10}{_rejected(f) if f.get('counts') else '-':>10}  "
                   f"{', '.join(f.get('kinds') or []) or '-'}")
        if f.get("why"):
            out.append(f"{'':<26}{f['why']}")
        if f.get("reconcileLine"):
            out.append(f"{'':<26}last reconcile: {f['reconcileLine']}")
    return out


def live_list(binding: dict | None) -> list[str]:
    """The live connectors a binding names, from its ``live`` (a name, a list, or {connectors, apply})."""
    v = (binding or {}).get("live")
    if isinstance(v, dict):
        v = v.get("connectors")
    return [str(x) for x in (v if isinstance(v, list) else [v] if v else [])]


def proposal(row: dict, feeds: list, on: bool, named: list[str]) -> dict:
    """The kind's new binding: what serves it now (the binding, else what it resolved to), with its live list switched."""
    b = dict(row.get("binding") or {})
    if not b.get("read") and row.get("read") and not b.get("demo"):
        b["read"] = list(row["read"])
    current = live_list(b) or [f["connector"] for f in feeds if f.get("state") not in ("OFF", "NOT CONFIGURED")]
    pick = named or [f["connector"] for f in feeds]
    both = isinstance(b.get("live"), dict) and str(b["live"].get("apply", "")).lower() == "both"
    new = list(dict.fromkeys(current + pick)) if on else [c for c in current if c not in pick]
    b.pop("live", None)
    if new:
        b["live"] = {"connectors": new, "apply": "both"} if both else new
    return b


def register(groups, srv, ctx: dict) -> None:
    """Adds ``live list|show|on|off|pause|resume|apply-now|rejects``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]

    def feeds_of(api, kind: str) -> list:
        return api.json("GET", "/api/v1/admin/live?kind=" + _q(kind)).get("feeds") or []

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.live_cmd
        if c == "list":
            res = api.json("GET", "/api/v1/admin/live")
            if a.json:
                say_json(res)
                return 0
            print("\n".join(table(res.get("feeds") or [])))
            return 0
        if c in ("pause", "resume"):
            res = api.json("POST", f"/api/v1/admin/live/{_q(a.target)}/{c}", {})
            if a.json:
                say_json(res)
                return 0
            print(f"{a.target}: {res.get('state', '')}" + (" (messages wait at the broker)" if c == "pause" and not (res.get("paused") or {}).get("skips")
                                                           else " (messages are skipped and read again at the next start)" if c == "pause" else ""))
            return 0
        feeds = feeds_of(api, a.target)
        if c == "show":
            detail = api.json("GET", f"/api/v1/admin/live/{_q(feeds[0]['connector'])}") if feeds else {}
            if a.json:
                say_json({"feeds": feeds, "rules": (detail.get("rules") or {}).get(a.target)})
                return 0
            if not feeds:
                print(f"{a.target}: no live feed (no kafka, activemq or rabbitmq connector serves it)")
                return 0
            print(f"{a.target} · live")
            print("\n".join(table(feeds)))
            print("the kind's rules (its contract):")
            for k, v in ((detail.get("rules") or {}).get(a.target) or {}).items():
                print(f"  {k:<18}{v}")
            return 0
        if c in ("on", "off"):
            if not (a.reason or "").strip():
                raise CliError("give --reason: a binding change is proposed with it and a second administrator approves it", 2)
            if not feeds:
                raise CliError(f"{a.target}: no live connector serves it (a companion: companion-of: <its data connector>)", 2)
            unknown = [x for x in a.connector or [] if x not in {f['connector'] for f in feeds}]
            if unknown:
                raise CliError(f"{', '.join(unknown)}: not a live feed of {a.target}; its feeds: {', '.join(f['connector'] for f in feeds)}", 2)
            st = api.json("GET", "/api/v1/admin/bindings")
            row = next((k for k in st.get("kinds") or [] if k.get("kind") == a.target), {"kind": a.target})
            b = proposal(row, feeds, c == "on", a.connector or [])
            res = api.json("POST", "/api/v1/admin/bindings/proposals", {"kind": a.target, "binding": b, "reason": a.reason})
            if a.json:
                say_json(res)
                return 0
            print(f"proposed {res.get('id', '')}: {a.target} live {live_list(b) or 'off'}; a second administrator approves it "
                  f"(drishti.py approvals list, or Admin -> Data bindings)")
            return 0
        if c == "apply-now":
            landed = [f for f in feeds if f.get("landing")]
            if not landed:
                raise CliError(f"{a.target}: no feed lands it (its contract's apply is overlay, and no binding asks for both)", 2)
            out = {f["connector"]: api.json("POST", f"/api/v1/admin/live/{_q(f['connector'])}/apply-now", {}) for f in landed}
            if a.json:
                say_json(out)
                return 0
            for name, w in out.items():
                print(f"{name}: landed; waiting now {w.get('waiting', 0)}")
            return 0
        if c == "rejects":
            out = {f["connector"]: api.json("GET", f"/api/v1/admin/live/{_q(f['connector'])}/rejects?kind=" + _q(a.target)).get("rejects") or []
                   for f in feeds}
            if a.json:
                say_json(out)
                return 0
            for name, rows in out.items():
                print(f"{name}: {len(rows)} newest rejects of {a.target} (all: <data folder>/live/rejects/{name}/)")
                for r in rows:
                    print(f"  {r}")
            return 0
        raise CliError(f"unknown action {c}", 2)

    p = groups.add_parser("live", help="live list|show|on|off|pause|resume|apply-now|rejects: the live feeds of every kind",
                          description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="live_cmd", metavar="ACTION", required=True)
    for name, what, arg in (("list", "every live feed: state, rate, rows, rejects, last reconcile", None),
                            ("show", "a kind's feeds and its contract's rules in words", "KIND"),
                            ("on", "propose a kind's live feeds on (four eyes)", "KIND"),
                            ("off", "propose a kind's live feeds off (four eyes)", "KIND"),
                            ("pause", "pause a feed (audited)", "CONNECTOR"),
                            ("resume", "resume a paused feed (audited)", "CONNECTOR"),
                            ("apply-now", "land a kind's waiting windows at once", "KIND"),
                            ("rejects", "the newest rejects of a kind's feeds", "KIND")):
        s = sub.add_parser(name, help=what, description=f"live {name}: {what}", parents=[srv], epilog=EPILOG,
                           formatter_class=argparse.RawDescriptionHelpFormatter)
        s.set_defaults(func=cmd)
        if arg:
            s.add_argument("target", metavar=arg, help="the kind (trade, fx-spot, ...)" if arg == "KIND" else "the live connector's name")
        if name in ("on", "off"):
            s.add_argument("--connector", action="append", help="only this companion (repeat for several); default every feed of the kind")
            s.add_argument("--reason", help="why (required; the proposal carries it)")
    p.set_defaults(cmd=None)
