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

"""`drishti.py net list|check|add-peer|set|members`: Drishti*net's config/net/ folder from the terminal (docs/admin/OPERATIONS.md).

    list                    the folder's files (in force, refused, absent) and what they say: on or off, node id, address, tags, seeds
    check [FILE ...]        check every file (or those named) as the server does; exit 1 when one would be refused
    add-peer NAME URL       write peers/NAME.yaml (a seed); --tags region:emea,tier:risk
    set KEY VALUE           change one setting: node.enabled true, node.tags region:emea, gossip.interval 2s, routing.scatter-budget 5s,
                            access.roles.risk.nodes n-a,region:emea, peers.drishti-a.url http://host:port; an empty VALUE removes it
    members                 the member table of a running server (GET /api/v1/net/members; an administrator sees every member)

The folder is --dir, else DRISHTI_NET_DIR, else ./config/net (the server's drishti.net.dir). A write is atomic and keeps the file's previous
text under <data dir>/net/history (--data-dir, else DRISHTI_DATA_DIR, else ./data), as the server's own writes do; a running server picks
the change up by itself. The cluster key is only ever written as a reference (secret: ${DRISHTI_NET_SECRET}); a key in clear is refused.
Exit: 0 done, 1 refused or a file has an error, 2 usage.
"""
from __future__ import annotations

import datetime
import os
import pathlib
import re

import hostaddr

FIXED = ("node.yaml", "gossip.yaml", "routing.yaml", "access.yaml")
NAME = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")
ENV = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_.]*)(?::([^}]*))?}")
SECRET_REF = re.compile(r"\$\{[A-Za-z_][A-Za-z0-9_.]*(:)?}")
DURATION = re.compile(r"(\d+(?:\.\d+)?)\s*(ms|s|m|h|d)")
TAG = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*(:[A-Za-z0-9._-]+)?")
KNOWN = {
    "node.yaml": {"enabled", "node-id", "advertise-url", "tags", "peer-only-packs", "secret", "previous-secret", "tls", "gossip-port"},
    "gossip.yaml": {"interval", "fanout", "suspect-after", "indirect-probes", "dead-after", "forget-after", "persist-every"},
    "routing.yaml": {"forward-timeout", "scatter-budget", "suggest-budget", "link-budget", "max-hops", "weights", "user-state",
                     "join-wait", "join-grace", "peer-protocol"},
    "access.yaml": {"roles"},
    "peer": {"url", "tags", "tls"},
}
LISTS = ("tags", "peer-only-packs", "nodes")


def _yaml(cli):
    return cli.need_yaml()


def folder(a) -> pathlib.Path:
    return pathlib.Path(getattr(a, "dir", None) or os.environ.get("DRISHTI_NET_DIR") or "config/net")


def data_dir(a) -> pathlib.Path:
    return pathlib.Path(getattr(a, "data_dir", None) or os.environ.get("DRISHTI_DATA_DIR") or "data")


def file_ids(d: pathlib.Path) -> list[str]:
    peers = sorted(f"peers/{p.name}" for p in (d / "peers").glob("*.yaml") if NAME.fullmatch(p.stem)) if (d / "peers").is_dir() else []
    return list(FIXED) + peers


def resolve(text: str) -> str:
    return ENV.sub(lambda m: os.environ.get(m.group(1), m.group(2) if m.group(2) is not None else ""), str(text))


def duration_ms(text: str) -> int | None:
    m = DURATION.fullmatch(str(text).strip().lower())
    if not m:
        return None
    n = float(m.group(1))
    return round(n * {"ms": 1, "s": 1000, "m": 60_000, "h": 3_600_000, "d": 86_400_000}[m.group(2)])


def line_of(text: str, key: str) -> int:
    key = key.split(".")[-1]
    for i, ln in enumerate(text.splitlines(), 1):
        if re.match(r"^\s*-?\s*" + re.escape(key) + r"\s*:", ln):
            return i
    return 0


def check_text(cli, fid: str, text: str) -> list[dict]:
    """The problems of a file's text, as the server's NetSchema finds them: {level, field, line, message}."""
    out: list[dict] = []

    def err(field, message, level="error"):
        out.append({"file": fid, "level": level, "field": field, "line": line_of(text, field) if field else 0, "message": message})
    try:
        m = _yaml(cli).safe_load(text) if text.strip() else {}
    except Exception as e:  # noqa: BLE001 - any YAML error refuses the file
        mark = getattr(e, "problem_mark", None)
        out.append({"file": fid, "level": "error", "field": "", "line": (mark.line + 1) if mark else 0, "message": f"is not YAML: {e}"})
        return out
    m = m or {}
    if not isinstance(m, dict):
        err("", "the file is a mapping of settings (key: value), not a list or a single value")
        return out
    known = KNOWN["peer" if fid.startswith("peers/") else fid]
    for k in m:
        if k not in known:
            err(k, f"unknown setting; ignored (known: {', '.join(sorted(known))})", "warning")

    def url(key, value):
        problem = hostaddr.url_problem(value)
        if problem:
            err(key, problem)

    def tags(key):
        for t in m.get(key) or []:
            if not TAG.fullmatch(str(t)):
                err(key, f"'{t}' is not a tag: a word or key:value (region:emea)")

    def dur(key, lo, hi):
        if key in m:
            ms = duration_ms(resolve(m[key]))
            if ms is None:
                err(key, f"'{m[key]}' is not a duration (150ms, 5s, 10m, 24h)")
            elif not lo <= ms <= hi:
                err(key, f"{m[key]} is outside the allowed range")

    def integer(key, lo, hi):
        if key in m:
            try:
                n = int(str(resolve(m[key])))
                if not lo <= n <= hi:
                    err(key, f"{n} is outside {lo} to {hi}")
            except ValueError:
                err(key, f"'{m[key]}' is not a whole number")
    if fid == "node.yaml":
        enabled = str(resolve(m.get("enabled", False))).lower()
        if enabled not in ("true", "false"):
            err("enabled", f"'{enabled}' is not true or false")
        if m.get("node-id") and not NAME.fullmatch(str(m["node-id"])):
            err("node-id", f"'{m['node-id']}' is not a node id: lower-case letters, digits and dashes, at most 64")
        if m.get("advertise-url"):
            if hostaddr.loopback_url(resolve(m["advertise-url"])):      # first: it names the address to use instead
                err("advertise-url", hostaddr.refusal("the address this node advertises", resolve(m["advertise-url"]))
                    + "; or leave advertise-url out and the node advertises that address with its port")
            url("advertise-url", resolve(m["advertise-url"]))
        tags("tags")
        secret = ""
        for key in ("secret", "previous-secret"):
            raw = str(m.get(key) or "").strip()
            if raw and not SECRET_REF.fullmatch(raw):
                env = "DRISHTI_NET_" + key.replace("-", "_").upper()
                err(key, f"the cluster key is written only as an environment reference ({key}: ${{{env}}}), never in clear and with no default")
            elif key == "secret":
                secret = resolve(raw)
        if enabled == "true":
            if not secret:
                err("secret", "enabled: true needs the cluster key: secret: ${DRISHTI_NET_SECRET} with the variable set (at least 32 bytes)")
            if not m.get("advertise-url") and not hostaddr.primary_ipv4():
                err("advertise-url", "enabled: true needs advertise-url (the address peers and consoles call this node at, https://host:port): "
                    "this machine has no address but loopback to advertise")
        if secret and len(secret.encode()) < 32:
            err("secret", "the cluster key must be at least 32 bytes")
        if "gossip-port" in m:                                       # auto (default), off, or a port (2.6.7); YAML reads a bare off as false
            gp = str(resolve(m["gossip-port"])).strip().lower()
            if gp not in ("auto", "off", "false", ""):
                if not gp.isdigit():
                    err("gossip-port", f"'{gp}' is not auto, off or a port number")
                elif not 1 <= int(gp) <= 65535:
                    err("gossip-port", f"{gp} is outside 1 to 65535")
    elif fid == "gossip.yaml":
        dur("interval", 50, 60_000)
        integer("fanout", 1, 16)
        dur("suspect-after", 100, 3_600_000)
        integer("indirect-probes", 0, 8)
        dur("dead-after", 100, 86_400_000)
        dur("forget-after", 1000, 30 * 86_400_000)
        dur("persist-every", 1000, 86_400_000)
    elif fid == "routing.yaml":
        dur("forward-timeout", 50, 300_000)
        dur("scatter-budget", 50, 300_000)
        dur("suggest-budget", 10, 10_000)
        dur("link-budget", 10, 30_000)
        integer("max-hops", 0, 1)
        if "user-state" in m and str(m["user-state"]) not in ("replicate", "off"):
            err("user-state", f"'{m['user-state']}' is not replicate or off")
        dur("join-wait", 0, 30_000)
        dur("join-grace", 0, 600_000)
        if "peer-protocol" in m and str(m["peer-protocol"]).lower() not in ("http2", "http1"):
            err("peer-protocol", f"'{m['peer-protocol']}' is not http2 or http1")
    elif fid == "access.yaml":
        roles = m.get("roles")
        if roles is not None and not isinstance(roles, dict):
            err("roles", "roles is a mapping: { <role>: { nodes: [<node id or tag>, ...] } }")
        for role, spec in (roles or {}).items():
            if spec is not None and not (isinstance(spec, dict) and isinstance(spec.get("nodes", []), list)):
                err(f"roles.{role}", "a role is { nodes: [<node id or tag>, ...] }")
    else:
        if not m.get("url"):
            err("url", "a peer file needs url: the peer's address (https://host:port)")
        else:
            if hostaddr.loopback_url(resolve(m["url"])):
                err("url", hostaddr.refusal("a peer's address", resolve(m["url"])))
            url("url", resolve(m["url"]))
        tags("tags")
    return out


def _read(d: pathlib.Path, fid: str) -> str | None:
    p = d / fid
    return p.read_text(encoding="utf-8") if p.is_file() else None


def _write(a, fid: str, text: str) -> None:
    """Atomic, with the previous text kept under <data dir>/net/history (as the server writes)."""
    d = folder(a)
    p = d / fid
    p.parent.mkdir(parents=True, exist_ok=True)
    if p.is_file():
        hist = data_dir(a) / "net" / "history"
        hist.mkdir(parents=True, exist_ok=True)
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%f")[:18]
        (hist / f"{fid.replace('.yaml', '').replace('/', '__')}.{stamp}.yaml").write_text(p.read_text(encoding="utf-8"), encoding="utf-8")
    tmp = p.with_name(f"net-{os.getpid()}.tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, p)


def _render(cli, fid: str, settings: dict) -> str:
    body = _yaml(cli).safe_dump(settings, sort_keys=False, default_flow_style=False) if settings else "{}\n"
    return f"# config/net/{fid} (Drishti*net, docs/admin/OPERATIONS.md)\n" + body


def _say_problems(problems: list[dict]) -> None:
    for p in problems:
        where = p["file"] + (f":{p['line']}" if p["line"] else "")
        print(f"  {p['level']:7} {where}  {p['field'] + ': ' if p['field'] else ''}{p['message']}")


def cmd_list(cli, a, extra) -> int:
    d = folder(a)
    rows, summary, all_problems = [], {}, []
    for fid in file_ids(d):
        text = _read(d, fid)
        problems = check_text(cli, fid, text) if text is not None else []
        all_problems += problems
        state = "absent (defaults)" if text is None else "refused" if any(p["level"] == "error" for p in problems) else "in force"
        rows.append({"file": fid, "state": state, "problems": problems})
        if text is not None and fid == "node.yaml":
            n = _yaml(cli).safe_load(text) or {}
            summary = {"enabled": bool(str(resolve(n.get("enabled", False))).lower() == "true"), "nodeId": n.get("node-id") or "(generated)",
                       "advertiseUrl": n.get("advertise-url") or "", "tags": n.get("tags") or [], "peerOnlyPacks": n.get("peer-only-packs") or [],
                       "key": "set" if resolve(n.get("secret") or "") else "not set"}
    seeds = []
    for fid in file_ids(d):
        if fid.startswith("peers/"):
            p = _yaml(cli).safe_load(_read(d, fid) or "") or {}
            seeds.append({"name": fid[6:-5], "url": p.get("url", "")})
    if a.json:
        cli.say_json({"dir": str(d), "node": summary or {"enabled": False}, "seeds": seeds, "files": rows})
        return 0
    on = summary.get("enabled")
    print(f"config/net at {d}: Drishti*net is {'ON' if on else 'off'}" + ("" if on else " (a single server, as without the cluster)"))
    if summary:
        print(f"  node {summary['nodeId']}  at {summary['advertiseUrl'] or '-'}  tags {','.join(summary['tags']) or '-'}  key {summary['key']}"
              + (f"  peer-only packs {','.join(summary['peerOnlyPacks'])}" if summary["peerOnlyPacks"] else ""))
    for s in seeds:
        print(f"  seed {s['name']}  {s['url']}")
    width = max(len(r["file"]) for r in rows)
    for r in rows:
        print(f"  {r['file'].ljust(width)}  {r['state']}")
    _say_problems(all_problems)
    return 0


def cmd_check(cli, a, extra) -> int:
    d = folder(a)
    ids = a.files or file_ids(d)
    problems, checked = [], 0
    for fid in ids:
        fid = fid if fid.endswith(".yaml") else fid + ".yaml"
        if fid not in FIXED and not (fid.startswith("peers/") and NAME.fullmatch(fid[6:-5])):
            raise cli.CliError(f"{fid} is not a file of config/net/: node.yaml, gossip.yaml, routing.yaml, access.yaml or peers/<name>.yaml", 2)
        text = _read(d, fid)
        if text is None:
            continue
        checked += 1
        problems += check_text(cli, fid, text)
    errors = [p for p in problems if p["level"] == "error"]
    if a.json:
        cli.say_json({"dir": str(d), "checked": checked, "ok": not errors, "problems": problems})
    else:
        print(f"{checked} file(s) checked in {d}: " + ("all would be taken" if not errors else f"{len(errors)} error(s); those files would be refused"))
        _say_problems(problems)
    return 1 if errors else 0


def _apply(cli, a, fid: str, text: str) -> int:
    problems = check_text(cli, fid, text)
    errors = [p for p in problems if p["level"] == "error"]
    if errors:
        _say_problems(problems)
        raise cli.CliError(f"{fid} was not written: {errors[0]['message']}", 1)
    _write(a, fid, text)
    if getattr(a, "json", False):
        cli.say_json({"file": fid, "written": True, "problems": problems})
    else:
        print(f"wrote {folder(a) / fid}" + (f" ({len(problems)} warning(s))" if problems else ""))
        _say_problems(problems)
    return 0


def cmd_add_peer(cli, a, extra) -> int:
    if not NAME.fullmatch(a.name):
        raise cli.CliError(f"{a.name!r} is not a peer name: lower-case letters, digits and dashes", 2)
    url = a.url if "://" in a.url else "http://" + a.url
    settings = {"url": url.rstrip("/")}
    if a.tags:
        settings["tags"] = [t.strip() for t in a.tags.split(",") if t.strip()]
    return _apply(cli, a, f"peers/{a.name}.yaml", _render(cli, f"peers/{a.name}.yaml", settings))


def cmd_set(cli, a, extra) -> int:
    parts = a.key.split(".")
    if parts[0] == "peers" and len(parts) >= 3:
        fid, path = f"peers/{parts[1]}.yaml", parts[2:]
    elif parts[0] + ".yaml" in FIXED and len(parts) >= 2:
        fid, path = parts[0] + ".yaml", parts[1:]
    else:
        raise cli.CliError(f"{a.key!r}: the key is <file>.<setting> (node.tags, gossip.interval, routing.max-hops, access.roles.risk.nodes, peers.<name>.url)", 2)
    text = _read(folder(a), fid) or ""
    try:
        m = (_yaml(cli).safe_load(text) if text.strip() else {}) or {}
    except Exception as e:  # noqa: BLE001
        raise cli.CliError(f"{fid} is not YAML: {e}", 1) from None
    at = m
    for p in path[:-1]:
        if not isinstance(at.get(p), dict):
            at[p] = {}
        at = at[p]
    leaf, value = path[-1], a.value
    if value is None or value == "":
        at.pop(leaf, None)
    elif leaf in LISTS:
        at[leaf] = [v.strip() for v in value.split(",") if v.strip()]
    elif value in ("true", "false"):
        at[leaf] = value == "true"
    elif re.fullmatch(r"-?\d{1,9}", value):
        at[leaf] = int(value)
    else:
        at[leaf] = value
    return _apply(cli, a, fid, _render(cli, fid, m))


def cmd_members(cli, a, extra) -> int:
    res = cli.Api.from_args(a).json("GET", "/api/v1/net/members")
    if a.json:
        cli.say_json(res)
        return 0
    if not res.get("running") and not res.get("members"):
        print("Drishti*net is off on this server (config/net/node.yaml does not say enabled: true)")
        return 0
    rows = res.get("members") or []
    print(f"{len(rows)} member(s); this node {res.get('self')}")
    headers = ["node", "state", "address", "version", "tags", "latency", "incarnation"]
    cells = [[m["nodeId"] + (" (this node)" if m.get("self") else "") + (" drained" if m.get("drained") else ""), m["state"], m.get("url", ""),
              m.get("version", ""), ",".join(m.get("tags") or []), "-" if m.get("latencyMs") is None else f"{m['latencyMs']} ms",
              str(m.get("incarnation", ""))] for m in rows]
    w = [max([len(h)] + [len(r[i]) for r in cells]) for i, h in enumerate(headers)]
    print("  " + "  ".join(h.ljust(w[i]) for i, h in enumerate(headers)).rstrip())
    for r in cells:
        print("  " + "  ".join(r[i].ljust(w[i]) for i in range(len(headers))).rstrip())
    return 0


def register(cli, sub, add, srv, js) -> None:
    """`sub` is the subparsers of the `net` group; `add` the parser factory of drishti.py; `srv` the server options; `js` the --json option."""
    def wrap(fn):
        return lambda a, extra: fn(cli, a, extra)

    def where(p, writes=False):
        p.add_argument("--dir", help="the config/net folder (default: DRISHTI_NET_DIR, then ./config/net)")
        if writes:
            p.add_argument("--data-dir", help="the server's data folder, for the history (default: DRISHTI_DATA_DIR, then ./data)")
        return p

    where(add(sub, "list", wrap(cmd_list), "net list: the files of config/net/ and what they say (on or off, this node, its seeds)", [js],
              "example:\n  drishti.py net list --dir /etc/drishti/net"))
    p = where(add(sub, "check", wrap(cmd_check), "net check: check the files as the server does; exit 1 when one would be refused", [js],
                  "examples:\n  drishti.py net check\n  drishti.py net check node.yaml peers/drishti-a.yaml --json"))
    p.add_argument("files", nargs="*", metavar="FILE", help="node.yaml, gossip.yaml, routing.yaml, access.yaml or peers/<name>.yaml (default: all)")
    p = where(add(sub, "add-peer", wrap(cmd_add_peer), "net add-peer: write peers/NAME.yaml, a seed this node joins through", [js],
                  "example:\n  drishti.py net add-peer drishti-a https://drishti-a.internal:8480 --tags region:emea"), True)
    p.add_argument("name")
    p.add_argument("url", help="host:port or an http(s) address")
    p.add_argument("--tags", help="the tags the peer is expected to carry, comma-separated")
    p = where(add(sub, "set", wrap(cmd_set), "net set: change one setting of a file (an empty value removes it)", [js],
                  "examples:\n  drishti.py net set node.enabled true\n  drishti.py net set node.secret '${DRISHTI_NET_SECRET}'\n"
                  "  drishti.py net set node.tags region:emea,tier:risk\n  drishti.py net set access.roles.risk.nodes n-a,region:emea\n"
                  "  drishti.py net set gossip.interval 2s"), True)
    p.add_argument("key", help="<file>.<setting>: node.tags, gossip.interval, routing.max-hops, access.roles.<role>.nodes, peers.<name>.url")
    p.add_argument("value", nargs="?", default="", help="the value; a list setting takes a comma-separated value; empty removes the setting")
    add(sub, "members", wrap(cmd_members), "net members: the member table of a running server (administrator)", [srv],
        "example:\n  drishti.py net members --server http://127.0.0.1:18783")
