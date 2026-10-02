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

"""Fuzz Sutras through the server's Studio preview (POST /api/v1/studio/preview) and report status + DRS codes."""
import json, re, sys, urllib.request

SRV = "http://127.0.0.1:18985/api/v1/studio/preview"
REPO = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-ux/fresh"
BASE = open(REPO + "/packs/trading/sutras/rates/irs-fixfloat.v1.sutra.yaml", encoding="utf-8").read()
BASE = BASE.replace("sutra: irs-fixfloat", "sutra: qa-fuzz")
HEAD = "rachana: 1\nsutra: qa-fuzz\nversion: 1\nmatch: { kind: trade }\ntitle: { id: $.tradeId }\n"


def panel(p):
    return HEAD + "panels:\n" + "\n".join("  " + l for l in p.strip("\n").split("\n")) + "\n"


def run(name, yaml, kind="trade", id_="MX-20000001", raw=None):
    body = raw if raw is not None else json.dumps({"yaml": yaml, "kind": kind, "id": id_}).encode()
    req = urllib.request.Request(SRV, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            st, txt = r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        st, txt = e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        st, txt = -1, repr(e)
    codes = sorted(set(re.findall(r"DRS-\d{4}", txt)))
    msg = ""
    try:
        j = json.loads(txt)
        if isinstance(j, dict) and ("problems" in j or "message" in j or "detail" in j):
            msg = json.dumps({k: j[k] for k in ("code", "message", "detail", "problems") if k in j})[:600]
        else:
            # a view model: count panels and panel errors
            ps = j.get("panels", []) if isinstance(j, dict) else []
            errs = [(p.get("id"), p.get("error") or p.get("reason")) for p in ps if isinstance(p, dict) and (p.get("error") or p.get("reason"))]
            msg = "VIEW panels=%d errs=%s" % (len(ps), json.dumps(errs)[:400])
    except Exception:
        msg = txt[:300].replace("\n", " ")
    flag = "!!" if st >= 500 or st < 0 or (st >= 400 and not codes) else "  "
    print(f"{flag} {st:4} {','.join(codes):22} {name:38} {msg}", flush=True)
    return st, codes, txt


CASES = []
def case(n, y, **kw): CASES.append((n, y, kw))

case("valid base", BASE)
case("empty", "")
case("only whitespace", "   \n\t\n")
case("not a mapping (list)", "- a\n- b\n")
case("scalar", "hello")
case("null doc", "~")
case("missing rachana", BASE.replace("rachana: 1\n", ""))
case("rachana 2", BASE.replace("rachana: 1", "rachana: 2"))
case("rachana string", BASE.replace("rachana: 1", "rachana: one"))
case("rachana null", BASE.replace("rachana: 1", "rachana:"))
case("missing sutra", BASE.replace("sutra: qa-fuzz\n", ""))
case("missing version", BASE.replace("version: 1\n", ""))
case("version 0", BASE.replace("version: 1", "version: 0"))
case("version -3", BASE.replace("version: 1", "version: -3"))
case("version 1.5", BASE.replace("version: 1", "version: 1.5"))
case("version huge", BASE.replace("version: 1", "version: 99999999999999999999"))
case("version string", BASE.replace("version: 1", "version: abc"))
case("version list", BASE.replace("version: 1", "version: [1]"))
case("name uppercase", BASE.replace("sutra: qa-fuzz", "sutra: QA_Fuzz"))
case("name 200 chars", BASE.replace("sutra: qa-fuzz", "sutra: " + "a" * 200))
case("name unicode", BASE.replace("sutra: qa-fuzz", "sutra: दृष्टि"))
case("name list", BASE.replace("sutra: qa-fuzz", "sutra: [a, b]"))
case("missing match", re.sub(r"match:.*\n", "", BASE))
case("match not mapping", re.sub(r"match:.*\n", "match: trade\n", BASE))
case("match kind number", re.sub(r"match:.*\n", "match: { kind: 42 }\n", BASE))
case("match where bad expr", re.sub(r"match:.*\n", "match: { kind: trade, where: \"$.a ==\" }\n", BASE))
case("match priority string", re.sub(r"match:.*\n", "match: { kind: trade, priority: high }\n", BASE))
case("match priority huge", re.sub(r"match:.*\n", "match: { kind: trade, priority: 1e99 }\n", BASE))
case("match unknown key", re.sub(r"match:.*\n", "match: { kind: trade, colour: red }\n", BASE))
case("unknown top key", BASE + "pannels: []\n")
case("panels mapping", HEAD + "panels: { a: 1 }\n")
case("panels null", HEAD + "panels:\n")
case("panels empty", HEAD + "panels: []\n")
case("panel not mapping", HEAD + "panels:\n  - hello\n")
case("panel list in list", HEAD + "panels:\n  - [1,2]\n")
case("panel no kind", panel("- { id: a, title: A }"))
case("panel kind number", panel("- { id: a, kind: 5 }"))
case("unknown kind chart", panel("- { id: a, kind: chart }"))
case("kind uppercase KV", panel("- { id: a, kind: KV }"))
case("no id", panel("- { kind: provenance }"))
case("dup id", panel("- { id: a, kind: provenance }\n- { id: a, kind: links }"))
case("id unicode", panel("- { id: 'ü-ä', kind: provenance }"))
case("id empty", panel("- { id: '', kind: provenance }"))
case("title 10k chars", panel("- { id: a, kind: markdown, text: hi, title: '" + "W" * 10000 + "' }"))
case("title html", panel("- { id: a, kind: markdown, text: hi, title: '<script>alert(1)</script>' }"))
case("title emoji rtl", panel("- { id: a, kind: markdown, text: hi, title: 'شاشة 🚀 \u202e evil' }"))
case("markdown html text", panel("- { id: a, kind: markdown, text: '<img src=x onerror=alert(1)> **b**' }"))
case("markdown 1MB text", panel("- { id: a, kind: markdown, text: '" + "x" * 1000000 + "' }"))
case("markdown template err", panel("- { id: a, kind: markdown, text: 'v ${$.mtm +}' }"))
case("markdown text list", panel("- { id: a, kind: markdown, text: [1,2] }"))
case("key F13", panel("- { id: a, kind: provenance, key: F13 }"))
case("key F0", panel("- { id: a, kind: provenance, key: F0 }"))
case("key lowercase f2", panel("- { id: a, kind: provenance, key: f2 }"))
case("key twice", panel("- { id: a, kind: provenance, key: F2 }\n- { id: b, kind: links, key: F2 }"))
case("key int", panel("- { id: a, kind: provenance, key: 2 }"))
case("keys action to missing panel", HEAD + "keys: { F5: nosuchpanel }\npanels:\n  - { id: a, kind: provenance }\n")
case("keys not mapping", HEAD + "keys: [F5]\npanels:\n  - { id: a, kind: provenance }\n")
case("area left", panel("- { id: a, kind: provenance, area: left }"))
case("span 13", panel("- { id: a, kind: provenance, span: 13 }"))
case("span 0", panel("- { id: a, kind: provenance, span: 0 }"))
case("span 6.5", panel("- { id: a, kind: provenance, span: 6.5 }"))
case("span string", panel("- { id: a, kind: provenance, span: wide }"))
case("height 30", panel("- { id: a, kind: provenance, height: 30 }"))
case("height -1", panel("- { id: a, kind: provenance, height: -1 }"))
case("strip 9", HEAD.replace("title:", "strip: [" + ",".join("{label: L%d, bind: $.mtm}" % i for i in range(9)) + "]\ntitle:") + "panels: []\n")
case("strip item no bind", HEAD.replace("title:", "strip: [{label: L}]\ntitle:") + "panels: []\n")
case("strip not list", HEAD.replace("title:", "strip: {label: L}\ntitle:") + "panels: []\n")
case("strip unknown key", HEAD.replace("title:", "strip: [{label: L, bind: $.mtm, colour: red}]\ntitle:") + "panels: []\n")
case("strip bad fmt", HEAD.replace("title:", "strip: [{label: L, bind: $.mtm, fmt: nosuchfmt}]\ntitle:") + "panels: []\n")
case("strip bad tone", HEAD.replace("title:", "strip: [{label: L, bind: $.mtm, tone: nosuchtone}]\ntitle:") + "panels: []\n")
case("title missing id", HEAD.replace("title: { id: $.tradeId }", "title: { pill: X }") + "panels: []\n")
case("title not mapping", HEAD.replace("title: { id: $.tradeId }", "title: hello") + "panels: []\n")
case("title id bad expr", HEAD.replace("title: { id: $.tradeId }", "title: { id: '$.tradeId +' }") + "panels: []\n")
# kinds: missing required, wrong types, invalid values
case("table no rows", panel("- { id: a, kind: table }"))
case("table rows number", panel("- { id: a, kind: table, rows: 5 }"))
case("table rows not list at runtime", panel("- { id: a, kind: table, rows: $.notional, columns: [{label: x, bind: '@.a'}] }"))
case("table limit string", panel("- { id: a, kind: table, rows: $.cashflows, limit: many }"))
case("table limit negative", panel("- { id: a, kind: table, rows: $.cashflows, limit: -5 }"))
case("table column not mapping", panel("- { id: a, kind: table, rows: $.cashflows, columns: [x] }"))
case("table column no bind", panel("- { id: a, kind: table, rows: $.cashflows, columns: [{label: x}] }"))
case("table column bad expr", panel("- { id: a, kind: table, rows: $.cashflows, columns: [{label: x, bind: '@.a +* 2'}] }"))
case("table column div zero", panel("- { id: a, kind: table, rows: $.cashflows, columns: [{label: x, bind: '@.amount / 0'}] }"))
case("table search yes", panel("- { id: a, kind: table, rows: $.cashflows, search: maybe }"))
case("table pivot yes please", panel("- { id: a, kind: table, rows: $.cashflows, pivot: 'yes please' }"))
case("table pivot 5 rows", panel("- { id: a, kind: table, rows: $.cashflows, columns: [{label: a, bind: '@.a'}], pivot: { fields: [a,b,c,d,e], rows: [a,b,c,d,e] } }"))
case("table pivot agg median", panel("- { id: a, kind: table, rows: $.cashflows, pivot: { fields: [amount], values: [{field: amount, agg: median}] } }"))
case("table pivot chart pie", panel("- { id: a, kind: table, rows: $.cashflows, pivot: { fields: [amount], chart: pie } }"))
case("kv rows number", panel("- { id: a, kind: kv, rows: 5 }"))
case("kv fields", panel("- { id: a, kind: kv, fields: [a] }"))
case("tabs no each", panel("- { id: a, kind: tabs }"))
case("tabs body on kv", panel("- { id: a, kind: kv, body: { kind: kv } }"))
case("tabs body unknown kind", panel("- { id: a, kind: tabs, each: $.legs, body: { kind: nope } }"))
case("tabs body tabs (nested)", panel("- { id: a, kind: tabs, each: $.legs, body: { kind: tabs, each: '@.x', body: { kind: tabs, each: '@.y' } } }"))
case("tabs body span", panel("- { id: a, kind: tabs, each: $.legs, body: { kind: kv, span: 4 } }"))
case("tabs layout weird", panel("- { id: a, kind: tabs, each: $.legs, layout: diagonal, body: { kind: kv } }"))
case("line source bad", panel("- { id: a, kind: line, source: 'curve(' }"))
case("line rows string", panel("- { id: a, kind: line, rows: hello }"))
case("area no rows", panel("- { id: a, kind: area }"))
case("area series not list", panel("- { id: a, kind: area, rows: $.cashflows, series: 5 }"))
case("area limit string", panel("- { id: a, kind: area, rows: $.cashflows, limit: abc }"))
case("hbar no rows", panel("- { id: a, kind: hbar }"))
case("hbar tone weird", panel("- { id: a, kind: hbar, rows: $.cashflows, tone: rainbow }"))
case("ladder limit", panel("- { id: a, kind: ladder, rows: $.cashflows, limit: 5 }"))
case("ladder highlight bad", panel("- { id: a, kind: ladder, rows: $.cashflows, highlight: '@.x >' }"))
case("status fields not list", panel("- { id: a, kind: status, fields: 5 }"))
case("status field no bind", panel("- { id: a, kind: status, fields: [{label: x}] }"))
case("markdown no text", panel("- { id: a, kind: markdown }"))
case("gauge no value", panel("- { id: a, kind: gauge }"))
case("gauge max zero", panel("- { id: a, kind: gauge, value: $.mtm, max: 0 }"))
case("gauge value string", panel("- { id: a, kind: gauge, value: \"'abc'\" }"))
case("surface no y", panel("- { id: a, kind: surface, rows: $.cashflows }"))
case("surface view weird", panel("- { id: a, kind: surface, rows: $.cashflows, y: x, view: 4d }"))
case("waterfall colors rainbow", panel("- { id: a, kind: waterfall, rows: $.cashflows, colors: rainbow }"))
case("waterfall sum str", panel("- { id: a, kind: waterfall, rows: $.cashflows, sum: maybe }"))
case("histogram bins 0", panel("- { id: a, kind: histogram, rows: $.cashflows, bins: 0 }"))
case("histogram bins 201", panel("- { id: a, kind: histogram, rows: $.cashflows, bins: 201 }"))
case("histogram bins 2.5", panel("- { id: a, kind: histogram, rows: $.cashflows, bins: 2.5 }"))
case("histogram marker no value", panel("- { id: a, kind: histogram, rows: $.cashflows, markers: [{label: x}] }"))
case("histogram markers scalar", panel("- { id: a, kind: histogram, rows: $.cashflows, markers: 5 }"))
case("scatter no x", panel("- { id: a, kind: scatter, rows: $.cashflows, y: amount }"))
case("scatter expr x err", panel("- { id: a, kind: scatter, rows: $.cashflows, x: '@.a +', y: amount }"))
case("candlestick no rows", panel("- { id: a, kind: candlestick }"))
case("graph no nodes", panel("- { id: a, kind: graph }"))
case("graph layout circle", panel("- { id: a, kind: graph, nodes: $.legs, layout: circle }"))
case("timeline no rows", panel("- { id: a, kind: timeline }"))
case("pivot no by", panel("- { id: a, kind: pivot, rows: $.cashflows, across: leg }"))
case("pivot agg median", panel("- { id: a, kind: pivot, rows: $.cashflows, by: type, across: leg, agg: median }"))
case("pivot heat yes", panel("- { id: a, kind: pivot, rows: $.cashflows, by: type, across: leg, heat: 'yes' }"))
case("pivot heat unquoted yes", panel("- { id: a, kind: pivot, rows: $.cashflows, by: type, across: leg, heat: yes }"))
case("pivot totals str", panel("- { id: a, kind: pivot, rows: $.cashflows, by: type, across: leg, totals: sometimes }"))
case("links extra opt", panel("- { id: a, kind: links, rows: $.x }"))
case("provenance extra opt", panel("- { id: a, kind: provenance, text: hi }"))
# expressions
case("expr unknown function", panel("- { id: a, kind: kv, columns: [{label: x, bind: 'nosuchfn($.mtm)'}] }"))
case("expr fn wrong arity", panel("- { id: a, kind: kv, columns: [{label: x, bind: 'fmt()'}] }"))
case("expr string*num runtime", panel("- { id: a, kind: kv, columns: [{label: x, bind: \"'a' * $.mtm\"}] }"))
case("expr deep path null", panel("- { id: a, kind: kv, columns: [{label: x, bind: '$.a.b.c.d.e[99].f'}] }"))
case("expr huge nesting", panel("- { id: a, kind: kv, columns: [{label: x, bind: '" + "(" * 3000 + "1" + ")" * 3000 + "'}] }"))
case("expr long chain", panel("- { id: a, kind: kv, columns: [{label: x, bind: '" + " + ".join(["$.mtm"] * 5000) + "'}] }"))
case("expr self link", panel("- { id: a, kind: kv, columns: [{label: x, bind: \"link($.tradeId, 'trade')\"}] }"))
case("expr regex catastrophic", panel("- { id: a, kind: kv, columns: [{label: x, bind: \"matches('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!', '(a+)+$')\"}] }"))
case("curve(self)", panel("- { id: a, kind: line, source: \"curve('trade', $.tradeId)\" }"))
# yaml edge cases
case("anchors aliases", BASE.replace("panels:", "x_anchor: &A { id: zz, kind: provenance }\npanels:\n  - *A", 1))
case("anchor reused panel", HEAD + "panels:\n  - &P { id: a, kind: provenance }\n  - *P\n")
case("merge key", HEAD + "panels:\n  - &P { id: a, kind: markdown, text: hi }\n  - { <<: *P, id: b }\n")
case("billion laughs", "a: &a [x,x,x,x,x,x,x,x,x]\nb: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]\nc: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]\nd: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]\ne: &e [*d,*d,*d,*d,*d,*d,*d,*d,*d]\nf: &f [*e,*e,*e,*e,*e,*e,*e,*e,*e]\ng: &g [*f,*f,*f,*f,*f,*f,*f,*f,*f]\nh: &h [*g,*g,*g,*g,*g,*g,*g,*g,*g]\n" + HEAD + "panels: []\n")
case("recursive alias", HEAD + "panels: &p [ *p ]\n")
case("java tag", HEAD + "panels: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:1/\"]]]]\n")
case("custom tag", HEAD + "panels:\n  - !panel { id: a, kind: provenance }\n")
case("!!binary tag", HEAD.replace("version: 1", "version: !!binary MQ==") + "panels: []\n")
case("multi docs", BASE + "---\nrachana: 1\nsutra: other\n")
case("multi docs leading ---", "---\n" + BASE)
case("tabs indent", BASE.replace("  - id: terms", "\t- id: terms", 1))
case("BOM", "\ufeff" + BASE)
case("CRLF", BASE.replace("\n", "\r\n"))
case("duplicate top key", BASE + "version: 2\n")
case("duplicate panel key", panel("- { id: a, id: b, kind: provenance }"))
case("huge int key", HEAD + "panels: []\n99999999999999999999999999: x\n")
case("non-string key", HEAD + "panels: []\n1: x\n")
case("null key", HEAD + "panels: []\n~: x\n")
case("NUL char", BASE.replace("Terms", "Te\u0000rms", 1))
case("lone surrogate escape", BASE.replace("Terms", "\"\\ud800\"", 1))
case("very deep nesting", HEAD + "panels: []\nnotes: " + "[" * 5000 + "]" * 5000 + "\n")
case("100 panels", HEAD + "panels:\n" + "".join("  - { id: p%d, kind: markdown, text: 'panel %d' }\n" % (i, i) for i in range(100)))
case("2000 panels", HEAD + "panels:\n" + "".join("  - { id: p%d, kind: markdown, text: 'panel %d' }\n" % (i, i) for i in range(2000)))
case("5MB sutra", HEAD + "notes: '" + "n" * 5_000_000 + "'\npanels: []\n")
# request-shape attacks
case("kind missing in request", BASE, kind="")
case("kind unknown in request", BASE, kind="spaceship")
case("id missing", BASE, id_="")
case("id unknown", BASE, id_="NOPE-1")
case("id path traversal", BASE, id_="../../etc/passwd")
case("kind mismatch sutra kind", BASE, kind="counterparty", id_="CP-NORTHBRIDGE")
case("yaml null in body", None)

if __name__ == "__main__":
    only = sys.argv[1:]
    for n, y, kw in CASES:
        if only and not any(o in n for o in only):
            continue
        if y is None:
            run(n, None, raw=b'{"yaml": null, "kind": "trade", "id": "MX-20000001"}')
        else:
            run(n, y, **{k: v for k, v in kw.items()})
    run("body not json", None, raw=b"{not json")
    run("body empty", None, raw=b"")
    run("yaml number", None, raw=b'{"yaml": 5, "kind": "trade", "id": "MX-20000001"}')
