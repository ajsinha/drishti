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

"""`drishti.py change ...` and `drishti.py edits`: write-back from the command line (docs/guides/WRITE_BACK.md › The design and its reasons, section 13; the guide:
docs/guides/WRITE_BACK.md, "Submitting"), for the caller's token (it needs the edit scope): propose a change to a record's fields
(the same request as a form's Submit: the field must be editable by a Sutra's form with save:, the server checks everything again),
submit a form's draft, list changes, show one, approve, reject or withdraw, a record's history, and Admin -> Edits (edits status).
Exit codes: 0, 1 (refused, conflicted, or the server said no), 2 (usage)."""
from __future__ import annotations

import urllib.parse

EPILOG = ("examples:\n  drishti.py change propose --kind trade --id MX-50000001 --set NOTIONAL=26000000 --from NOTIONAL=25000000 --reason \"booking typo\"\n"
          "  drishti.py change submit d-4ab964fa3060 --reason \"booking typo\"\n"
          "  drishti.py change list --state awaiting\n  drishti.py change show chg-01JB3Z5V9Q8R7S6T5U4V3W2X1Y\n"
          "  drishti.py change approve chg-01JB3Z5V9Q8R7S6T5U4V3W2X1Y --note \"checked against the ticket\"\n"
          "  drishti.py change reject chg-01JB3Z5V9Q8R7S6T5U4V3W2X1Y --reason \"wrong ticket\"\n"
          "  drishti.py change history trade MX-50000001\n  drishti.py edits status")


def values(items, what: str, err) -> dict:
    """``FIELD=value`` arguments; a value that reads as a number is sent as one, ``true``/``false`` as a switch."""
    out = {}
    for it in items or []:
        if "=" not in it:
            raise err(f"{what} '{it}' is not FIELD=value", 2)
        k, _, v = it.partition("=")
        v = v.strip()
        if v.lower() in ("true", "false"):
            out[k.strip()] = v.lower() == "true"
            continue
        try:
            out[k.strip()] = int(v) if v.lstrip("-").isdigit() else float(v) if v.replace(".", "", 1).lstrip("-").isdigit() else v
        except ValueError:
            out[k.strip()] = v
    return out


def render_change(c: dict, say) -> None:
    """One change in a few lines: id, record, state (and why), each field from -> to, approvals."""
    say(f"{c.get('id')}  {c.get('kind')} {c.get('entity')}  {str(c.get('state', '')).upper()}  ({c.get('target')}, by {c.get('by')})")
    if c.get("stateReason"):
        say("  " + c["stateReason"])
    for it in c.get("items") or []:
        say(f"  {str(it.get('path', '')).replace('$.', '')}: {it.get('from')} -> {it.get('to')}")
    if c.get("needed"):
        say(f"  approvals {len(c.get('approvals') or [])} of {c['needed']}")
    if c.get("commandState"):
        say(f"  at {c.get('connector')}: {c['commandState']}" + (f" ({c['commandReason']})" if c.get("commandReason") else ""))


def register(groups, srv, ctx: dict) -> None:
    """Adds ``change propose|submit|list|show|approve|reject|withdraw|history`` and ``edits status``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    q = lambda s: urllib.parse.quote(s, safe="")   # noqa: E731

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.change_cmd
        if c == "propose":
            to = values(a.set, "--set", CliError)
            if not to:
                raise CliError("say what changes: --set FIELD=value (repeat for several)", 2)
            was = values(a.was, "--from", CliError)
            body = {"kind": a.kind, "entity": a.id, "reason": a.reason or "", "via": "cli",
                    "items": [{"field": k, "from": was.get(k), "to": v} for k, v in to.items()]}
            for k, v in (("sutra", a.sutra), ("panel", a.panel), ("businessDate", a.date)):
                if v:
                    body[k] = v
            if a.override:
                body["override"] = True
            return answers(api.json("POST", "/api/v1/changes", body), a)
        if c == "submit":
            return answers(api.json("POST", "/api/v1/changes", {"draft": a.draft, "reason": a.reason or ""}), a)
        if c == "list":
            qs = {k: v for k, v in (("state", a.state), ("kind", a.kind), ("entity", a.entity)) if v}
            if a.mine:
                qs["mine"] = "true"
            res = api.json("GET", "/api/v1/changes" + ("?" + urllib.parse.urlencode(qs) if qs else ""))
            if a.json:
                say_json(res)
            else:
                for ch in res.get("changes") or []:
                    render_change(ch, print)
            return 0
        if c == "show":
            res = api.json("GET", f"/api/v1/changes/{q(a.change)}")
            if a.json:
                say_json(res)
            else:
                render_change(res, print)
            return 0 if res.get("state") not in ("refused", "conflicted", "rejected") else 1
        if c in ("approve", "reject", "withdraw"):
            body = {"note": a.note or ""} if c == "approve" else {"reason": a.reason or ""} if c == "reject" else {}
            say_json(api.json("POST", f"/api/v1/changes/{q(a.change)}/{c}", body))
            return 0
        res = api.json("GET", f"/api/v1/entities/{q(a.kind_)}/{q(a.id_)}/history")
        if a.json:
            say_json(res)
        else:
            print(f"{res.get('kind')} {res.get('id')}  version {res.get('version')}")
            for ch in res.get("changes") or []:
                render_change(ch, print)
        return 0

    def answers(res: dict, a) -> int:
        if a.json:
            say_json(res)
        else:
            for ch in res.get("changes") or []:
                extra = f" (lane {ch['lane']}, seq {ch['seq']})" if "lane" in ch else f" ({ch.get('needed')} approval needed)" if ch.get("needed") else ""
                print(f"{ch.get('id')}  {ch.get('kind')} {ch.get('entity')}  {str(ch.get('state', '')).upper()}{extra}")
        return 0

    def edits(a, extra) -> int:
        say_json(Api.from_args(a).json("GET", "/api/v1/admin/edits"))
        return 0

    p = groups.add_parser("change", help="change: write-back (propose a change, submit a form's draft, approve, reject, history)",
                          description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="change_cmd", required=True)
    pr = sub.add_parser("propose", help="propose a change to a record's fields, as you", parents=[srv])
    pr.add_argument("--kind", required=True)
    pr.add_argument("--id", required=True, help="the record's id")
    pr.add_argument("--set", action="append", metavar="FIELD=VALUE", help="the new value (repeat for several)")
    pr.add_argument("--from", dest="was", action="append", metavar="FIELD=VALUE", help="the value you saw: a field that moved since is conflicted")
    pr.add_argument("--reason", help="why (required when the form asks for one)")
    pr.add_argument("--sutra", help="the Sutra whose form decides the rules (default: the first form that edits the field)")
    pr.add_argument("--panel", help="the panel of that Sutra")
    pr.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (a past one is a restatement: it needs the restate role)")
    pr.add_argument("--override", action="store_true", help="Apply mine anyway over a newer value (needs the override role)")
    sb = sub.add_parser("submit", help="submit one of your form drafts (d-...) as change requests", parents=[srv])
    sb.add_argument("draft")
    sb.add_argument("--reason")
    ls = sub.add_parser("list", help="changes, newest first", parents=[srv])
    ls.add_argument("--state", choices=["awaiting", "queued", "applying", "applied", "refused", "conflicted", "rejected", "withdrawn"])
    ls.add_argument("--kind")
    ls.add_argument("--entity")
    ls.add_argument("--mine", action="store_true", help="only yours")
    for name, what in (("show", "one change"), ("approve", "approve a change (an approver, never your own)"),
                       ("reject", "reject a change with a reason"), ("withdraw", "withdraw your change while it awaits approval")):
        s = sub.add_parser(name, help=what, parents=[srv])
        s.add_argument("change", help="the change id (chg-...)")
        if name == "approve":
            s.add_argument("--note")
        if name == "reject":
            s.add_argument("--reason", required=True)
    h = sub.add_parser("history", help="a record's changes from the edit log", parents=[srv])
    h.add_argument("kind_", metavar="KIND")
    h.add_argument("id_", metavar="ID")
    p.set_defaults(func=cmd, cmd=None)

    e = groups.add_parser("edits", help="edits: Admin -> Edits (where the edit log lives, lanes, warnings, connector writes)",
                          description="Admin -> Edits from the command line (administrators).")
    es = e.add_subparsers(dest="edits_cmd", required=True)
    es.add_parser("status", help="the store, lanes, warnings, unconfirmed source commands and connector writes", parents=[srv])
    e.set_defaults(func=edits, cmd=None)
