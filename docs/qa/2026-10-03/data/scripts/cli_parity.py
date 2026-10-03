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

"""CLI vs API for the same inputs: lint (codes+lines), shape (schema+roles), design (yaml), test (matrix statuses), exit codes, JUnit validity."""
import os, re, json, shutil, glob, xml.etree.ElementTree as ET
from cli_run import *
from qa import j
W = S + "/w"; shutil.rmtree(W, ignore_errors=True); os.makedirs(W + "/bad")
E = "/home/ashutosh/IdeaProjects/drishti/docs/guides/examples/"
base = open("base.sutra.yaml").read()
bads = {"syntax": "rachana: 1\nsutra: [x\n", "unknownkey": base.replace("version: 1", "version: 1\nbogus: 1"), "dupkey": base + "\nsutra: again\n", "noversion": base.replace("rachana: 1\n", ""), "emptyfile": "", "badexpr": base.replace("$.mtm, fmt", "$.mtm +, fmt"), "tabs": "rachana: 1\n\tsutra: x\n", "badpanel": base.replace("kind: kv", "kind: nokind"), "badopt": base.replace("span: 8", "span: 99")}
for k, v in bads.items(): open(f"{W}/bad/{k}.sutra.yaml", "w").write(v)
# ---- lint parity
rc, out = sutra("lint", W + "/bad")
cli = set()
for l in out.splitlines():
    m = re.match(r".*/bad/(\w+)\.sutra\.yaml:(\d+) (DRS-\d+)", l)
    if m: cli.add((m.group(1), int(m.group(2)), m.group(3)))
api = set()
for k, v in bads.items():
    st, o = j("POST", "/api/v1/builder/check", body={"yaml": v, "samples": [{"name": "s", "document": {}}]})
    det = o.get("detail", "") if isinstance(o, dict) else ""
    for m in re.finditer(r"studio\.sutra\.yaml:(\d+):\d+ (DRS-\d+)", det): api.add((k, int(m.group(1)), m.group(2)))
    if st == 200: print("API accepted", k)
print("lint exit", rc, "| in CLI not API:", sorted(cli - api), "| in API not CLI:", sorted(api - cli), "| common", len(cli & api))
# ---- exit codes
for args, want in [(["lint"], 2), (["bogus", "x"], 2), (["lint", W + "/nope"], 2), (["lint", E + "pnl-explain.sutra.yaml"], 0), (["test", E + "pnl-explain.sutra.yaml", "--bogus"], 2), (["lint", W + "/bad", "--junit", "/nonexistent/dir/x.xml"], None), ([], 2), (["lint", E, "--out"], 2)]:
    rc, out = sutra(*args); print("exit", rc, "want", want, args[:3], "|", out.splitlines()[0][:100] if out else "")
# ---- test: examples
rc, out = sutra("test", E, "--junit", W + "/junit.xml"); print("test examples exit", rc, out.splitlines()[-3:] if out else "")
t = ET.parse(W + "/junit.xml").getroot(); print("junit root", t.tag, t.attrib, "cases", len(list(t.iter("testcase"))), "failures", len(list(t.iter("failure"))), "skipped", len(list(t.iter("skipped"))))
# api matrix for each example
for f in sorted(glob.glob(E + "*.sutra.yaml")):
    n = os.path.basename(f)[:-11]; y = open(f).read(); import yaml; kind = yaml.safe_load(y)["match"]["kind"]
    st, c = j("POST", "/api/v1/builder/check", body={"yaml": y, "kind": kind, "samples": [{"name": n + ".json", "document": json.load(open(E + n + ".json"))}]})
    cli_cases = [tc for tc in t.iter("testcase") if n in (tc.get("classname", "") + tc.get("name", ""))]
    cli_fail = sum(1 for tc in cli_cases if tc.find("failure") is not None or tc.find("error") is not None)
    print(f"  {n}: api ok={c.get('ok')} counts={c.get('counts')} | cli cases={len(cli_cases)} failed={cli_fail}")
