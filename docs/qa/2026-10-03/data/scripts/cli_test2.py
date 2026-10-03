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

"""CLI test command with expect.yaml variants, hostile sample names, JUnit XML validity, vs API matrix."""
import os, re, json, shutil, xml.etree.ElementTree as ET
from cli_run import *
from qa import j
W = S + "/w2"; shutil.rmtree(W, ignore_errors=True)
base = open("base.sutra.yaml").read()
good = {"id": "T1", "mtm": 5, "coupon": 0.1, "qty": 3, "history": [{"date": "2026-01-01", "v": 1}], "legs": [{"name": "a", "amount": 1}]}
nolegs = {**good, "legs": []}
def pack(name, samples, expect=None, sutra=base):
    d = f"{W}/{name}"; os.makedirs(f"{d}/sutras/x", exist_ok=True); os.makedirs(f"{d}/tests/qa-edit", exist_ok=True)
    open(f"{d}/pack.yaml", "w").write(f"name: {name}\nversion: 1\n")
    open(f"{d}/sutras/x/qa-edit.v1.sutra.yaml", "w").write(sutra)
    for fn, doc in samples.items(): open(f"{d}/tests/qa-edit/{fn}", "w").write(json.dumps(doc) if not isinstance(doc, str) else doc)
    if expect is not None: open(f"{d}/tests/qa-edit/expect.yaml", "w").write(expect)
    return d
def run(label, d, *extra, want=None):
    rc, out = sutra("test", d, "--junit", d + "/junit.xml", *extra)
    jx = None
    try:
        r = ET.parse(d + "/junit.xml").getroot(); jx = (len(list(r.iter("testcase"))), len(list(r.iter("failure"))), len(list(r.iter("error"))), len(list(r.iter("skipped"))))
    except Exception as e: jx = "JUNIT-INVALID " + repr(e)[:80]
    first = [l for l in out.splitlines() if l.startswith(("ok", "FAIL", "fail", "skip", "sutra", "PROBLEM", "SKIP", "ERROR"))][:3]
    print(f"{label:34} exit={rc} want={want} junit(cases,fail,err,skip)={jx} | {first}"[:330])
run("ok default", pack("p1", {"g.json": good}), want=0)
run("nonEmpty met", pack("p2", {"g.json": good}, "nonEmpty: [legs]\n"), want=0)
run("nonEmpty unmet (empty legs)", pack("p3", {"e.json": nolegs}, "nonEmpty: [legs]\n"), want=1)
run("nonEmpty unknown panel", pack("p4", {"g.json": good}, "nonEmpty: [zzz]\n"), want="1 or 2")
run("unknown key", pack("p5", {"g.json": good}, "nonEmptyy: [legs]\n"), want=2)
run("expect yaml broken", pack("p6", {"g.json": good}, "nonEmpty: [legs\n"), want="2")
run("expect samples override", pack("p7", {"g.json": good, "e.json": nolegs}, "samples:\n  e.json: { nonEmpty: [legs] }\n"), want=1)
run("expect samples unknown file", pack("p8", {"g.json": good}, "samples:\n  zzz.json: { nonEmpty: [legs] }\n"), want="?")
run("noErrors false", pack("p9", {"g.json": good}, "noErrors: false\n"), want=0)
run("noErrors wrong type", pack("p10", {"g.json": good}, "noErrors: maybe\n"), want="2?")
run("nonEmpty as string", pack("p11", {"g.json": good}, "nonEmpty: legs\n"), want="?")
run("hostile sample names", pack("p12", {'a&b<c>"d\'.json': good, "é名.json": good, "sp ace.json": good}), want=0)
run("invalid JSON sample", pack("p13", {"bad.json": "{not json", "g.json": good}), want=1)
run("empty JSON file", pack("p14", {"empty.json": "", "g.json": good}), want="1")
run("JSON array root", pack("p15", {"arr.json": "[1,2]"}), want="?")
run("JSON scalar", pack("p16", {"n.json": "5"}), want="?")
run("deep JSON 200", pack("p17", {"deep.json": '{"a":' * 200 + "1" + "}" * 200}), want="?")
run("BOM sample", pack("p18", {"bom.json": "﻿" + json.dumps(good)}), want=0)
run("sutra error", pack("p19", {"g.json": good}, sutra=base.replace("kind: kv", "kind: nokind")), want=1)
run("no samples at all", pack("p20", {}), want="0 skipped")
run("jsonl sample", pack("p21", {"x.jsonl": json.dumps(good) + "\n" + json.dumps(nolegs) + "\n"}), want="?")
# junit unwritable on good input
rc, out = sutra("test", W + "/p1", "--junit", "/nonexistent/dir/x.xml"); print("junit unwritable on good pack: exit", rc, out.splitlines()[-1][:120] if out else "")
