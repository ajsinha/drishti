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

"""Tests of `drishti.py net list|check|add-peer|set|members`: the config/net/ folder checked as the server checks it (the same rules and
line numbers), writes that are atomic and keep a history, the key never in clear, and the member table of a (fake) running server."""
import json
import os
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_pack_deploy import Fake, ServerCase, run  # noqa: E402

KEY = "a-cluster-key-of-at-least-thirty-two-bytes"


class NetFolder(ServerCase):
    def setUp(self):
        super().setUp()
        self.dir = pathlib.Path(self.tmp.name) / "net"
        self.data = pathlib.Path(self.tmp.name) / "data"
        os.environ.pop("DRISHTI_NET_SECRET", None)

    def tearDown(self):
        os.environ.pop("DRISHTI_NET_SECRET", None)
        super().tearDown()

    def net(self, *argv):
        return run("net", *argv, "--dir", self.dir, *([] if argv[0] in ("list", "check") else ["--data-dir", self.data]))

    def test_without_the_folder_the_cluster_is_off(self):
        code, out, _ = self.net("list")
        self.assertEqual(0, code)
        self.assertIn("Drishti*net is off (a single server", out)
        self.assertIn("node.yaml", out)
        self.assertIn("absent (defaults)", out)
        self.assertFalse(self.dir.exists())

    def test_switching_on_needs_the_key_reference_and_the_address(self):
        code, _, err = self.net("set", "node.enabled", "true")
        self.assertEqual(1, code)
        self.assertIn("needs the cluster key", err)
        self.assertFalse((self.dir / "node.yaml").exists())                      # refused: nothing written
        code, _, err = self.net("set", "node.secret", KEY)
        self.assertEqual(1, code)
        self.assertIn("environment reference", err)
        os.environ["DRISHTI_NET_SECRET"] = KEY
        self.assertEqual(0, self.net("set", "node.secret", "${DRISHTI_NET_SECRET}")[0])
        self.assertEqual(0, self.net("set", "node.advertise-url", "http://192.0.2.10:18783")[0])
        self.assertEqual(0, self.net("set", "node.tags", "region:emea,tier:risk")[0])
        self.assertEqual(0, self.net("set", "node.enabled", "true")[0])
        text = (self.dir / "node.yaml").read_text()
        self.assertIn("${DRISHTI_NET_SECRET}", text)
        self.assertNotIn(KEY, text)
        self.assertIn("- region:emea", text)
        code, out, _ = self.net("list", "--json")
        listing = json.loads(out)
        self.assertTrue(listing["node"]["enabled"])
        self.assertEqual("set", listing["node"]["key"])
        self.assertEqual(["region:emea", "tier:risk"], listing["node"]["tags"])
        history = list((self.data / "net" / "history").glob("node.*.yaml"))
        self.assertGreaterEqual(len(history), 3)                                  # every earlier text kept
        self.assertFalse(any(p.name.startswith(".") for p in self.dir.rglob("*")))  # no hidden or temporary file left

    def test_a_peer_is_a_seed_file_and_a_bad_file_is_named_with_its_line(self):
        self.assertEqual(0, self.net("add-peer", "drishti-a", "192.0.2.11:18783", "--tags", "region:emea")[0])
        self.assertIn("url: http://192.0.2.11:18783", (self.dir / "peers" / "drishti-a.yaml").read_text())
        self.assertEqual(2, self.net("add-peer", "Drishti_A", "x:1")[0])
        (self.dir / "gossip.yaml").write_text("interval: 1s\nfanout: lots\nsurprise: 1\n")
        code, out, _ = self.net("check")
        self.assertEqual(1, code)
        self.assertIn("gossip.yaml:2", out)
        self.assertIn("fanout", out)
        self.assertIn("warning", out)
        code, out, _ = self.net("check", "peers/drishti-a.yaml", "--json")
        self.assertEqual(0, code)
        self.assertTrue(json.loads(out)["ok"])
        self.assertEqual(0, self.net("set", "access.roles.risk.nodes", "n-a,region:emea")[0])
        self.assertIn("- region:emea", (self.dir / "access.yaml").read_text())
        self.assertEqual(0, self.net("set", "peers.drishti-a.url", "http://192.0.2.11:18784")[0])
        self.assertIn("18784", (self.dir / "peers" / "drishti-a.yaml").read_text())
        self.assertEqual(2, self.net("set", "nowhere.x", "1")[0])
        (self.dir / "routing.yaml").write_text("this: [is not\n")
        code, out, _ = self.net("check", "routing.yaml")
        self.assertEqual(1, code)
        self.assertIn("is not YAML", out)

    def test_a_loopback_or_wildcard_address_is_refused_naming_this_machines_address(self):
        import hostaddr
        ip = hostaddr.primary_ipv4()
        for bad in ("http://127.0.0.1:18783", "http://localhost:18783", "http://0.0.0.0:18783", "http://[::1]:18783", "http://[::]:18783"):
            code, _, err = self.net("set", "node.advertise-url", bad)
            self.assertEqual(1, code, bad)
            if "[" in bad:
                continue                                                          # an IPv6 literal is not taken at all (as before)
            self.assertIn("loopback or wildcard", err)
            if ip:
                self.assertIn(f"http://{ip}:18783", err)
            code, _, err = self.net("add-peer", "drishti-a", bad)
            self.assertEqual(1, code, bad)
            self.assertIn("a peer's address", err)
        self.assertFalse((self.dir / "node.yaml").exists())
        self.assertFalse((self.dir / "peers" / "drishti-a.yaml").exists())
        (self.dir / "peers").mkdir(parents=True)
        (self.dir / "peers" / "drishti-b.yaml").write_text("url: http://127.0.0.2:18784\n")
        code, out, _ = self.net("check", "peers/drishti-b.yaml")
        self.assertEqual(1, code)
        self.assertIn("loopback or wildcard", out)

    def test_members_of_a_running_server(self):
        Fake.routes[("GET", "/api/v1/net/members")] = {"running": True, "self": "n-a", "members": [
            {"nodeId": "n-a", "self": True, "url": "http://127.0.0.1:18783", "state": "alive", "version": "2.5.0", "tags": ["region:emea"], "latencyMs": None, "incarnation": 1},
            {"nodeId": "n-b", "self": False, "url": "http://127.0.0.1:18784", "state": "suspect", "version": "2.5.0", "tags": [], "latencyMs": 2.5, "incarnation": 3, "drained": True}]}
        code, out, _ = run("net", "members", "--server", self.url)
        self.assertEqual(0, code)
        for text in ("2 member(s); this node n-a", "n-a (this node)", "n-b drained", "suspect", "2.5 ms", "region:emea"):
            self.assertIn(text, out)
        Fake.routes[("GET", "/api/v1/net/members")] = {"running": False, "members": []}
        self.assertIn("off on this server", run("net", "members", "--server", self.url)[1])


if __name__ == "__main__":
    unittest.main()
