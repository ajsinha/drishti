#!/usr/bin/env python3
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

"""The single-node sizing planner workbook (CLUSTER_GUIDE.md, Sizing a single node: the planner workbook).

    python3 tools/sizing_planner.py                 writes docs/architecture/Drishti-Sizing-Planner.xlsx, the server's template
                                                    (capacity/template/) and the shared test vectors, all from capacity/planner.yaml
    python3 tools/sizing_planner.py --out DIR       writes the workbook into DIR instead
    python3 tools/sizing_planner.py --check         exit 1 when the committed workbook differs from a fresh build

The workbook has four tabs: Inputs (the yellow cells a planner types: positions, dates in memory, users, the Java version...),
Results (every heap, page-cache, RAM, disk and CPU figure, the settings with their keys, the JVM arguments and the warnings, each a
real Excel formula over named cells), Coefficients (every constant with its unit, source and status: measured, formula, calibrated
or estimate; Results never hard-code one) and Explain (plain words). Every input, coefficient, formula and warning comes from
capacity/planner.yaml (PyYAML reads it); the parts are written as XML into a zip.
The look is the VaR Playground's template (drishti-banking/src/main/resources/playground/var/template/xl/styles.xml).

Every formula is also evaluated here by a small evaluator of the subset used (names, + - * / ^ &, comparisons, MAX, MIN, IF,
CEILING, AND, OR, INT, ROUND), so each cell carries its value at the defaults and the tests can check the figures the Explain tab
states (EXPECTED) without a spreadsheet program.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape
import yaml

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "architecture" / "Drishti-Sizing-Planner.xlsx"
TEMPLATE = ROOT / "drishti-banking" / "src" / "main" / "resources" / "playground" / "var" / "template"
MB = "BytesPerMB"


def _version() -> str:
    pom = (ROOT / "drishti-server" / "pom.xml").read_text()
    m = re.search(r"<artifactId>drishti-server</artifactId>\s*<version>([^<]+)</version>", pom) or re.search(r"<parent>.*?<version>([^<]+)</version>", pom, re.S)
    return m.group(1) if m else "VERSION"


def _branding() -> dict:
    text = (ROOT / "drishti-server" / "src" / "main" / "resources" / "application.yaml").read_text(encoding="utf-8")
    out = {}
    for key in ("product", "owner", "copyright"):
        m = re.search(r"^\s+" + key + r":\s*(.+?)\s*(#.*)?$", text[text.index("branding:"):], re.M)
        out[key] = re.sub(r"^\$\{[A-Z_]+:(.*)\}$", r"\1", m.group(1)) if m else ""
    return out


# ---------------------------------------------------------------------------------------------------------------- the planner's one file
# drishti-server/src/main/resources/capacity/planner.yaml holds the inputs, coefficients, results, settings, JVM arguments, warnings,
# calibration lines and the Explain tab's examples. The server serves it to Admin -> Capacity, whose drishti_capacity.js evaluates the same
# expressions; this module turns it into the workbook. The tuples below keep the shapes the workbook code and the tests read.
PLANNER = ROOT / "drishti-server" / "src" / "main" / "resources" / "capacity" / "planner.yaml"
TEMPLATE_OUT = ROOT / "drishti-server" / "src" / "main" / "resources" / "capacity" / "template"
VECTORS = ROOT / "drishti-server" / "src" / "test" / "resources" / "capacity" / "test-vectors.json"


def _load() -> dict:
    with PLANNER.open(encoding="utf-8") as f:
        return yaml.safe_load(f)


PLAN = _load()
# (section, name, label, default, unit, kind, hint); kind: int, dec, text
INPUTS = [(i["group"], i["name"], i["label"], i["default"], i["unit"], i["kind"], i["hint"]) for i in PLAN["inputs"]]
# (name, value, unit, status, source); status: measured, formula, calibrated, estimate
COEFFS = [(c["name"], c["value"], c["unit"], c["status"], c["source"]) for c in PLAN["coefficients"]]


def _results() -> list:
    """("band", title), (name, label, formula, unit, why) and ("settings",) after the band that carries them."""
    r = []
    for band in PLAN["results"]:
        r.append(("band", band["band"]))
        r += [(x["name"], x["label"], x["expression"], x["unit"], x["why"]) for x in band["rows"]]
        if band.get("settings"):
            r.append(("settings",))
    return r


# (name, key, formula, unit, environment variable, its source, why, condition); source "docs": the name CONFIGURATION.md gives (a
# ${NAME:default} placeholder in application.yaml); "relaxed": none is documented, so Spring's relaxed binding of the key
SETTINGS = [(s["name"], s["key"], s["expression"], s["unit"], s["env"], s["envSource"], s["why"], s.get("condition")) for s in PLAN["settings"]]
SETTINGS_FILE = PLAN["settingsFile"]
OFF = PLAN["settingOff"]
# (name, flag, formula, condition, why, source); source: documented or suggested
JVM = [(j["name"], j["flag"], j["expression"], j["condition"], j["why"], j["source"]) for j in PLAN["jvm"]]
WARNINGS = [(w["name"], w["label"], w["expression"]) for w in PLAN["warnings"]]
CALIBRATION = [(c["name"], c["label"], c["expression"], c["unit"], c["why"]) for c in PLAN["calibration"]]
_EXAMPLES = {e["name"]: e for e in PLAN["examples"]}
# The Explain tab's second example: the 1,000,000-position benchmark's shape; the figures the Explain tab states (EXPECTED at the
# defaults, EXPECTED_BENCH with BENCH_SHAPE, CALIBRATION_EXPECTED whatever the inputs), checked by the tests of all three evaluators.
BENCH_SHAPE = _EXAMPLES["benchmark"]["inputs"]
EXPECTED = _EXAMPLES["production"]["expected"]
EXPECTED_BENCH = _EXAMPLES["benchmark"]["expected"]
CALIBRATION_EXPECTED = PLAN["calibrationExpected"]


def _q(text: str) -> str:
    return '"' + text.replace('"', '""') + '"'


def _yaml_formula(key: str, value: str) -> str:
    parts = key.split(".")
    lines = [_q("  " * i + part + ":") for i, part in enumerate(parts[:-1])] + [_q("  " * (len(parts) - 1) + parts[-1] + ": ") + "&" + value]
    return "&CHAR(10)&".join(lines)


def _cond(cond, expr: str) -> str:
    return expr if not cond else f"IF({cond},{expr},{_q(OFF)})"


def setting_columns(name: str, key: str, env: str, cond) -> dict:
    """The formulas of a setting's Where, YAML, environment and command-line cells."""
    return {"where": _q(SETTINGS_FILE), "yaml": _cond(cond, _yaml_formula(key, name)), "env": _cond(cond, _q(env + "=") + "&" + name),
            "cmd": _cond(cond, _q("--" + key + "=") + "&" + name), "use": _q("YAML")}


def yaml_block_formula(settings: list | None = None) -> str:
    """One nested YAML block of every setting (SETTINGS, or the tuples given): one drishti: root, each group once."""
    tree = {}
    for name, key, _f, _u, _e, _s, _w, cond in (settings if settings is not None else SETTINGS):
        node = tree
        for part in key.split(".")[:-1]:
            node = node.setdefault(part, {})
        node[key.split(".")[-1]] = (name, cond)

    def conds(node):
        out = set()
        for v in node.values():
            out |= conds(v) if isinstance(v, dict) else {v[1]}
        return out

    lines = []  # (expression, condition)

    def emit(node, depth, outer):
        for k, v in node.items():
            if isinstance(v, dict):
                c = conds(v)
                cond = next(iter(c)) if len(c) == 1 else None
                lines.append((_q("  " * depth + k + ":"), cond or outer))
                emit(v, depth + 1, cond or outer)
            else:
                lines.append((_q("  " * depth + k + ": ") + "&" + v[0], v[1] or outer))
    emit(tree, 0, None)
    out = [lines[0][0]]
    for expr, cond in lines[1:]:
        out.append(f'IF({cond},CHAR(10)&{expr},"")' if cond else f"CHAR(10)&{expr}")
    return "&".join(out)


def _command_formula(jvm: list | None = None) -> str:
    parts = ['JvmArenas&" java"'] + [f'IF({n}="",""," "&{n})' for n, *_ in (jvm if jvm is not None else JVM) if n != "JvmArenas"]
    return "&".join(parts) + '&" -jar "&ServerJar'


# ---------------------------------------------------------------------------------------------------------------- the evaluator
_TOKEN = re.compile(r'\s*(?:(?P<num>\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(?P<str>"(?:[^"]|"")*")|(?P<name>[A-Za-z_][A-Za-z0-9_.]*)|(?P<op><=|>=|<>|[-+*/^&=<>(),]))')


def _tokens(f: str) -> list:
    out, i = [], 0
    while i < len(f):
        m = _TOKEN.match(f, i)
        if not m or m.end() == i:
            if f[i:].strip() == "":
                break
            raise ValueError(f"cannot read {f[i:]!r} in {f!r}")
        i = m.end()
        kind = m.lastgroup
        val = m.group(kind)
        out.append((kind, val[1:-1].replace('""', '"') if kind == "str" else val))
    return out


def names_in(formula: str) -> set:
    """The names a formula refers to (functions excluded)."""
    toks = _tokens(formula)
    return {v for i, (k, v) in enumerate(toks) if k == "name" and not (i + 1 < len(toks) and toks[i + 1] == ("op", "("))}


def _text(v) -> str:
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(int(v)) if float(v).is_integer() else repr(float(v))
    return str(v)


def _num(v) -> float:
    if isinstance(v, str):
        raise ValueError(f"text {v!r} used as a number")
    return float(v)


class Evaluator:
    """Evaluates the workbook's formulas by name: {name: number, text or formula string starting with '='}."""

    def __init__(self, cells: dict):
        self.cells, self.memo = cells, {}

    def value(self, name: str):
        if name not in self.memo:
            c = self.cells[name]
            self.memo[name] = self.eval(c[1:]) if isinstance(c, str) and c.startswith("=") else c
        return self.memo[name]

    def eval(self, formula: str):
        saved = getattr(self, "toks", None), getattr(self, "pos", 0)  # a name's own formula is read in the middle of another
        self.toks, self.pos = _tokens(formula), 0
        try:
            v = self._compare()
            if self.pos != len(self.toks):
                raise ValueError(f"unexpected {self.toks[self.pos]} in {formula!r}")
            return v
        finally:
            self.toks, self.pos = saved

    def _peek(self):
        return self.toks[self.pos] if self.pos < len(self.toks) else (None, None)

    def _take(self, op=None):
        t = self._peek()
        if op is not None and t != ("op", op):
            raise ValueError(f"expected {op}, found {t}")
        self.pos += 1
        return t

    def _compare(self):
        a = self._concat()
        while self._peek()[0] == "op" and self._peek()[1] in ("=", "<>", "<", ">", "<=", ">="):
            op = self._take()[1]
            b = self._concat()
            x, y = (a.lower(), b.lower()) if isinstance(a, str) and isinstance(b, str) else (a, b)
            a = {"=": x == y, "<>": x != y, "<": x < y, ">": x > y, "<=": x <= y, ">=": x >= y}[op]
        return a

    def _concat(self):
        a = self._add()
        while self._peek() == ("op", "&"):
            self._take()
            a = _text(a) + _text(self._add())
        return a

    def _add(self):
        a = self._mul()
        while self._peek()[0] == "op" and self._peek()[1] in "+-" and self._peek()[1] != "":
            op = self._take()[1]
            b = self._mul()
            a = _num(a) + _num(b) if op == "+" else _num(a) - _num(b)
        return a

    def _mul(self):
        a = self._pow()
        while self._peek()[0] == "op" and self._peek()[1] in ("*", "/"):
            op = self._take()[1]
            b = self._pow()
            a = _num(a) * _num(b) if op == "*" else _num(a) / _num(b)
        return a

    def _pow(self):
        a = self._unary()
        while self._peek() == ("op", "^"):
            self._take()
            a = _num(a) ** _num(self._unary())
        return a

    def _unary(self):
        if self._peek() == ("op", "-"):
            self._take()
            return -_num(self._unary())
        return self._atom()

    def _atom(self):
        kind, v = self._take()
        if kind == "num":
            return float(v)
        if kind == "str":
            return v
        if kind == "op" and v == "(":
            x = self._compare()
            self._take(")")
            return x
        if kind == "name":
            if self._peek() == ("op", "("):
                return self._call(v.upper())
            return self.value(v)
        raise ValueError(f"unexpected {v!r}")

    def _call(self, fn: str):
        self._take("(")
        if fn == "IF":
            cond = self._compare()
            self._take(",")
            a = self._compare()
            self._take(",")
            b = self._compare()
            self._take(")")
            return a if cond else b
        args = []
        if self._peek() != ("op", ")"):
            args.append(self._compare())
            while self._peek() == ("op", ","):
                self._take()
                args.append(self._compare())
        self._take(")")
        if fn == "MAX":
            return max(_num(a) for a in args)
        if fn == "MIN":
            return min(_num(a) for a in args)
        if fn == "CEILING":
            x, s = _num(args[0]), _num(args[1])
            return math.ceil(x / s - 1e-12) * s
        if fn == "AND":
            return all(bool(a) for a in args)
        if fn == "OR":
            return any(bool(a) for a in args)
        if fn == "CHAR":
            return chr(int(_num(args[0])))
        if fn == "INT":
            return float(math.floor(_num(args[0])))
        if fn == "ROUND":
            return float(round(_num(args[0]), int(_num(args[1]))))
        raise ValueError(f"function {fn} is not supported")


# ---------------------------------------------------------------------------------------------------------------- the explanation
def _fmt(d: dict) -> dict:
    return {k: v if isinstance(v, str) else (f"{v:,.0f}" if abs(v) >= 100 else f"{v:,.1f}".rstrip("0").rstrip(".")) for k, v in d.items()}


def _example(title: str, figures: dict, shape: str) -> tuple:
    e = _fmt(figures)
    return (title,
            f"{shape} One book is {e['HeapBook']} MB of heap ({e['HbFormula']} by the formula, calibrated); the dates in memory and one opening "
            f"take {e['HeapDates']} MB; today's intraday layer {e['HeapIntraday']} MB. The steady heap after GC is {e['HeapSteady']} MB and the loads "
            f"in progress add {e['HeapLoadTransient']} MB: -Xmx = {e['HeapSteady']} ÷ 0.61 + {e['HeapLoadTransient']}, rounded up: -Xmx{e['XmxGB']}g, "
            f"collector {e['GcChoice']}. drishti.var.open-heap-mb {e['SetOpenHeapMB']} (the automatic net would be {e['AutoNetMB']} MB). A hot file "
            f"is {e['HotFileBookMB']} MB a date; the open dates' page cache {e['PageCacheMB']} MB. RAM {e['RamRecommendedGB']} GB recommended "
            f"({e['RamMinimumGB']} GB the smallest workable). Disk: the VaR lake for a year {e['DiskLakeVarGB']} GB, intraday batches in it "
            f"{e['DiskIntradayLakeGB']} GB, hot files {e['DiskHotGB']} GB, {e['DiskUsedGB']} GB in all: {e['DiskRecommendedGB']} GB recommended, "
            f"{e['DiskLocalRecommendedGB']} GB of local disk if the lakes live elsewhere. CPU: {e['CoresRecommended']} cores. A date's first open "
            f"about {e['FirstOpenSec']} s.")


def explain() -> list:
    """(title, text) rows of the Explain tab; figures in braces are EXPECTED's, formatted."""
    c = _fmt(CALIBRATION_EXPECTED)
    rows = [
        ("In plain words: what this workbook answers",
         "Before a large deployment you need to know how much memory, disk and CPU one Drishti server needs, and which settings to give it. "
         "Type what you expect (positions, dates kept in memory, users, the Java version) into the yellow cells of Inputs; Results then gives, "
         "line by line, the heap each part takes, the -Xmx to set, the machine's RAM, the disk by folder, the cores, the exact configuration keys "
         "with their values, the JVM arguments ready to paste, and warnings in words when a choice is risky. Every figure is an Excel formula you can "
         "follow; every constant it uses is on Coefficients, with where it came from, so you can tune it when you measure your own server."),
        ("How to use it",
         "1. Fill Inputs (yellow). 2. Read Results from the top: the heap terms, then the heap to set, page cache and RAM, disk, CPU, the settings and "
         "the JVM arguments; read every warning that is not OK. 3. Copy the settings into the server's configuration and the command line into its "
         "start script. 4. After the server runs with real data, compare Admin → Caches and Health with the lines here, and correct a coefficient "
         "(its status says how sure it is) when they differ. If your spreadsheet program does not recalculate on opening, press F9 (Excel) or "
         "Ctrl+Shift+F9 (LibreOffice)."),
        ("Units", "MB is 1,024 × 1,024 bytes and GB is 1,024 MB, as Java and Admin → Caches count them. Times are seconds."),
        ("Coefficients and their status",
         "measured: read in a benchmark or a test (the source names the section). formula: a rule of the code or the guides (VAR_GUIDE.md, "
         "CLUSTER_GUIDE.md, CONFIGURATION.md). calibrated: set so that the defaults reproduce the 1,000,000-position re-benchmark (Java 21, "
         "6 GB heap): BookHeapCalibration, FirstOpenOnceMB, BasePerPositionB, BaseTradeRows, HotSmallBytesPerTrade, LakeBytesPerValue, "
         "ResidentFirstIdle, ResidentNextIdle, ResidentShare, ExpHotBytesPerTrade, and ReqPerCoreVarAtRef from the users run. The users' "
         "figures are PerUserMB (working memory per concurrent user, measured) and ReqPerCoreTrades / ReqPerCoreVarAtRef (requests a second per "
         "core): change them in one place when you measure your own server. estimate: a reasoned guess to replace by a measurement."),
    ]
    for sec, name, label, default, unit, kind, hint in INPUTS:
        rows.append((f"Input: {label}", f"{hint} Default {default if kind == 'text' else f'{default:,}'} ({unit}); named {name}."))
    terms = [
        ("Heap: one VaR book",
         "A business date's VaR data lives in three places: the lake (every date, on disk), a hot file (one per open date, on local disk, mapped into "
         "memory: the P&L rows) and the heap (the date's ids, columns, nodes and links). The heap of one book is VAR_GUIDE.md section 27.1's "
         "H_book, term by term: node members as runs (8·R), names and runs (~150 bytes a book, cell or node), the aggregates' index (64·A), the "
         "columns (108·T + 136·P), the ids (72·(2T + 3P), the largest part), the per-currency totals of every node (8·(S+1)·N·C) and the portfolios. "
         "The sum is scaled by BookHeapCalibration to what the server measured."),
        ("Heap: one date and the dates in memory",
         "A date is its USD book plus one book per reporting currency built ahead and one for the stress set. The server keeps hot-dates dates open; "
         "the date just used, the newest date and the dates pinned in memory always stay, so if those alone are more than hot-dates, the planner counts "
         "them instead. While a new date opens, the date it pushes out stays open until the requests reading it finish, so the peak is one date more."),
        ("Heap: the server, caches and users",
         "At start, before any date is open, the server already holds the lake's footers and an index of the P&L rows (HeapBase, measured). The first "
         "date opened warms caches once (HeapFirstOpen). Each cache is counted at its budget: the Parquet footer cache, the type-ahead index, BI's "
         "query and plan caches, the history answers and VaR's answers. Trade rows beyond those measured add the trade connectors' working set, and "
         "each concurrent user's request needs working memory (PerUserMB, measured: about 2.5 MB)."),
        ("Heap: loads in progress",
         "While a date loads, the build holds its aggregates and currency totals on the heap (1.6 GB at 1,000,000 positions, VarProperties) plus a read "
         "buffer; drishti.var.load-threads dates may load at once. An exposure load holds every netting set's value matrix in float64."),
        ("The heap to set (-Xmx)",
         "The collector needs room: the heap after collection should be at most 61% of -Xmx (CLUSTER_GUIDE.md 9.5). So -Xmx = the steady heap ÷ "
         "0.61 + the loads in progress, rounded up to a whole GB. Under 32 GB Java keeps 4-byte object references (compressed oops); at 32 GB and above "
         "they double in size unless -XX:ObjectAlignmentInBytes=16, which keeps them compressed up to 64 GB: so either stay at 31g or go well past 32."),
        ("Page cache and RAM",
         "The hot files are read through the operating system's page cache, outside the heap: a question over the whole bank reads every row of a date. "
         "Recommended RAM keeps every open date's hot files resident, plus the heap (fully committed after a while), native memory (metaspace, direct "
         "buffers, threads, the allocator), the operating system and the console. The smallest workable RAM keeps only the date being read resident: "
         "it works, but a question on another date reads the disk first."),
        ("Disk, by folder",
         "The lake keeps every date for the years you keep; var/hot keeps up to 60 hot files (disk-dates) plus the pinned dates on disk and currency books "
         "for the 5 newest dates; tmp/server needs three times the largest hot file for each load running (the disk guard checks this before a build); "
         "logs rotate; a heap dump is the heap's size; the trade connectors keep id maps, grouped pages, a document cache and the type-ahead index; BI "
         "keeps layers and runs. The recommendation leaves the free-space guard's margin: 10% of the disk and 20 GB free. When the lakes live on object "
         "storage, the local-disk line is the one to buy fast (NVMe)."),
        ("CPU",
         "Each core answers about 22.5 trade questions a second and, at 1,000,000 positions, about 11.6 VaR questions a second (measured on a machine "
         "of 12 cores and 24 hardware threads: 183 requests a second at 50 users, half of them VaR, none failed). A VaR question's cost grows with the "
         "positions (VarCostExponent). The mix is weighed by your share of VaR questions; the cores (physical cores, two hardware threads each) are "
         "planned 70% busy at the peak, plus 2 for loads, the collector and the operating system. More users than the cores can serve do not fail, "
         "they wait: the same machine answered 138 requests a second at 100 users and 124 at 200, with p95 about 2 and 5 s."),
        ("The settings",
         "drishti.var.hot-dates: the dates in memory. drishti.var.open-heap-mb: the byte safety net over every open date's heap; when the dates open "
         "exceed it, the least recently used dates' currency books, then stress books, then whole dates close (never a protected one, never one read in "
         "the last 120 seconds). Its automatic value (0.4 × heap − 512 MB − load-threads × 1.6 GB) is often smaller than ten dates need, so the planner "
         "gives an explicit value. drishti.var.memory-budget-mb bounds the heap tier only and stays 0 on the mapped tier. -Ddrishti.parquet.footer-cache-mb "
         "is a JVM property. drishti.var.load-threads, drishti.var.tier.max-disk-gb, disk-dates, currencies, stress and the exposure dates follow. "
         "Each setting is given in its three forms with its exact key, as CONFIGURATION.md names it: YAML for ./application.local.yaml in the "
         "server's working directory (the recommended form), the environment variable (CONFIGURATION.md's name, or Spring's relaxed binding of the "
         "key when it names none: drop the dashes, dots to underscores, upper case) and the server argument (--key=value). The YAML block to paste "
         "merges them under one drishti: root. The JVM arguments (-X, -XX and the -D system properties the code reads) are command line only, not "
         "YAML; they may also go in JAVA_TOOL_OPTIONS. MALLOC_ARENA_MAX is an environment variable of Linux."),
    ]
    rows += terms
    flags = [(f"JVM: {flag}", f"{why} When: {cond}. ({src}: {'from the docs and launchers' if src == 'documented' else 'beyond the docs, a suggestion'}.)")
             for _n, flag, _f, cond, why, src in JVM]
    rows += flags
    rows += [
        ("The command line",
         "Results assembles every argument into one line ready to paste: MALLOC_ARENA_MAX=2 java <arguments> -jar <the server jar>. On Windows leave out "
         "MALLOC_ARENA_MAX (a Linux setting) and use Windows paths for the data folder."),
        ("Examples: two shapes",
         "The two examples below are the workbook's results for two sets of inputs: the defaults (the production shape) and the 1,000,000-position "
         "benchmark. Type either into Inputs to see every line; the Calibration check block always shows the benchmark against what it measured."),
        _example("Example 1, production (the defaults): 300,000 trades, 39% updated intraday", EXPECTED,
                 "300,000 OTC trades, one a position; portfolios, basket members, nodes, books and cells scaled from the benchmark by 0.3; 10 intraday "
                 "batches of 11,700 updates (117,000 a day, 39% of the trades; the batch count assumed); 300,000 trade rows a day; 10 dates in memory; "
                 "50 users and 100 requests a second, half VaR; Java 21."),
        _example("Example 2, the benchmark: 1,000,000 positions, no intraday", EXPECTED_BENCH,
                 "1,000,000 positions of one trade, 15,000 portfolios, 2,000,000 basket members, 27,024 nodes, 130,000 books and cells, no intraday "
                 "batch, 20,000 trade rows a day; the rest as the defaults."),
        ("Against the measured",
         f"The Calibration check block computes the benchmark shape from the Bench coefficients against the final 1,000,000-position users run at "
         f"6 GB of heap (measured in brackets): heap after GC {c['CalHeapStart']} MB at start (769), {c['CalHeapOne']} MB with one date open (1,352: "
         f"every date is counted at the 0.72 GB average, the safe side), {c['CalHeapTwo']} MB with two (2,213); RSS {c['CalRssStart']}, "
         f"{c['CalRssOne']} and {c['CalRssTwo']} MB (2,875, 5,156, 6,899); {c['CalLakeTwoGB']} GB of lake for two dates (2.8); "
         f"{c['CalWarmHeap']} MB warm at 50 users (2,336), RSS {c['CalRssEnd']} MB at the end (11,160) and {c['CalReqPerSec']} "
         "requests a second on its 12 cores (183.3). A recommended -Xmx is larger than the 6 GB that held two dates because it adds ten "
         "dates, the loads running together, every cache at its budget, the users and the collector's headroom."),
        ("Limits",
         "One server only: a sharded cluster divides the hot files, the page cache and the loads among its members but copies each date's skeleton to "
         "every member, which this sheet does not model; a cluster sheet comes later. The requests a second per core were measured on one "
         "workstation; a server's cores may differ. The hot files' page cache is advisory: the server works with less RAM, more slowly. The figures are for the "
         "mapped tier (the default); the heap tier keeps every vector on the heap (HeapTierBudgetMB a date)."),
    ]
    return rows


# ---------------------------------------------------------------------------------------------------------------- the workbook
def _col(i: int) -> str:
    s = ""
    i += 1
    while i:
        i, r = divmod(i - 1, 26)
        s = chr(65 + r) + s
    return s


def _bad_name(n: str) -> bool:
    return bool(re.fullmatch(r"[A-Za-z]{1,3}[0-9]+|[RrCc]|[Rr][0-9]*[Cc][0-9]*", n))


class Sheet:
    def __init__(self, name: str, styles: dict, tab: str):
        self.name, self.styles, self.tab = name, styles, tab
        self.rows, self.widths, self.merges, self.heights, self.cf, self.validations = {}, {}, [], {}, [], []
        self.freeze = 0

    def put(self, r: int, c: int, kind: str, value, style: str = "default", cached=None):
        self.rows.setdefault(r, {})[c] = (kind, value, style, cached)

    def xml(self) -> str:
        out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
               'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">',
               f'<sheetPr><tabColor rgb="FF{self.tab}"/></sheetPr><sheetViews><sheetView workbookViewId="0"'
               + (' tabSelected="1"' if self.name == "Inputs" else "") + ">"]
        if self.freeze:
            out.append(f'<pane ySplit="{self.freeze}" topLeftCell="A{self.freeze + 1}" activePane="bottomLeft" state="frozen"/>')
        out.append('</sheetView></sheetViews><sheetFormatPr defaultRowHeight="14"/><cols>')
        out += [f'<col min="{c + 1}" max="{c + 1}" width="{w}" customWidth="1"/>' for c, w in sorted(self.widths.items())]
        out.append("</cols><sheetData>")
        for r in sorted(self.rows):
            ht = f' ht="{self.heights[r]}" customHeight="1"' if r in self.heights else ""
            out.append(f'<row r="{r}"{ht}>')
            for c in sorted(self.rows[r]):
                kind, v, style, cached = self.rows[r][c]
                ref, s = f"{_col(c)}{r}", self.styles[style]
                sa = f' s="{s}"' if s else ""
                if kind == "text":
                    out.append(f'<c r="{ref}"{sa} t="inlineStr"><is><t xml:space="preserve">{escape(v)}</t></is></c>')
                elif kind == "num":
                    out.append(f'<c r="{ref}"{sa}><v>{_text(v)}</v></c>')
                elif kind == "blank":
                    out.append(f'<c r="{ref}"{sa}/>')
                elif isinstance(cached, str):
                    out.append(f'<c r="{ref}"{sa} t="str"><f>{escape(v)}</f><v>{escape(cached)}</v></c>')
                elif isinstance(cached, bool):
                    out.append(f'<c r="{ref}"{sa} t="b"><f>{escape(v)}</f><v>{int(cached)}</v></c>')
                else:
                    out.append(f'<c r="{ref}"{sa}><f>{escape(v)}</f><v>{_text(round(cached, 9))}</v></c>')
            out.append("</row>")
        out.append("</sheetData>")
        if self.merges:
            out.append(f'<mergeCells count="{len(self.merges)}">' + "".join(f'<mergeCell ref="{m}"/>' for m in self.merges) + "</mergeCells>")
        out += self.cf
        if self.validations:
            out.append(f'<dataValidations count="{len(self.validations)}">' + "".join(self.validations) + "</dataValidations>")
        out.append('<pageMargins left="0.4" right="0.4" top="0.5" bottom="0.6" header="0.2" footer="0.25"/>'
                   '<pageSetup orientation="landscape" fitToWidth="1" fitToHeight="0"/></worksheet>')
        return "".join(out)


def _styles() -> tuple[str, dict]:
    """The playground template's styles plus five of the planner's own (thousands, one decimal, totals, input numbers, the command line)."""
    xml = re.sub(r"<!--.*?-->\s*", "", (TEMPLATE / "xl" / "styles.xml").read_text(encoding="utf-8"), flags=re.S)
    names = {}
    for line in (TEMPLATE / "template.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("style."):
            k, v = line[6:].split("=")
            names[k.strip()] = int(v)
    n_fmt = int(re.search(r'<numFmts count="(\d+)">', xml).group(1))
    xml = xml.replace(f'<numFmts count="{n_fmt}">', f'<numFmts count="{n_fmt + 1}">').replace(
        "</numFmts>", '<numFmt numFmtId="170" formatCode="#,##0.0"/></numFmts>')
    n_font = int(re.search(r'<fonts count="(\d+)">', xml).group(1))
    xml = xml.replace(f'<fonts count="{n_font}">', f'<fonts count="{n_font + 1}">').replace(
        "</fonts>", '<font><sz val="9"/><color rgb="FF1F2937"/><name val="Consolas"/><family val="3"/></font></fonts>')
    n_xf = int(re.search(r'<cellXfs count="(\d+)">', xml).group(1))
    extra = [
        ("num0", '<xf numFmtId="168" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'),
        ("num1", '<xf numFmtId="170" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'),
        ("num.total", '<xf numFmtId="168" fontId="6" fillId="4" borderId="2" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>'),
        ("input.num", '<xf numFmtId="168" fontId="5" fillId="5" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/>'),
        ("code", f'<xf numFmtId="0" fontId="{n_font}" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>'),
    ]
    for i, (k, x) in enumerate(extra):
        names[k] = n_xf + i
    xml = xml.replace(f'<cellXfs count="{n_xf}">', f'<cellXfs count="{n_xf + len(extra)}">').replace("</cellXfs>", "".join(x for _, x in extra) + "</cellXfs>")
    return xml, names


def _height(text: str, width: float) -> float:
    lines = sum(max(1, math.ceil(len(p) / max(width * 1.15, 1))) for p in text.split("\n"))
    return max(14.0, 13.0 * lines + 2)


def workbook_cells(overrides: dict | None = None) -> dict:
    """Every named cell: an input's or coefficient's value ({@code overrides} replacing inputs), or "=formula"."""
    version = _version()
    cells = {}
    for _s, name, _l, default, _u, kind, _h in INPUTS:
        cells[name] = default.replace("{VERSION}", version) if isinstance(default, str) else default
    cells.update(overrides or {})
    for name, value, *_ in COEFFS:
        cells[name] = value
    for item in _results():
        if item[0] not in ("band", "note", "settings"):
            cells[item[0]] = "=" + item[2]
    for name, _flag, f, *_ in JVM:
        cells[name] = "=" + f
    cells["JvmCommand"] = "=" + _command_formula()
    for name, _k, f, *_ in SETTINGS:
        cells[name] = "=" + f
    cells["YamlBlock"] = "=" + yaml_block_formula()
    for name, _l, f in WARNINGS:
        cells[name] = "=" + f
    for name, _l, f, *_ in CALIBRATION:
        cells[name] = "=" + f
    return cells


def plan_cells(plan: dict, overrides: dict | None = None) -> dict:
    """Every named cell of a planner document (planner.yaml as read, or as a server serves it), {@code overrides} replacing inputs: what
    drishti.py admin capacity and drishti_capacity.js evaluate."""
    cells = {i["name"]: i["default"] for i in plan["inputs"]}
    cells.update(overrides or {})
    cells.update({c["name"]: c["value"] for c in plan["coefficients"]})
    for band in plan["results"]:
        cells.update({x["name"]: "=" + x["expression"] for x in band["rows"]})
    jvm = [(j["name"], j["flag"], j["expression"], j["condition"], j["why"], j["source"]) for j in plan["jvm"]]
    cells.update({j[0]: "=" + j[2] for j in jvm})
    cells["JvmCommand"] = "=" + _command_formula(jvm)
    settings = [(s["name"], s["key"], s["expression"], s["unit"], s["env"], s["envSource"], s["why"], s.get("condition")) for s in plan["settings"]]
    cells.update({s[0]: "=" + s[2] for s in settings})
    cells["YamlBlock"] = "=" + yaml_block_formula(settings)
    cells.update({w["name"]: "=" + w["expression"] for w in plan["warnings"]})
    cells.update({c["name"]: "=" + c["expression"] for c in plan.get("calibration") or []})
    return cells


def evaluate(overrides: dict | None = None) -> Evaluator:
    """The workbook's figures for the defaults, or for the inputs {@code overrides} changes (the Explain tab's examples)."""
    return Evaluator(workbook_cells(overrides))


def build(brand: dict | None = None) -> dict:
    """The workbook's parts: {path in the zip: text}; {@code brand} replaces the configured branding (the template's placeholders)."""
    brand = brand or _branding()
    styles_xml, st = _styles()
    where = {}  # name -> (sheet, row, col)
    cells = workbook_cells()
    results = _results()
    ev = Evaluator(cells)
    for n in cells:
        if _bad_name(n):
            raise ValueError(f"{n} is not a valid Excel name")
        ev.value(n)

    # Inputs
    inp = Sheet("Inputs", st, "1F3A5F")
    inp.widths = {0: 46, 1: 20, 2: 12, 3: 20, 4: 90}
    inp.put(1, 0, "text", f"{brand['product']} · Single-node sizing planner: inputs", "title")
    for c in range(1, 5):
        inp.put(1, c, "blank", None, "title")
    inp.put(2, 0, "text", "Type your expected loads into the yellow cells; Results recomputes everything. Defaults are the 1,000,000-position benchmark's shape.", "note")
    inp.put(3, 0, "text", "Input", "head")
    for c, h in enumerate(["Value", "Unit", "Name", "Hint"], 1):
        inp.put(3, c, "text", h, "head")
    inp.freeze = 3
    r, sec = 4, None
    for s, name, label, default, unit, kind, hint in INPUTS:
        if s != sec:
            sec = s
            inp.put(r, 0, "text", s, "band")
            for c in range(1, 5):
                inp.put(r, c, "blank", None, "band")
            r += 1
        v = cells[name]
        style = "input.text" if kind == "text" else ("input.num" if kind == "int" else "input.dec")
        inp.put(r, 0, "text", label, "label")
        inp.put(r, 1, "text" if kind == "text" else "num", v, style)
        inp.put(r, 2, "text", unit, "text")
        inp.put(r, 3, "text", name, "note")
        inp.put(r, 4, "text", hint, "wrap")
        where[name] = ("Inputs", r, 1)
        if name == "Collector":
            inp.validations.append(f'<dataValidation type="list" allowBlank="0" showErrorMessage="1" sqref="B{r}"><formula1>"auto,G1,ZGC"</formula1></dataValidation>')
        if name == "JavaVersion":
            inp.validations.append(f'<dataValidation type="list" allowBlank="0" showErrorMessage="1" sqref="B{r}"><formula1>"21,25"</formula1></dataValidation>')
        if name in ("StressOn", "ExposureOn"):
            inp.validations.append(f'<dataValidation type="list" allowBlank="0" showErrorMessage="1" sqref="B{r}"><formula1>"0,1"</formula1></dataValidation>')
        r += 1

    # Coefficients
    co = Sheet("Coefficients", st, "6B7686")
    co.widths = {0: 26, 1: 14, 2: 18, 3: 12, 4: 120}
    co.put(1, 0, "text", "Coefficients: every constant Results uses (tune them here; Results never hard-codes one)", "title")
    for c in range(1, 5):
        co.put(1, c, "blank", None, "title")
    co.put(2, 0, "text", "measured: read in a benchmark · formula: a rule of the code or the guides · calibrated: set so the defaults reproduce the re-benchmark · estimate: to replace by a measurement", "note")
    for c, h in enumerate(["Name", "Value", "Unit", "Status", "Source"]):
        co.put(3, c, "text", h, "head")
    co.freeze = 3
    for i, (name, value, unit, status, source) in enumerate(COEFFS, 4):
        co.put(i, 0, "text", name, "label")
        co.put(i, 1, "num", value, "input.num" if float(value).is_integer() and abs(value) >= 1 else "input.dec")
        co.put(i, 2, "text", unit, "text")
        co.put(i, 3, "text", status, "text")
        co.put(i, 4, "text", source, "wrap")
        co.heights[i] = _height(source, 120)
        where[name] = ("Coefficients", i, 1)

    # Results
    rs = Sheet("Results", st, "2E5A88")
    rs.widths = {0: 44, 1: 18, 2: 11, 3: 70, 4: 22, 5: 90, 6: 30, 7: 40, 8: 44, 9: 50, 10: 14}
    last = 10
    rs.put(1, 0, "text", "Results: memory, disk, CPU, settings and JVM arguments for one server", "title")
    for c in range(1, last + 1):
        rs.put(1, c, "blank", None, "title")
    rs.put(2, 0, "text", "Every value is a formula over the named cells of Inputs and Coefficients (column Formula shows it). Read every warning that is not OK.", "note")
    for c, h in enumerate(["Item", "Value", "Unit", "Why", "Name", "Formula", "Where", "YAML", "Environment variable", "Command line", "Use"]):
        rs.put(3, c, "text", h, "head")
    rs.freeze = 3
    r = 4

    def band_row(title):
        nonlocal r
        rs.put(r, 0, "text", title, "band")
        for c in range(1, last + 1):
            rs.put(r, c, "blank", None, "band")
        r += 1

    def result_row(name, label, formula, unit, why, style=None):
        nonlocal r
        v = ev.value(name)
        if style is None:
            style = "text" if isinstance(v, str) else ("num0" if unit in ("MB", "rows", "requests/s") and abs(v) >= 100 else "num1" if unit not in ("factor", "share") else "rate")
        if name in ("XmxGB", "RamRecommendedGB", "DiskRecommendedGB", "CoresRecommended", "SetOpenHeapMB"):
            style = "num.total"
        rs.put(r, 0, "text", label, "label.wrap")
        rs.put(r, 1, "formula", formula, style, v)
        rs.put(r, 2, "text", unit if unit != "text" else "", "text")
        rs.put(r, 3, "text", why, "wrap")
        rs.put(r, 4, "text", name, "note")
        rs.put(r, 5, "text", "=" + formula, "code")
        rs.heights[r] = max(_height(why, 70), _height(formula, 100), _height(label, 44), _height(_text(v), 18) if isinstance(v, str) else 14)
        where[name] = ("Results", r, 1)
        r += 1

    def forms(cols: dict, extra_height: float = 0):
        nonlocal r
        h = 14.0
        for c, k in enumerate(("where", "yaml", "env", "cmd", "use"), 6):
            f = cols[k]
            v = ev.eval(f)
            rs.put(r - 1, c, "formula", f, "code" if k != "where" else "wrap", v)
            h = max(h, 13.0 * (v.count("\n") + 1) + 4, _height(v, rs.widths[c]))
        rs.heights[r - 1] = max(rs.heights.get(r - 1, 14), h + extra_height)

    for item in results:
        if item[0] == "band":
            band_row(item[1])
        elif item[0] == "settings":
            for name, key, f, unit, env, src, why, cond in SETTINGS:
                result_row(name, key, f, unit, why + (" Environment variable: CONFIGURATION.md's." if src == "docs" else
                                                      " Environment variable: none documented; Spring's relaxed binding of the key."))
                forms(setting_columns(name, key, env, cond))
            v = ev.value("YamlBlock")
            rs.put(r, 0, "text", "The YAML block to paste into " + SETTINGS_FILE, "label.wrap")
            rs.put(r, 1, "formula", yaml_block_formula(), "code", v)
            rs.merges.append(f"B{r}:F{r}")
            rs.heights[r] = 13.0 * (v.count("\n") + 1) + 6
            where["YamlBlock"] = ("Results", r, 1)
            r += 1
        else:
            result_row(*item)
    band_row("JVM arguments: command line only, not YAML (documented: from the docs and launchers; suggested: beyond them)")
    for name, flag, f, cond, why, src in JVM:
        result_row(name, flag, f, "text", f"{why} When: {cond}. [{src}]")
        if name == "JvmArenas":
            forms({"where": _q("environment (Linux, glibc)"), "yaml": _q("environment only (not YAML)"), "env": name,
                   "cmd": _q("before java: ") + "&" + name, "use": _q("environment")})
        else:
            forms({"where": _q("JVM command line, before -jar"), "yaml": _q("command line only (not YAML)"),
                   "env": f'IF({name}="","(not set)","or in JAVA_TOOL_OPTIONS: "&{name})', "cmd": f'IF({name}="","(not set)",{name})',
                   "use": _q("command line")})
    band_row("The command line, ready to paste")
    v = ev.value("JvmCommand")
    rs.put(r, 0, "text", "java ... -jar", "label")
    rs.put(r, 1, "formula", _command_formula(), "code", v)
    rs.merges.append(f"B{r}:F{r}")
    rs.heights[r] = _height(v, 200) + 6
    where["JvmCommand"] = ("Results", r, 1)
    r += 1
    band_row("Warnings (OK when nothing to say)")
    first = r
    for name, label, f in WARNINGS:
        v = ev.value(name)
        rs.put(r, 0, "text", label, "label")
        rs.put(r, 1, "formula", f, "wrap", v)
        rs.merges.append(f"B{r}:D{r}")
        rs.put(r, 4, "text", name, "note")
        rs.put(r, 5, "text", "=" + f, "code")
        rs.heights[r] = max(_height(v, 100), _height(f, 100))
        where[name] = ("Results", r, 1)
        r += 1
    rs.cf.append(f'<conditionalFormatting sqref="B{first}:B{r - 1}"><cfRule type="cellIs" dxfId="0" priority="1" operator="equal"><formula>"OK"</formula></cfRule>'
                 f'<cfRule type="cellIs" dxfId="1" priority="2" operator="notEqual"><formula>"OK"</formula></cfRule></conditionalFormatting>')
    band_row("Calibration check: the 1,000,000-position benchmark shape (the Bench coefficients) against what it measured, whatever the Inputs")
    for item in CALIBRATION:
        result_row(*item)

    # Explain
    ex = Sheet("Explain", st, "C00000")
    ex.widths = {0: 40, 1: 150}
    ex.put(1, 0, "text", "Explain: the sizing planner in plain words", "title")
    ex.put(1, 1, "blank", None, "title")
    for i, (title, text) in enumerate(explain(), 2):
        ex.put(i, 0, "text", title, "label.wrap")
        ex.put(i, 1, "text", text, "wrap")
        ex.heights[i] = max(_height(text, 150), _height(title, 40))

    sheets = [inp, rs, co, ex]
    defined = "".join(f'<definedName name="{n}">{s}!${_col(c)}${row}</definedName>' for n, (s, row, c) in sorted(where.items(), key=lambda x: x[0].lower()))
    missing = set(cells) - set(where)
    if missing:
        raise ValueError(f"names without a cell: {sorted(missing)}")
    parts = {
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        + "".join(f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for i in range(1, len(sheets) + 1))
        + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
        '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
        '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
        '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>',
        "docProps/core.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
        f"<dc:title>{escape(brand['product'])} sizing planner</dc:title><dc:subject>Single-node sizing</dc:subject><dc:creator>{escape(brand['product'])}</dc:creator>"
        f"<dc:description>{escape(brand['copyright'])}</dc:description></cp:coreProperties>",
        "docProps/app.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">'
        f"<Application>{escape(brand['product'])}</Application><Company>{escape(brand['owner'])}</Company><DocSecurity>0</DocSecurity></Properties>",
        "xl/_rels/workbook.xml.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + "".join(f'<Relationship Id="rId{i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{i}.xml"/>' for i in range(1, len(sheets) + 1))
        + f'<Relationship Id="rId{len(sheets) + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
        "xl/workbook.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView activeTab="0"/></bookViews><sheets>'
        + "".join(f'<sheet name="{s.name}" sheetId="{i}" r:id="rId{i}"/>' for i, s in enumerate(sheets, 1))
        + f'</sheets><definedNames>{defined}</definedNames><calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>',
        "xl/styles.xml": styles_xml,
    }
    for i, s in enumerate(sheets, 1):
        parts[f"xl/worksheets/sheet{i}.xml"] = s.xml()
    return parts


def write(parts: dict, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".part")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for name in parts:
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, parts[name].encode("utf-8"))
    tmp.replace(path)


def read(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        return {n: z.read(n).decode("utf-8") for n in z.namelist()}


# The server's template (capacity/template/): the workbook's parts with the branding as {{PRODUCT}}, {{OWNER}}, {{COPYRIGHT}} placeholders
# (PlaygroundTemplate's), and template.json naming the parts, the Inputs sheet, each input's row and the styles the server needs to fill
# the Inputs (and a Live or Plan column) without touching a formula.
PLACEHOLDERS = {"product": "{{PRODUCT}}", "owner": "{{OWNER}}", "copyright": "{{COPYRIGHT}}"}
VECTOR_SHAPES = {
    "production": {},
    "benchmark": None,  # BENCH_SHAPE
    "large-java25-exposure": {"Positions": 4_000_000, "Trades": 4_200_000, "JavaVersion": 25, "ExposureOn": 1, "StressOn": 1, "CcysAhead": 4,
                              "PinnedMem": 12, "Collector": "auto", "Users": 400, "PeakRps": 900, "DataFolder": "/data/drishti"},
}


def template_parts() -> dict:
    """{file under capacity/template/: text}."""
    parts = build(PLACEHOLDERS)
    _xml, styles = _styles()
    rows = {}
    for r, line in enumerate(parts["xl/worksheets/sheet1.xml"].split('<row r="')[1:], 0):
        num = int(line[:line.index('"')])
        for _s, name, *_rest in INPUTS:
            if f'<c r="D{num}"' in line and f">{escape(name)}</t>" in line.split(f'<c r="D{num}"')[1].split("</c>")[0]:
                rows[name] = num
    inputs = {name: {"row": rows[name], "kind": kind} for _s, name, _l, _d, _u, kind, _h in INPUTS}
    meta = {"comment": "Written by tools/sizing_planner.py: do not edit; edit capacity/planner.yaml and run the tool.",
            "parts": sorted(parts), "inputsSheet": "xl/worksheets/sheet1.xml", "valueColumn": "B", "extraColumn": "F", "headerRow": 3,
            "styles": {k: styles[k] for k in ("head", "text", "note", "input.num", "input.dec", "input.text", "num0")}, "inputs": inputs}
    import license_headers                                                   # each .xml part carries the copyright notice (the server drops comments)
    note = license_headers.block("xml")
    out = {n: (x.split("\n", 1)[0] + "\n" + note + x.split("\n", 1)[1] if x.startswith("<?xml") else note + x) for n, x in parts.items()}
    out["template.json"] = json.dumps(meta, indent=1, ensure_ascii=False) + "\n"
    return out


def vectors() -> dict:
    """Every named figure at three shapes: the shared test vectors the Python, Java and JavaScript evaluators are checked against."""
    out = {"comment": "Written by tools/sizing_planner.py from capacity/planner.yaml: every named figure at three shapes. Numbers rounded to 6 "
                      "decimals; text exact. The console's drishti_capacity.js and the server's test check their evaluation against these.", "shapes": {}}
    for shape, inputs in VECTOR_SHAPES.items():
        inputs = BENCH_SHAPE if inputs is None else inputs
        cells = workbook_cells(inputs)
        ev = Evaluator(cells)
        res = {}
        for n in cells:
            v = ev.value(n)
            res[n] = v if isinstance(v, (str, bool)) else round(float(v), 6)
        out["shapes"][shape] = {"inputs": inputs, "results": res}
    return out


def _vectors_text() -> str:
    return json.dumps(vectors(), indent=1, ensure_ascii=False, sort_keys=False) + "\n"


def _stale_template() -> list:
    want = template_parts()
    have = sorted(str(p.relative_to(TEMPLATE_OUT)).replace("\\", "/") for p in TEMPLATE_OUT.rglob("*") if p.is_file()) if TEMPLATE_OUT.exists() else []
    stale = sorted(set(have) ^ set(want))
    stale += [n for n in want if n in have and (TEMPLATE_OUT / n).read_text(encoding="utf-8") != want[n]]
    return stale


def write_template(folder: Path = TEMPLATE_OUT) -> None:
    if folder.exists():
        shutil.rmtree(folder)
    for name, text in template_parts().items():
        f = folder / name
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", help="the folder to write the workbook into (default docs/architecture); the template and vectors are not written")
    ap.add_argument("--check", action="store_true", help="exit 1 when the committed workbook, the server's template or the test vectors differ from a fresh build")
    a = ap.parse_args(argv)
    parts = build()
    if a.check:
        bad = []
        if not OUT.exists() or read(OUT) != parts:
            bad.append(str(OUT.relative_to(ROOT)))
        stale = _stale_template()
        if stale:
            bad.append(f"{TEMPLATE_OUT.relative_to(ROOT)} ({', '.join(stale[:5])})")
        if not VECTORS.exists() or VECTORS.read_text(encoding="utf-8") != _vectors_text():
            bad.append(str(VECTORS.relative_to(ROOT)))
        if bad:
            print(f"out of date: {'; '.join(bad)}: run python3 tools/sizing_planner.py", file=sys.stderr)
            return 1
        print(f"{OUT.relative_to(ROOT)}, the server's template and the test vectors are current")
        return 0
    if a.out:
        path = Path(a.out) / OUT.name
        write(parts, path)
        print(f"wrote {path}")
        return 0
    write(parts, OUT)
    write_template()
    VECTORS.parent.mkdir(parents=True, exist_ok=True)
    VECTORS.write_text(_vectors_text(), encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}, {TEMPLATE_OUT.relative_to(ROOT)}/ and {VECTORS.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
