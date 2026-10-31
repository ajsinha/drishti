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

"""The rolling upgrade runner (CD3, docs/architecture/CLUSTER_DEPLOYMENT.md section 10.1): a cluster upgraded one node at a time with no
downtime, on VMs (systemd, any shell commands) or on ECS (one service per node).

The order is fixed: **consoles**, then **BI and data-serving** members, then **coordinators**, then **shard owners one zone at a time**
(so a shard's other replica, in another zone, answers while its zone is upgraded). For each node: drain (a server: ``POST
/api/v1/admin/node/drain``; a console: the ``deregister`` command and ``console-drain`` seconds for its connections to close), wait until
it is safe to stop, stop, upgrade, start, wait until it is ready (a server: ``/actuator/health/readiness``; a console: ``/readyz``), then
the next node. On ECS stop, upgrade and start are one ``aws ecs update-service`` (the services stop the old task before starting the new).
Any step that fails stops the run, naming the node to resume from (``--from``). ``--dry-run`` prints every step and calls nothing.

    python3 tools/rolling.py --inventory cluster.yaml --version 2.9.7 --dry-run
    python3 tools/rolling.py --inventory cluster.yaml --version 2.9.7 [--from var-3] [--only var-1,var-2]

The inventory (YAML or JSON; ``deploy/rolling.example.yaml`` is a commented example)::

    platform: vm                       # vm | ecs
    waits: {drain: 600, ready: 1800, console-drain: 30}
    commands:                          # vm: shell commands; {node} {host} {url} {version} {role} {zone} are filled in
      stop: ssh {host} sudo systemctl stop drishti-server
      upgrade: ssh {host} sudo /opt/drishti/bin/install.sh {version}
      start: ssh {host} sudo systemctl start drishti-server
      console-stop: ssh {host} sudo systemctl stop drishti-console      # console-*: the consoles' own (else the same as the servers')
      deregister: ...                  # optional, consoles: take it out of the load balancer; register: put it back
    ecs: {cluster: drishti-prod, service: "{node}", task-definition: "drishti-prod-{node}", region: eu-west-2}
    nodes:
      - {name: console-1, role: console, url: "http://10.0.1.11:17480", host: 10.0.1.11}
      - {name: drishti-a, role: coordinator, url: "http://10.0.2.10:18480", host: 10.0.2.10}
      - {name: var-1, role: owner, zone: az-a, url: "http://10.0.3.11:18480", host: 10.0.3.11}

The token (an administrator's personal API token with the ``node:drain`` scope) comes from ``--token-file``, else ``DRISHTI_DRAIN_TOKEN``,
else ``DRISHTI_TOKEN``; it is never printed. ``tools/rolling-upgrade.sh`` and ``tools/windows/rolling-upgrade.ps1`` run this file.
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

ROLES = ("console", "bi", "data", "coordinator", "owner")
PHASES = (("consoles", ("console",)), ("BI and data-serving", ("bi", "data")), ("coordinators", ("coordinator",)), ("owners", ("owner",)))
WAITS = {"drain": 600, "ready": 1800, "console-drain": 30}
SERVER_STEPS = ("stop", "upgrade", "start")


class PlanError(ValueError):
    """The inventory cannot be planned: what is wrong, in words."""


@dataclass(frozen=True)
class Node:
    name: str
    role: str
    url: str
    zone: str = ""
    host: str = ""

    @property
    def console(self) -> bool:
        return self.role == "console"

    def fill(self, template: str, version: str) -> str:
        return template.format(node=self.name, host=self.host or self.name, url=self.url, version=version, role=self.role, zone=self.zone)


@dataclass(frozen=True)
class Step:
    """One step of the run: ``kind`` is drain, wait-safe, run (a command), wait-ready, pause or note."""
    node: str
    kind: str
    what: str
    command: str = ""
    seconds: int = 0


@dataclass
class Inventory:
    platform: str
    nodes: list[Node]
    commands: dict[str, str] = field(default_factory=dict)
    ecs: dict[str, str] = field(default_factory=dict)
    waits: dict[str, int] = field(default_factory=lambda: dict(WAITS))
    zones: list[str] = field(default_factory=list)

    @staticmethod
    def parse(doc: dict) -> "Inventory":
        if not isinstance(doc, dict):
            raise PlanError("the inventory is not a mapping")
        platform = str(doc.get("platform", "vm")).lower()
        if platform not in ("vm", "ecs"):
            raise PlanError(f"platform is {platform!r}: vm or ecs")
        nodes, seen = [], set()
        for i, n in enumerate(doc.get("nodes") or []):
            if not isinstance(n, dict) or not n.get("name") or not n.get("url"):
                raise PlanError(f"node {i + 1} needs a name and a url")
            role = str(n.get("role", "")).lower()
            if role not in ROLES:
                raise PlanError(f"node {n['name']}: role {role!r} is not one of {', '.join(ROLES)}")
            if n["name"] in seen:
                raise PlanError(f"node {n['name']} is listed twice")
            seen.add(n["name"])
            nodes.append(Node(str(n["name"]), role, str(n["url"]).rstrip("/"), str(n.get("zone") or ""), str(n.get("host") or "")))
        if not nodes:
            raise PlanError("the inventory lists no nodes")
        commands = {str(k): str(v) for k, v in (doc.get("commands") or {}).items() if v}
        ecs = {str(k): str(v) for k, v in (doc.get("ecs") or {}).items() if v}
        if platform == "vm":
            missing = [k for k in SERVER_STEPS if k not in commands]
            if missing:
                raise PlanError(f"platform vm needs commands: {', '.join(missing)}")
        elif not ecs.get("cluster"):
            raise PlanError("platform ecs needs ecs.cluster")
        waits = dict(WAITS)
        for k, v in (doc.get("waits") or {}).items():
            if k not in WAITS:
                raise PlanError(f"waits.{k} is not one of {', '.join(WAITS)}")
            waits[k] = int(v)
        return Inventory(platform, nodes, commands, ecs, waits, [str(z) for z in doc.get("zones") or []])

    @staticmethod
    def load(path: str) -> "Inventory":
        text = Path(path).read_text(encoding="utf-8")
        if path.endswith(".json"):
            return Inventory.parse(json.loads(text))
        import yaml                                                     # the tools' usual dependency (pyyaml)
        return Inventory.parse(yaml.safe_load(text))


def order(inv: Inventory) -> list[tuple[str, list[Node]]]:
    """The groups in upgrade order: consoles, BI and data, coordinators, then the owners zone by zone (the inventory's ``zones:`` order,
    else by name; owners without a zone last). Within a group, the inventory's order."""
    groups: list[tuple[str, list[Node]]] = []
    for title, roles in PHASES[:3]:
        members = [n for n in inv.nodes if n.role in roles]
        if members:
            groups.append((title, members))
    owners = [n for n in inv.nodes if n.role == "owner"]
    zones = list(inv.zones) + sorted({n.zone for n in owners if n.zone and n.zone not in inv.zones})
    for z in zones:
        members = [n for n in owners if n.zone == z]
        if members:
            groups.append((f"owners in zone {z}", members))
    rest = [n for n in owners if not n.zone or n.zone not in zones]
    if rest:
        groups.append(("owners without a zone", rest))
    return groups


def _ecs(inv: Inventory, node: Node, version: str) -> tuple[str, str]:
    """The update of the node's service, and the wait until ECS has replaced its task (the old one gone: its readiness says nothing)."""
    e = inv.ecs
    region = f" --region {shlex.quote(e['region'])}" if e.get("region") else ""
    cluster = shlex.quote(e["cluster"])
    service = shlex.quote(node.fill(e.get("console-service" if node.console else "service", "{node}"), version))
    task = node.fill(e.get("console-task-definition" if node.console else "task-definition", "drishti-{node}"), version)
    return (f"aws ecs update-service{region} --cluster {cluster} --service {service} --task-definition {shlex.quote(task)} --force-new-deployment",
            f"aws ecs wait services-stable{region} --cluster {cluster} --services {service}")


def steps(inv: Inventory, node: Node, version: str) -> list[Step]:
    """One node's steps, in order."""
    out: list[Step] = []
    cmd = inv.commands
    if node.console:
        if cmd.get("deregister"):
            out.append(Step(node.name, "run", "take it out of the load balancer", node.fill(cmd["deregister"], version)))
        out.append(Step(node.name, "pause", "let its connections close", seconds=inv.waits["console-drain"]))
    else:
        out.append(Step(node.name, "drain", "drain (forwarded work and shards go to other members)"))
        out.append(Step(node.name, "wait-safe", "wait until it is safe to stop", seconds=inv.waits["drain"]))
    if inv.platform == "ecs":
        update, stable = _ecs(inv, node, version)
        out.append(Step(node.name, "run", "stop, upgrade and start (a new task of the new task definition)", update))
        out.append(Step(node.name, "run", "wait until ECS has replaced the task", stable))
    else:
        for s in SERVER_STEPS:
            template = cmd.get(f"console-{s}", cmd[s]) if node.console else cmd[s]
            out.append(Step(node.name, "run", s, node.fill(template, version)))
    out.append(Step(node.name, "wait-ready", "wait until it is ready", seconds=inv.waits["ready"]))
    if node.console and cmd.get("register"):
        out.append(Step(node.name, "run", "put it back in the load balancer", node.fill(cmd["register"], version)))
    return out


def plan(inv: Inventory, version: str, start: str | None = None, only: set[str] | None = None) -> list[tuple[str, Node, list[Step]]]:
    """Every node to upgrade with its group and steps; ``start`` skips the nodes before it, ``only`` keeps the named ones."""
    names = {n.name for n in inv.nodes}
    for n in ([start] if start else []) + sorted(only or ()):
        if n not in names:
            raise PlanError(f"no node {n} in the inventory")
    out, started = [], start is None
    for title, members in order(inv):
        for node in members:
            started = started or node.name == start
            if started and (not only or node.name in only):
                out.append((title, node, steps(inv, node, version)))
    return out


# --------------------------------------------------------------------------------------------------------------------------- running

def _token(path: str | None) -> str:
    if path:
        return Path(path).expanduser().read_text(encoding="utf-8").strip()
    return os.environ.get("DRISHTI_DRAIN_TOKEN") or os.environ.get("DRISHTI_TOKEN") or ""


def http(method: str, url: str, token: str, body: dict | None = None, timeout: float = 10) -> tuple[int, dict]:
    """The answer's status and JSON body (an empty dict when it has none); status 0 when the server did not answer."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)        # noqa: S310 - the operator's own inventory
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:        # noqa: S310
            raw = r.read()
            return r.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except ValueError:
            return e.code, {}
    except (urllib.error.URLError, OSError, ValueError):
        return 0, {}


def shell(command: str) -> int:
    return subprocess.run(command, shell=True, check=False).returncode   # noqa: S602 - the operator's own commands, as written


class Failed(RuntimeError):
    pass


class Runner:
    """Runs a plan; the calls, commands, clock and sleep are injected so the tests run it without a cluster."""

    def __init__(self, token: str = "", call: Callable = http, run: Callable[[str], int] = shell, sleep: Callable[[float], None] = time.sleep,
                 clock: Callable[[], float] = time.monotonic, say: Callable[[str], None] = print, poll: float = 5):
        self.token, self.call, self.run_cmd, self.sleep, self.clock, self.say, self.poll = token, call, run, sleep, clock, say, poll

    def step(self, node: Node, s: Step) -> None:
        if s.kind == "drain":
            st, body = self.call("POST", node.url + "/api/v1/admin/node/drain", self.token, {"on": True})
            if st != 200:
                raise Failed(f"the drain was refused ({st or 'no answer'}{': ' + body.get('detail', '') if body.get('detail') else ''})")
        elif s.kind == "wait-safe":
            self._until(s.seconds, lambda: self._safe(node), "not safe to stop")
        elif s.kind == "run":
            rc = self.run_cmd(s.command)
            if rc != 0:
                raise Failed(f"'{s.what}' exited {rc}")
        elif s.kind == "pause":
            self.sleep(s.seconds)
        elif s.kind == "wait-ready":
            path = "/readyz" if node.console else "/actuator/health/readiness"
            self._until(s.seconds, lambda: self.call("GET", node.url + path, "")[0] == 200, "not ready")

    def _safe(self, node: Node) -> bool:
        st, body = self.call("GET", node.url + "/api/v1/admin/node", self.token)
        return st == 200 and body.get("safeToStop") is True

    def _until(self, seconds: int, test: Callable[[], bool], why: str) -> None:
        end = self.clock() + seconds
        while not test():
            if self.clock() >= end:
                raise Failed(f"{why} after {seconds} s")
            self.sleep(self.poll)

    def run(self, work: list[tuple[str, Node, list[Step]]]) -> int:
        group = None
        for i, (title, node, ss) in enumerate(work):
            if title != group:
                group = title
                self.say(f"== {title}")
            self.say(f"-- {node.name} ({node.role}{', zone ' + node.zone if node.zone else ''}), {i + 1} of {len(work)}")
            for s in ss:
                self.say(f"   {s.what}" + (f": {s.command}" if s.command else ""))
                try:
                    self.step(node, s)
                except Failed as e:
                    self.say(f"STOPPED at {node.name}, {s.what}: {e}")
                    if s.kind in ("wait-safe", "run") and not node.console:
                        self.say(f"   {node.name} may still be drained: drishti-node.sh undrain (DRISHTI_NODE_URL={node.url}), or resume with "
                                 f"--from {node.name}")
                    else:
                        self.say(f"   resume with --from {node.name}")
                    return 1
        self.say(f"done: {len(work)} node(s) upgraded, one at a time")
        return 0


def describe(work: list[tuple[str, Node, list[Step]]], inv: Inventory, version: str) -> str:
    """The plan in words, every command as it would run (--dry-run)."""
    lines = [f"rolling upgrade to {version or '(no version given)'} on {inv.platform}: {len(work)} node(s), one at a time"]
    group = None
    for title, node, ss in work:
        if title != group:
            group = title
            lines.append(f"== {title}")
        lines.append(f"-- {node.name} ({node.role}{', zone ' + node.zone if node.zone else ''}) at {node.url}")
        for s in ss:
            if s.kind == "drain":
                lines.append(f"   POST {node.url}/api/v1/admin/node/drain {{\"on\": true}}")
            elif s.kind == "wait-safe":
                lines.append(f"   wait up to {s.seconds} s for safeToStop (GET {node.url}/api/v1/admin/node)")
            elif s.kind == "pause":
                lines.append(f"   wait {s.seconds} s: {s.what}")
            elif s.kind == "wait-ready":
                lines.append(f"   wait up to {s.seconds} s for GET {node.url}{'/readyz' if node.console else '/actuator/health/readiness'} = 200")
            else:
                lines.append(f"   {s.what}: {s.command}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="rolling.py", description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog="docs/architecture/CLUSTER_DEPLOYMENT.md section 10.1; docs/admin/OPERATIONS.md, Rolling upgrades")
    p.add_argument("--inventory", required=True, help="the cluster's nodes and commands (YAML or JSON; deploy/rolling.example.yaml)")
    p.add_argument("--version", default="", help="the version to install, filled in as {version}")
    p.add_argument("--dry-run", action="store_true", help="print every step and call nothing")
    p.add_argument("--from", dest="start", help="resume from this node (the nodes before it are skipped)")
    p.add_argument("--only", help="only these nodes (comma-separated), still in the upgrade order")
    p.add_argument("--token-file", help="a file holding the token (default DRISHTI_DRAIN_TOKEN, else DRISHTI_TOKEN); never printed")
    a = p.parse_args(argv)
    try:
        inv = Inventory.load(a.inventory)
        work = plan(inv, a.version, a.start, {x.strip() for x in a.only.split(",") if x.strip()} if a.only else None)
    except (PlanError, OSError, ValueError) as e:
        print(f"rolling.py: {e}", file=sys.stderr)
        return 2
    if a.dry_run:
        print(describe(work, inv, a.version))
        return 0
    return Runner(_token(a.token_file)).run(work)


if __name__ == "__main__":
    sys.exit(main())
