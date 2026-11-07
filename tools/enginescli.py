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

"""`drishti.py server engines` (and `drishti.py admin engines`, the same): Admin -> Engines from the command line
(docs/architecture/ARCHITECTURE.md section 25.10; the API: /api/v1/admin/engines, administrators only, never a personal token).

    list [--cluster] [--json]                 every engine: state, memory, needs, its file (--cluster: every node side by side)
    show <id> [--sources]                     one engine: state, memory, needs, used by, what turning it off hides; --sources: where its switch comes from
    on <id>... --reason R [--with-needs]      turn engines on (live; the in-memory lake: written, restart needed)
    off <id>... --reason R [--with-dependents]
    history [--limit N]                       the changes, newest first

Exit codes: 0 done, 2 refused (a dependency, DRS-1021; a file edited by hand, DRS-1023; usage), 3 not running anywhere (DRS-1020)."""
from __future__ import annotations

EPILOG = ("examples:\n  drishti.py server engines list\n  drishti.py server engines show frtb-sa --sources\n"
          "  drishti.py server engines off frtb-sa --reason \"market-risk node only\"\n"
          "  drishti.py server engines on frtb-sa --with-needs --reason \"capital desk asked\"\n"
          "  drishti.py server engines off exposure --with-dependents --reason \"moved to node b\" --node drishti-b\n"
          "  drishti.py admin engines list --cluster")


def _mb(n) -> str:
    n = int(n or 0)
    if n <= 0:
        return "-"
    return f"{n / (1 << 30):.1f} GB" if n >= 1 << 30 else f"{max(1, round(n / (1 << 20)))} MB"


def table(view: dict) -> list[str]:
    """The list as the design shows it: ENGINE STATE MEMORY NEEDS FILE."""
    out = [f"{'ENGINE':<15}{'STATE':<16}{'MEMORY':<10}{'NEEDS':<22}FILE"]
    for e in view.get("engines") or []:
        state = e.get("state", "")
        if e.get("restartNeeded"):
            state = "restart needed"
        file = e.get("file", "")
        if not e.get("liveOn", True) and not e.get("on"):
            file += " (restart to turn on)"
        if e.get("overridden") and e.get("installed"):
            file += f" (set by {e.get('source', '')})"
        out.append(f"{e.get('id', ''):<15}{state:<16}{_mb(e.get('memoryBytes')):<10}{', '.join(e.get('needs') or []) or '-':<22}{file}")
    return out


def matrix(view: dict) -> list[str]:
    """--cluster: nodes x engines, and whether people are offered each engine (any node runs it)."""
    c = view.get("cluster") or {}
    peers = c.get("peers") or {}
    nodes = [c.get("self") or view.get("node") or "this server"] + list(peers)
    out = [f"{'ENGINE':<15}" + "".join(f"{n[:16]:<18}" for n in nodes) + "AVAILABLE"]
    for e in view.get("engines") or []:
        cells = [e.get("state", "")] + [(peers[n] or {}).get(e["id"], "-").replace("not-installed", "not installed") for n in peers]
        out.append(f"{e['id']:<15}" + "".join(f"{x:<18}" for x in cells) + ("yes" if (c.get("available") or {}).get(e["id"]) else "no"))
    return out


def health_lines(h: dict) -> list[str]:
    """``drishti.py server health``'s lines after the summary: the linear algebra in use (engine openblas; its banner and each member's
    path when they differ), then each source with its status."""
    out = []
    blas = (h.get("native") or {}).get("blas")
    if blas:
        out.append(blas.get("line", ""))
        out += [f"  {line}" for line in blas.get("banner") or []]
        out += [f"  member {node}: {w}" for node, w in (blas.get("members") or {}).items()]
    out += [f"  {src['name']:<28}{src['status']:<10}{src.get('health', '')}" for src in h.get("sources", [])]
    return out


def register(server_sub, admin_sub, srv, ctx: dict) -> None:
    """Adds ``server engines`` and ``admin engines`` (the product owner's words, accepted as the same command)."""
    import argparse

    Api, CliError, ApiError, say_json = ctx["Api"], ctx["CliError"], ctx["ApiError"], ctx["say_json"]

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        act = a.engines_cmd
        try:
            if act in ("on", "off"):
                if not (a.reason or "").strip():
                    raise CliError("give --reason: it is kept in the audit log and History", 2)
                body = {"reason": a.reason, "node": a.node or "", "changes": [{"engine": n, "on": act == "on"} for n in a.ids],
                        "withNeeds": bool(getattr(a, "with_needs", False)), "withDependents": bool(getattr(a, "with_dependents", False))}
                res = api.json("POST", "/api/v1/admin/engines", body)
                if a.json:
                    say_json(res)
                    return 0
                words = []
                for o in res.get("outcomes") or []:
                    if o.get("outcome") == "unchanged":
                        words.append(f"{o['engine']} already {'running' if o.get('on') else 'off'}")
                    else:
                        words.append(f"{o['engine']} {'on' if o.get('on') else 'off'} ({o.get('outcome')})")
                files = [e.get("file") for e in res.get("engines") or [] if e.get("id") in a.ids]
                where = f" on {res['forwardedTo']}" if res.get("forwardedTo") else ""
                print("; ".join(words) + where + "." + (f" Written to {', '.join(files)}." if files else ""))
                return 0
            if act == "history":
                res = api.json("GET", f"/api/v1/admin/engines/history?limit={a.limit}")
                if a.json:
                    say_json(res)
                    return 0
                print(f"{'WHEN':<18}{'ENGINE':<15}{'CHANGE':<24}{'WHO':<14}{'NODE':<16}{'OUTCOME':<16}WHY")
                for h in res.get("changes") or []:
                    print(f"{str(h.get('at', '')).replace('T', ' ')[:16]:<18}{h.get('engine', ''):<15}{h.get('change', ''):<24}{h.get('who') or '-':<14}"
                          f"{h.get('node', ''):<16}{h.get('outcome', ''):<16}{h.get('why') or '-'}"
                          + (f"  ({h.get('detail')})" if h.get("outcome") == "refused" else ""))
                return 0
            view = api.json("GET", "/api/v1/admin/engines")
            if act == "show":
                e = next((x for x in view.get("engines") or [] if x.get("id") == a.id), None)
                if e is None:
                    raise CliError(f"no engine '{a.id}'; the engines: {', '.join(x['id'] for x in view.get('engines') or [])}", 2)
                if a.json:
                    say_json(e)
                    return 0
                print(f"{e['title']} ({e['id']}): {e['state']}{' - ' + e['reason'] if e.get('reason') else ''}"
                      f"{'; restart needed: ' + e['restartNeeded'] if e.get('restartNeeded') else ''}")
                print(f"  {e.get('about', '')}")
                print(f"  memory {_mb(e.get('memoryBytes'))}, threads {e.get('threads', 0)}, dates open {e.get('datesOpen', 0)}, in flight {e.get('inFlight', 0)}")
                print(f"  needs {', '.join(e.get('needs') or []) or '-'}; uses {', '.join(e.get('uses') or []) or '-'}; "
                      f"needed by {', '.join(e.get('neededBy') or []) or '-'}; used by {', '.join(e.get('usedBy') or []) or '-'}")
                print(f"  file {e.get('file')}{'' if e.get('fileExists') else ' (not written yet: the shipped copy is in effect)'}")
                for k, v in (e.get("hides") or {}).items():
                    print(f"  off hides {k}: {', '.join(v)}")
                if a.sources:
                    print(f"  the switch comes from: {e.get('source')}" + (f" (old name {e['alias']}, DRS-1024)" if e.get("alias") else "")
                          + ("; a change is written to the file but this still decides until it is removed" if e.get("overridden") else ""))
                return 0
            if a.json:
                say_json(view)
                return 0
            print("\n".join(matrix(view) if a.cluster else table(view)))
            return 0
        except ApiError as e:
            if e.drs in ("DRS-1021", "DRS-1023") or e.status in (400, 409):
                raise CliError(f"refused ({e.drs}): {str(e).split(': ', 1)[-1]}", 2) from None
            raise

    def add(sub, name):
        p = sub.add_parser("engines", help=f"{name} engines list|show|on|off|history: compute engines on or off on a server, each with its own file",
                           description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
        acts = p.add_subparsers(dest="engines_cmd", metavar="ACTION", required=True)
        for act, what in (("list", "every engine: state, memory, needs, its file"), ("show", "one engine in full"),
                          ("on", "turn engines on (with a reason)"), ("off", "turn engines off (with a reason)"), ("history", "the changes, newest first")):
            c = acts.add_parser(act, parents=[srv], help=what, description=what, epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
            c.set_defaults(func=cmd)
            if act == "list":
                c.add_argument("--cluster", action="store_true", help="every node of the cluster side by side")
            elif act == "show":
                c.add_argument("id", help="the engine's id: var, exposure, sensitivities, frtb-sa, simm, memory-lake, ...")
                c.add_argument("--sources", action="store_true", help="where the switch's value comes from")
            elif act in ("on", "off"):
                c.add_argument("ids", nargs="+", help="the engines' ids")
                c.add_argument("--reason", help="why (required; kept in the audit log and History)")
                c.add_argument("--node", help="another node of the cluster (its id); default this server")
                if act == "on":
                    c.add_argument("--with-needs", action="store_true", help="also turn on what they need on this server")
                else:
                    c.add_argument("--with-dependents", action="store_true", help="also turn off the engines that need them")
            else:
                c.add_argument("--limit", type=int, default=50, help="most changes listed (default 50)")
        return p

    add(server_sub, "server")
    if admin_sub is not None:
        add(admin_sub, "admin")

    def blas(a, extra) -> int:
        """server blas java|native|status: the Linear algebra switch of Admin -> Engines (engine openblas), with its reason and audit."""
        api = Api.from_args(a)
        if a.blas_cmd in ("java", "native"):
            if not (a.reason or "").strip():
                raise CliError("give --reason: it is kept in the audit log and History", 2)
            body = {"reason": a.reason, "node": a.node or "", "changes": [{"engine": "openblas", "on": a.blas_cmd == "native"}]}
            try:
                res = api.json("POST", "/api/v1/admin/engines", body)
            except ApiError as e:
                if e.drs in ("DRS-1021", "DRS-1023") or e.status in (400, 409):
                    raise CliError(f"refused ({e.drs}): {str(e).split(': ', 1)[-1]}", 2) from None
                raise
            if a.json:
                say_json(res)
                return 0
            row = next((x for x in res.get("engines") or [] if x.get("id") == "openblas"), {})
            o = next((x for x in res.get("outcomes") or [] if x.get("engine") == "openblas"), {})
            if a.blas_cmd == "native" and row.get("state") not in (None, "running"):
                print(f"refused: {row.get('reason') or o.get('detail') or row.get('state')}")    # the self-test failed: Java stays
                return 1
            print(f"linear algebra: {'OpenBLAS (native)' if a.blas_cmd == 'native' else 'pure Java (chosen)'}: {o.get('outcome', 'done')}"
                  + (f" on {res['forwardedTo']}" if res.get("forwardedTo") else ""))
            blas_line = ((res.get("native") or {}).get("blas") or {}).get("line")
            if blas_line:
                print(blas_line)
            return 0
        view = api.json("GET", "/api/v1/admin/engines")
        b = (view.get("native") or {}).get("blas") or {}
        if a.json:
            say_json(b)
            return 0
        print(b.get("line") or "BLAS: no status (an older server)")
        if b.get("clusterPath"):
            print(f"  cluster sums: {b['clusterPath']}")
        for node, w in (b.get("members") or {}).items():
            print(f"  member {node}: {w}")
        return 0

    for sub in (server_sub, admin_sub):
        if sub is None:
            continue
        p = sub.add_parser("blas", help="blas java|native|status: linear algebra on the pure-Java path or the bundled OpenBLAS, live",
                           description="The Linear algebra switch of Admin -> Engines (engine openblas): java chooses the pure-Java path (a notice, "
                                       "the same figures); native runs the self-test against Java first and refuses in words if it fails. "
                                       "docs/admin/OPERATIONS.md section 2.1.")
        acts = p.add_subparsers(dest="blas_cmd", metavar="ACTION", required=True)
        for act, what in (("status", "this server's path, the cluster's and each member's"), ("java", "switch to the pure-Java path (with a reason)"),
                          ("native", "switch back to OpenBLAS after its self-test (with a reason)")):
            c = acts.add_parser(act, parents=[srv], help=what, description=what)
            c.set_defaults(func=blas)
            if act != "status":
                c.add_argument("--reason", help="why (required; kept in the audit log and History)")
                c.add_argument("--node", help="another node of the cluster (its id); default this server")
