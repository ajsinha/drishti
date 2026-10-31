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

"""The rolling upgrade runner (CD3, tools/rolling.py): the order (consoles, BI and data, coordinators, owners zone by zone), each node's
steps on VMs and on ECS, resuming and choosing nodes, the dry run (every step printed, nothing called), and a run against fake nodes:
one node at a time, each drained, waited for, stopped, upgraded, started and waited for until ready; a failure stops the run.

    python3 -m unittest -q tools/test_rolling.py
"""
from __future__ import annotations

import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rolling  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]

DOC = {
    "platform": "vm",
    "waits": {"drain": 60, "ready": 120, "console-drain": 5},
    "zones": ["az-b", "az-a"],
    "commands": {"stop": "stop {node}", "upgrade": "install {version} on {host}", "start": "start {node}",
                 "console-upgrade": "console-install {version} on {host}", "deregister": "lb-out {host}", "register": "lb-in {host}"},
    "nodes": [
        {"name": "var-1", "role": "owner", "zone": "az-a", "url": "http://10.0.3.11:18480", "host": "10.0.3.11"},
        {"name": "coord-1", "role": "coordinator", "url": "http://10.0.2.11:18480"},
        {"name": "var-3", "role": "owner", "zone": "az-b", "url": "http://10.0.4.11:18480"},
        {"name": "console-1", "role": "console", "url": "http://10.0.1.11:17480", "host": "10.0.1.11"},
        {"name": "data-1", "role": "data", "url": "http://10.0.2.31:18480"},
        {"name": "var-2", "role": "owner", "zone": "az-a", "url": "http://10.0.3.12:18480"},
        {"name": "bi-1", "role": "bi", "url": "http://10.0.2.21:18480"},
        {"name": "var-9", "role": "owner", "url": "http://10.0.5.11:18480"},
    ],
}


class Fake:
    """Nodes that answer: a drain makes a server safe to stop after ``safe_after`` polls; a started node is ready after ``ready_after``."""

    def __init__(self, safe_after: int = 2, ready_after: int = 2, fail_command: str = ""):
        self.log: list[str] = []
        self.polls: dict[str, int] = {}
        self.safe_after, self.ready_after, self.fail_command = safe_after, ready_after, fail_command
        self.now = 0.0

    def call(self, method, url, token, body=None):
        self.log.append(f"{method} {url}" + (f" {json.dumps(body)}" if body is not None else ""))
        key = url.split("/api/")[0].split("/actuator")[0].split("/readyz")[0]
        if url.endswith("/drain"):
            self.polls[key + "|safe"] = 0
            return 200, {"drained": True, "safeToStop": False}
        if url.endswith("/api/v1/admin/node"):
            self.polls[key + "|safe"] += 1
            return 200, {"safeToStop": self.polls[key + "|safe"] >= self.safe_after}
        n = self.polls.get(key + "|ready", 0) + 1
        self.polls[key + "|ready"] = n
        return (200 if n >= self.ready_after else 503), {}

    def run(self, command):
        self.log.append("$ " + command)
        return 1 if self.fail_command and command == self.fail_command else 0

    def sleep(self, s):
        self.now += s

    def clock(self):
        return self.now


class Planning(unittest.TestCase):
    def test_the_order_is_consoles_bi_and_data_coordinators_then_owners_zone_by_zone(self):
        inv = rolling.Inventory.parse(DOC)
        groups = [(t, [n.name for n in ns]) for t, ns in rolling.order(inv)]
        self.assertEqual(groups, [("consoles", ["console-1"]), ("BI and data-serving", ["data-1", "bi-1"]), ("coordinators", ["coord-1"]),
                                  ("owners in zone az-b", ["var-3"]), ("owners in zone az-a", ["var-1", "var-2"]),
                                  ("owners without a zone", ["var-9"])])

    def test_zones_not_listed_follow_by_name(self):
        doc = {**DOC, "zones": []}
        titles = [t for t, _ in rolling.order(rolling.Inventory.parse(doc))]
        self.assertEqual(titles[3:], ["owners in zone az-a", "owners in zone az-b", "owners without a zone"])

    def test_a_server_is_drained_waited_for_stopped_upgraded_started_and_waited_for(self):
        inv = rolling.Inventory.parse(DOC)
        node = next(n for n in inv.nodes if n.name == "var-1")
        ss = rolling.steps(inv, node, "2.9.7")
        self.assertEqual([s.kind for s in ss], ["drain", "wait-safe", "run", "run", "run", "wait-ready"])
        self.assertEqual([s.command for s in ss if s.kind == "run"], ["stop var-1", "install 2.9.7 on 10.0.3.11", "start var-1"])
        self.assertEqual(ss[1].seconds, 60)
        self.assertEqual(ss[-1].seconds, 120)

    def test_a_console_leaves_the_load_balancer_and_uses_its_own_commands_where_given(self):
        inv = rolling.Inventory.parse(DOC)
        node = next(n for n in inv.nodes if n.console)
        ss = rolling.steps(inv, node, "2.9.7")
        self.assertEqual([s.kind for s in ss], ["run", "pause", "run", "run", "run", "wait-ready", "run"])
        self.assertEqual([s.command for s in ss if s.kind == "run"], ["lb-out 10.0.1.11", "stop console-1", "console-install 2.9.7 on 10.0.1.11",
                                                                      "start console-1", "lb-in 10.0.1.11"])

    def test_on_ecs_stop_upgrade_and_start_are_one_service_update_then_the_wait_for_the_new_task(self):
        doc = {"platform": "ecs", "ecs": {"cluster": "drishti-prod", "region": "eu-west-2", "task-definition": "drishti-prod-{node}"},
               "nodes": [{"name": "var-1", "role": "owner", "zone": "az-a", "url": "http://var-1.drishti.local:18480"}]}
        inv = rolling.Inventory.parse(doc)
        ss = rolling.steps(inv, inv.nodes[0], "2.9.7")
        self.assertEqual([s.kind for s in ss], ["drain", "wait-safe", "run", "run", "wait-ready"])
        self.assertEqual(ss[2].command, "aws ecs update-service --region eu-west-2 --cluster drishti-prod --service var-1 "
                                        "--task-definition drishti-prod-var-1 --force-new-deployment")
        self.assertEqual(ss[3].command, "aws ecs wait services-stable --region eu-west-2 --cluster drishti-prod --services var-1")

    def test_resume_and_only_keep_the_order(self):
        inv = rolling.Inventory.parse(DOC)
        self.assertEqual([n.name for _, n, _ in rolling.plan(inv, "v", start="coord-1")], ["coord-1", "var-3", "var-1", "var-2", "var-9"])
        self.assertEqual([n.name for _, n, _ in rolling.plan(inv, "v", only={"var-2", "console-1"})], ["console-1", "var-2"])
        with self.assertRaises(rolling.PlanError):
            rolling.plan(inv, "v", start="nope")

    def test_a_bad_inventory_says_what_is_wrong(self):
        cases = [({"nodes": []}, "no nodes"), ({**DOC, "platform": "k8s"}, "vm or ecs"),
                 ({**DOC, "commands": {"stop": "x"}}, "upgrade, start"), ({"platform": "ecs", "nodes": DOC["nodes"]}, "ecs.cluster"),
                 ({**DOC, "nodes": [{"name": "x", "role": "boss", "url": "http://h:1"}]}, "role 'boss'"),
                 ({**DOC, "nodes": [DOC["nodes"][0], DOC["nodes"][0]]}, "twice"), ({**DOC, "waits": {"nap": 1}}, "waits.nap")]
        for doc, words in cases:
            with self.subTest(words), self.assertRaises(rolling.PlanError) as e:
                rolling.Inventory.parse(doc)
            self.assertIn(words, str(e.exception))

    def test_the_example_inventory_plans(self):
        inv = rolling.Inventory.load(str(ROOT / "deploy" / "rolling.example.yaml"))
        work = rolling.plan(inv, "2.9.7")
        self.assertEqual([n.role for _, n, _ in work][:2], ["console", "console"])
        self.assertEqual([n.zone for _, n, _ in work][-4:], ["az-a", "az-a", "az-b", "az-b"])
        for _, n, _ in work:
            self.assertNotRegex(n.url, r"//(localhost|127\.|0\.0\.0\.0)", "a real address, never localhost")


class Running(unittest.TestCase):
    def test_the_dry_run_prints_every_step_and_calls_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            inv = Path(d, "c.json")
            inv.write_text(json.dumps(DOC), encoding="utf-8")
            out = io.StringIO()
            with redirect_stdout(out):
                rc = rolling.main(["--inventory", str(inv), "--version", "2.9.7", "--dry-run"])
        self.assertEqual(rc, 0)
        text = out.getvalue()
        self.assertIn("8 node(s), one at a time", text)
        self.assertIn("POST http://10.0.3.11:18480/api/v1/admin/node/drain", text)
        self.assertIn("install 2.9.7 on 10.0.3.11", text)
        self.assertIn("GET http://10.0.1.11:17480/readyz", text)
        self.assertLess(text.index("console-1"), text.index("coord-1"))
        self.assertLess(text.index("var-3"), text.index("var-1"))

    def test_one_node_at_a_time_each_drained_safe_stopped_upgraded_started_and_ready_before_the_next(self):
        inv = rolling.Inventory.parse({**DOC, "nodes": [DOC["nodes"][0], DOC["nodes"][1]]})
        f = Fake()
        said: list[str] = []
        rc = rolling.Runner("tok", f.call, f.run, f.sleep, f.clock, said.append, poll=1).run(rolling.plan(inv, "2.9.7"))
        self.assertEqual(rc, 0, said)
        lines = [x for x in f.log if "coord-1" in x or "10.0.2.11" in x]
        self.assertEqual(lines[0], 'POST http://10.0.2.11:18480/api/v1/admin/node/drain {"on": true}')
        self.assertEqual(lines[1:3], ["GET http://10.0.2.11:18480/api/v1/admin/node"] * 2)
        self.assertEqual(lines[3:6], ["$ stop coord-1", "$ install 2.9.7 on coord-1", "$ start coord-1"])
        self.assertEqual(lines[6:8], ["GET http://10.0.2.11:18480/actuator/health/readiness"] * 2)
        first_var = next(i for i, x in enumerate(f.log) if "10.0.3.11" in x)
        self.assertGreater(first_var, f.log.index(lines[-1]), "the owner only after the coordinator is ready")

    def test_a_node_never_safe_to_stop_stops_the_run_before_it_is_stopped(self):
        inv = rolling.Inventory.parse({**DOC, "nodes": [DOC["nodes"][1], DOC["nodes"][0]]})
        f = Fake(safe_after=10_000)
        said: list[str] = []
        rc = rolling.Runner("", f.call, f.run, f.sleep, f.clock, said.append, poll=5).run(rolling.plan(inv, "2.9.7"))
        self.assertEqual(rc, 1)
        self.assertFalse([x for x in f.log if x.startswith("$ ")], "nothing stopped")
        self.assertTrue(any("STOPPED at coord-1" in s and "not safe to stop after 60 s" in s for s in said), said)
        self.assertTrue(any("undrain" in s and "--from coord-1" in s for s in said), said)
        self.assertFalse(any("10.0.3.11" in x for x in f.log), "the next node is not touched")

    def test_a_failed_command_or_a_node_never_ready_stops_the_run(self):
        inv = rolling.Inventory.parse({**DOC, "nodes": [DOC["nodes"][1]]})
        f = Fake(fail_command="install 2.9.7 on coord-1")
        said: list[str] = []
        self.assertEqual(rolling.Runner("", f.call, f.run, f.sleep, f.clock, said.append).run(rolling.plan(inv, "2.9.7")), 1)
        self.assertNotIn("$ start coord-1", f.log)
        g = Fake(ready_after=10_000)
        said = []
        self.assertEqual(rolling.Runner("", g.call, g.run, g.sleep, g.clock, said.append, poll=5).run(rolling.plan(inv, "2.9.7")), 1)
        self.assertTrue(any("not ready after 120 s" in s for s in said), said)

    def test_the_shell_wrapper_runs_the_runner(self):
        with tempfile.TemporaryDirectory() as d:
            inv = Path(d, "c.json")
            inv.write_text(json.dumps(DOC), encoding="utf-8")
            r = subprocess.run(["bash", "-n", str(ROOT / "tools" / "rolling-upgrade.sh")], capture_output=True, text=True, check=False)
            self.assertEqual(r.returncode, 0, r.stderr)
            r = subprocess.run([sys.executable, str(ROOT / "tools" / "rolling.py"), "--inventory", str(inv), "--dry-run"],
                               capture_output=True, text=True, check=False)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("== owners in zone az-b", r.stdout)


class StopHook(unittest.TestCase):
    """deploy/bin/drishti-node.sh: POSIX sh, its help needs nothing, a drain against a server that does not answer gives up at once."""

    SCRIPT = ROOT / "deploy" / "bin" / "drishti-node.sh"

    def test_it_parses_and_helps(self):
        for sh in ("sh", "bash"):
            r = subprocess.run([sh, "-n", str(self.SCRIPT)], capture_output=True, text=True, check=False)
            self.assertEqual(r.returncode, 0, r.stderr)
        r = subprocess.run([str(self.SCRIPT), "help"], capture_output=True, text=True, check=False)
        self.assertEqual(r.returncode, 0)
        self.assertIn("drain [--wait SECONDS]", r.stdout)
        self.assertEqual(subprocess.run([str(self.SCRIPT), "nonsense"], capture_output=True, check=False).returncode, 2)

    def test_a_drain_nobody_answers_lets_the_stop_go_on(self):
        env = {"PATH": "/usr/bin:/bin", "DRISHTI_NODE_URL": "http://127.0.0.1:9", "DRISHTI_DRAIN_WAIT": "1"}
        r = subprocess.run([str(self.SCRIPT), "drain"], capture_output=True, text=True, env=env, timeout=30, check=False)
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertIn("stopping without it", r.stderr)

    def test_run_passes_a_stop_on_after_the_drain_and_keeps_the_exit_code(self):
        env = {"PATH": "/usr/bin:/bin", "DRISHTI_NODE_URL": "http://127.0.0.1:9", "DRISHTI_DRAIN_WAIT": "1"}
        p = subprocess.Popen([str(self.SCRIPT), "run", "sh", "-c", "trap 'exit 143' TERM; while :; do sleep 0.1; done"], env=env,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        import time
        time.sleep(0.5)
        p.terminate()
        _, err = p.communicate(timeout=30)
        self.assertEqual(p.returncode, 143, err)
        self.assertIn("stopping without it", err)                     # the drain was tried first


if __name__ == "__main__":
    unittest.main()
