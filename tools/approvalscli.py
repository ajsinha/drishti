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

"""`drishti.py approvals ...`: the one Approvals queue from the command line (USER_GUIDE.md "Approvals"; API_GUIDE.md "Approvals"): the
items waiting for you (and those you sent, or decided), one item with what changes, its effect and the rules, and the decision (approve,
reject with a reason, withdraw your own) or a question on it. Every four-eyes kind is decided here: Sutra proposals, corrections, BI
items, compute packages, pivot functions, stress windows and report sign-offs. Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import urllib.parse

KINDS = ("sutra", "change", "bi", "compute", "function", "stress-window", "var-report")
EPILOG = ("examples:\n  drishti.py approvals list\n  drishti.py approvals list --box sent --group risk\n"
          "  drishti.py approvals show sutra P-000042\n"
          "  drishti.py approvals approve change chg-01JB3Z5V9Q8R7S6T5U4V3W2X1Y --comment \"checked against the ticket\"\n"
          "  drishti.py approvals reject bi it-0042 --comment \"name the columns\"\n"
          "  drishti.py approvals approve function asha:wret@1 --comment reviewed --param scope=group:rates\n"
          "  drishti.py approvals approve var-report annual-report-var:FY2026:v1 --param step=checker\n"
          "  drishti.py approvals ask sutra P-000042 \"Why the new title?\"")


def params(pairs, CliError) -> dict:
    out = {}
    for p in pairs or []:
        if "=" not in p:
            raise CliError(f"--param {p}: give NAME=VALUE", 2)
        k, v = p.split("=", 1)
        out[k.strip()] = True if v.strip().lower() == "true" else v.strip()
    return out


def line(e: dict) -> str:
    i = e["item"]
    due = f"  due {i['due']}" if e.get("dueSoon") and i.get("due") else ""
    return (f"{'!' if e.get('dueSoon') else ' '} {i['kind']:<13} {i['id']:<34} {str(i.get('status', '')):<9} {i.get('maker') or '':<10} "
            f"{i.get('title', '')}{due}" + (f"  ({e['questions']} q)" if e.get("questions") else ""))


def register(groups, srv, ctx: dict) -> None:
    """Adds ``approvals list|show|approve|reject|withdraw|ask``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    q = lambda s: urllib.parse.quote(s, safe=":@~+")   # noqa: E731

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.approvals_cmd
        if c == "list":
            qs = {"box": a.box}
            if a.group:
                qs["group"] = a.group
            if a.kind:
                qs["kind"] = a.kind
            res = api.json("GET", "/api/v1/approvals?" + urllib.parse.urlencode(qs))
            if a.json:
                say_json(res)
                return 0
            print(f"{res.get('inbox', 0)} waiting for you, {res.get('dueSoon', 0)} due soon")
            for e in res.get("items") or []:
                print(line(e))
            for u in res.get("unavailable") or []:
                print("could not be read: " + u)
            return 0
        if c == "show":
            res = api.json("GET", f"/api/v1/approvals/{q(a.kind_)}/{q(a.id_)}")
            if a.json:
                say_json(res)
                return 0
            d, i = res["detail"], res["detail"]["item"]
            print(f"{res.get('label')}: {i['title']}  [{i['status']}{' · ' + i['stage'] if i.get('stage') else ''}]  by {i.get('maker')}")
            if i.get("reason"):
                print(f"  why: {i['reason']}")
            print("what changes:")
            diff = d.get("diff") or {}
            for r in diff.get("rows") or []:
                if diff.get("format") == "fields":
                    print(f"  {r.get('field')}: {r.get('before')} -> {r.get('after')}")
                elif r.get("op") != "same":
                    if r.get("left") is not None and r.get("op") != "added":
                        print(f"  - {r['left']}")
                    if r.get("right") is not None and r.get("op") != "removed":
                        print(f"  + {r['right']}")
            print("effect if approved:")
            for x in d.get("effect") or []:
                print(f"  {x}")
            print("rules:")
            for x in d.get("rules") or []:
                print(f"  {x}")
            for p in res.get("questions") or []:
                print(f"  ? {p['by']} {p['at'][:16]}: {p['text']}")
            print("you may: " + ", ".join(res.get("actions") or []))
            return 0
        if c == "ask":
            say_json(api.json("POST", f"/api/v1/approvals/{q(a.kind_)}/{q(a.id_)}/questions", {"text": a.text}))
            return 0
        if c == "reject" and not (a.comment or "").strip():
            raise CliError("say why it is rejected: --comment \"...\"", 2)
        res = api.json("POST", f"/api/v1/approvals/{q(a.kind_)}/{q(a.id_)}/{c}", {"comment": a.comment or "", "params": params(a.param, CliError)})
        if a.json:
            say_json(res)
        else:
            print(f"{res.get('kind')} {res.get('id')}  {str(res.get('status', '')).upper()}")
        return 0

    p = groups.add_parser("approvals", help="approvals: the one queue of four-eyes items (list, show, approve, reject, withdraw, ask)",
                          description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="approvals_cmd", required=True)
    ls = sub.add_parser("list", help="the queue: waiting for you (inbox), sent by you, decided, or all; due soon first", parents=[srv])
    ls.add_argument("--box", choices=["inbox", "sent", "done", "all"], default="inbox")
    ls.add_argument("--group", choices=["data", "screens", "risk", "access"])
    ls.add_argument("--kind", choices=KINDS)
    for name, what in (("show", "one item: what changes, its effect, the rules, the questions"),
                       ("approve", "approve an item (never your own)"), ("reject", "reject an item, with a reason"),
                       ("withdraw", "withdraw an item you sent"), ("ask", "ask a question on an item (or answer one)")):
        s = sub.add_parser(name, help=what, parents=[srv])
        s.add_argument("kind_", metavar="KIND", choices=KINDS)
        s.add_argument("id_", metavar="ID")
        if name == "ask":
            s.add_argument("text")
        elif name != "show":
            s.add_argument("--comment", help="a note, or the reason (required to reject)")
            s.add_argument("--param", action="append", metavar="NAME=VALUE",
                           help="what a kind needs besides: scope (a pivot function), step (a report run), force or applyRenames (a BI item)")
    p.set_defaults(func=cmd, cmd=None)
