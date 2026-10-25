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

"""`drishti.py compute ...`: reactive connectors from the command line (docs/connectors/REACTIVE_CONNECTOR.md, "The HTTP API"), for the
caller's token: ask for a computed kind (run), cancel a request, send an event to the triggers (event, administrators), list the caller's
what-if sandboxes, and Admin's view of the reactive connectors (reactive). Exit codes: 0, 1 (refused, failed, or the server said no),
2 (usage)."""
from __future__ import annotations

import urllib.parse

EPILOG = ("examples:\n  drishti.py compute run path-sum-java path-sum-java BOOK-FX --param delay-ms=50\n"
          "  drishti.py compute run path-sum-python path-sum BOOK-RATES --wait 10000 --json\n"
          "  drishti.py compute cancel req-mvnbo42x-554223c23f7e\n"
          "  drishti.py compute event write-back chg-77 --kind path-strip --entity STRIP-FX-001 --payload BOOK_ID=BOOK-FX --watermark L3=12\n"
          "  drishti.py compute sandboxes\n  drishti.py compute reactive")


def pairs(items, what: str, err) -> dict:
    """``name=value`` arguments as a mapping; a value that is a whole number stays a number."""
    out = {}
    for it in items or []:
        if "=" not in it:
            raise err(f"{what} '{it}' is not name=value", 2)
        k, _, v = it.partition("=")
        out[k.strip()] = int(v) if v.strip().lstrip("-").isdigit() else v
    return out


def render_answer(res: dict, say) -> int:
    """One answer in a few lines: the state, the request, the headline fields, the stamp's pin, engine and where it ran."""
    say(f"{res.get('connector')} {res.get('id')}  {res.get('state', '').upper()}  ({res.get('requestId')})")
    if res.get("message"):
        say("  " + res["message"])
    if res.get("progress"):
        say(f"  progress {res['progress']}")
    doc = res.get("result") or {}
    for k, v in doc.items():
        if k not in ("ID", "RESULTS", "TABLES", "STAMP", "COMPUTE") and not isinstance(v, (dict, list)):
            say(f"  {k:<12} {v}")
    st = doc.get("STAMP") or {}
    if st:
        say(f"  computed {st.get('computedAt')} by {st.get('engine')}, pin {st.get('pin')}, {st.get('resultHash')}")
        if st.get("ran"):
            say(f"  ran on {st['ran']}")
    return 0 if res.get("state") in ("ready", "computing", "stale") else 1


def register(groups, srv, ctx: dict) -> None:
    """Adds ``compute run|cancel|event|sandboxes|reactive``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    q = lambda s: urllib.parse.quote(s, safe="")   # noqa: E731

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        if a.compute_cmd == "run":
            body = {"id": a.id, "params": pairs(a.param, "--param", CliError), "wait": a.wait}
            if a.date:
                body["asOf"] = a.date
            res = api.json("POST", f"/api/v1/compute/{q(a.connector)}/{q(a.kind)}", body)
            if a.json:
                say_json(res)
                return 0 if res.get("state") in ("ready", "computing", "stale") else 1
            return render_answer(res, print)
        if a.compute_cmd == "cancel":
            say_json(api.json("DELETE", f"/api/v1/compute/requests/{q(a.request)}"))
            return 0
        if a.compute_cmd == "event":
            body = {"id": a.id, "type": a.type, "kind": a.kind or "", "entity": a.entity or "", "payload": pairs(a.payload, "--payload", CliError),
                    "watermark": pairs(a.watermark, "--watermark", CliError)}
            if a.date:
                body["businessDate"] = a.date
            say_json(api.json("POST", "/api/v1/compute/events", body))
            return 0
        if a.compute_cmd == "sandboxes":
            say_json(api.json("GET", "/api/v1/compute/sandboxes"))
            return 0
        say_json(api.json("GET", "/api/v1/admin/compute/reactive"))
        return 0

    p = groups.add_parser("compute", help="compute: reactive connectors (ask for a computed kind, cancel, send an event, sandboxes)",
                          description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="compute_cmd", required=True)
    run = sub.add_parser("run", help="ask a reactive connector for a computed kind, as you", parents=[srv])
    run.add_argument("connector")
    run.add_argument("kind")
    run.add_argument("id", help="the question's id, for example BOOK-FX")
    run.add_argument("--param", action="append", metavar="NAME=VALUE", help="a parameter (repeat for several)")
    run.add_argument("--wait", type=int, default=5000, metavar="MS", help="how long to wait for the result before answering computing (default 5000)")
    run.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the current one)")
    c = sub.add_parser("cancel", help="cancel one of your requests", parents=[srv])
    c.add_argument("request", help="the request id (req-...)")
    e = sub.add_parser("event", help="send an event to every reactive connector's triggers (administrators)", parents=[srv])
    e.add_argument("type", choices=["write-back", "live", "data-version", "schedule"])
    e.add_argument("id", help="the event's id (a change id): an id already processed is ignored")
    e.add_argument("--kind")
    e.add_argument("--entity")
    e.add_argument("--date", metavar="YYYY-MM-DD")
    e.add_argument("--payload", action="append", metavar="NAME=VALUE")
    e.add_argument("--watermark", action="append", metavar="LANE=SEQ")
    sub.add_parser("sandboxes", help="your private what-if sandboxes", parents=[srv])
    sub.add_parser("reactive", help="Admin's view of the reactive connectors (administrators)", parents=[srv])
    p.set_defaults(func=cmd, cmd=None)
