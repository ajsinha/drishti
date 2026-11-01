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
"""The charts of docs/admin/BENCHMARK.md, drawn from the VaR run's results (var_bench.py's JSON lines) as plain SVG files.

    python3 tools/bench/charts.py tools/bench/results/2.9.8-200k.jsonl tools/bench/results/2.9.8-1m.jsonl --to docs/admin/images/benchmark

Standard library only. Each chart has a transparent background and mid-tone colours, so it reads on a light and on a dark page; every
bar carries its value as a tooltip (``<title>``). A chart is drawn only from measured lines: a cell no run measured is left out, and a
chart with nothing measured is not written. The sizes are told apart by each line's ``positions``. Rerun it after every run.

Charts: requests a second by users (single server and cluster); p99 by request type per size; a date's first open split by stage;
heap and resident memory against the sizing formula; GC pauses by Java and collector; the hottest CPU frames of a first open and of
the run under load; disk and load time against the formulas.
"""
from __future__ import annotations

import argparse
import glob
import json
import sys
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PALETTE = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
HEADER = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!--\n  Project Drishti · Any data. Any domain. One grammar.\n"
          "  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.\n"
          "  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.\n"
          "  Drawn by tools/bench/charts.py from the benchmark's results files; do not edit by hand.\n-->\n")
INK = "#8a8a86"            # mid-grey text and axes: readable on a light and on a dark page
FONT = "font-family='Inter,Segoe UI,Helvetica,Arial,sans-serif'"
KINDS = [("trade", "trade"), ("search", "search"), ("ctrd", "counterparty page"), ("netting-set", "netting set"),
         ("var-node", "VaR node"), ("var-bank-minus-desk", "bank − desk"), ("var-basket", "basket 50K"),
         ("var-contrib", "contributions"), ("var-path", "P&L path")]
#: sizing formulas (BENCHMARK.md section 12.1)
H0_MB, B_POS, A_NODE = 323, 1023, 19.5 * 1024
H0_BASE_MB, H0_POS = 128, 980          # the heap at start grows with the lake's rows: 128 MB + 980 B a position
HOT_B, LAKE_B = 2783, 1362


def size_label(p: int) -> str:
    return f"{p // 1_000_000}M" if p >= 1_000_000 and p % 1_000_000 == 0 else f"{p // 1000}K"


def nice_scale(v: float, n: int = 5) -> tuple[float, float]:
    """(top, step): a round step (1, 2, 2.5 or 5 × 10^k) and the first multiple of it at or above v."""
    import math
    if v <= 0:
        return 1.0, 0.2
    raw = v / n
    e = 10 ** math.floor(math.log10(raw))
    step = next(m * e for m in (1, 2, 2.5, 5, 10) if m * e >= raw)
    return math.ceil(v / step - 1e-9) * step, step


def short_frame(name: str) -> str:
    """``com.x.y.Outer$Inner.method(Args)`` -> ``Outer$Inner.method``."""
    head = name.split("(")[0]
    parts = head.split(".")
    return ".".join(parts[-2:]) if len(parts) >= 2 else head


def fmt(v: float) -> str:
    if v >= 100:
        return f"{v:,.0f}"
    if v >= 10:
        return f"{v:.1f}"
    return f"{v:.2f}".rstrip("0").rstrip(".") if v else "0"


class Svg:
    def __init__(self, w: int, h: int, title: str, subtitle: str = ""):
        self.w, self.h, self.parts = w, h, []
        self.text(16, 24, title, size=15, weight=600)
        if subtitle:
            self.text(16, 42, subtitle, size=12)

    def text(self, x, y, s, size=12, anchor="start", weight=400, rotate=None):
        rot = f" transform='rotate({rotate} {x} {y})'" if rotate is not None else ""
        self.parts.append(f"<text x='{x:.1f}' y='{y:.1f}' fill='{INK}' font-size='{size}' font-weight='{weight}' "
                          f"text-anchor='{anchor}' {FONT}{rot}>{escape(str(s))}</text>")

    def rect(self, x, y, w, h, color, tip):
        self.parts.append(f"<rect x='{x:.1f}' y='{y:.1f}' width='{max(w, 0.5):.1f}' height='{max(h, 0.5):.1f}' rx='2' fill='{color}'>"
                          f"<title>{escape(tip)}</title></rect>")

    def line(self, x1, y1, x2, y2, opacity=0.35, dash=""):
        d = f" stroke-dasharray='{dash}'" if dash else ""
        self.parts.append(f"<line x1='{x1:.1f}' y1='{y1:.1f}' x2='{x2:.1f}' y2='{y2:.1f}' stroke='{INK}' stroke-opacity='{opacity}' stroke-width='1'{d}/>")

    def legend(self, names, x, y) -> None:
        """One swatch and name a series; the names wrap to a second line rather than run off the right edge."""
        x0 = x
        for i, n in enumerate(names):
            w = 26 + 6.6 * len(n)
            if x + w > self.w - 10 and x > x0:
                x, y = x0, y + 16
            self.parts.append(f"<rect x='{x}' y='{y - 9}' width='10' height='10' rx='2' fill='{PALETTE[i % len(PALETTE)]}'/>")
            self.text(x + 15, y, n, size=12)
            x += w

    def write(self, path: Path) -> None:
        body = "\n".join(self.parts)
        path.write_text(HEADER + f"<svg xmlns='http://www.w3.org/2000/svg' width='{self.w}' height='{self.h}' viewBox='0 0 {self.w} {self.h}' "
                        f"role='img'>\n{body}\n</svg>\n", encoding="utf-8")


def grouped_bars(path, title, subtitle, cats, series, values, unit, width=760):
    """values[(cat, series)] = number; missing cells are left out."""
    if not values:
        return False
    h, top, left, bottom = 380, 78, 64, 330
    svg = Svg(width, h, title, subtitle)
    svg.legend(series, left, 62)
    vmax, step = nice_scale(max(values.values()))
    plot_w = width - left - 20
    k = 0
    while k * step <= vmax + 1e-9:
        y = bottom - (bottom - top) * k * step / vmax
        svg.line(left, y, width - 20, y, 0.25 if k else 0.6)
        svg.text(left - 6, y + 4, fmt(k * step), size=11, anchor="end")
        k += 1
    svg.text(16, (top + bottom) / 2, unit, size=11, anchor="middle", rotate=-90)
    gw = plot_w / len(cats)
    bw = min(28, (gw - 16) / max(1, len(series)) - 2)
    label_bars = len(cats) * len(series) <= 12          # a value on each bar when there are few
    for ci, c in enumerate(cats):
        x0 = left + ci * gw + (gw - len(series) * (bw + 2)) / 2
        for si, s in enumerate(series):
            v = values.get((c, s))
            if v is None:
                continue
            bh = (bottom - top) * v / vmax
            svg.rect(x0 + si * (bw + 2), bottom - bh, bw, bh, PALETTE[si % len(PALETTE)], f"{s}, {c}: {fmt(v)} {unit}")
            if label_bars:
                svg.text(x0 + si * (bw + 2) + bw / 2, bottom - bh - 4, fmt(v), size=10, anchor="middle")
        svg.text(left + ci * gw + gw / 2, bottom + 18, c, size=11, anchor="middle")
    svg.write(path)
    return True


def hbars(path, title, subtitle, rows, unit, color=PALETTE[0], width=900):
    """rows: [(label, value)]."""
    if not rows:
        return False
    left, top, rh = 330, 56, 22
    h = top + rh * len(rows) + 46
    svg = Svg(width, h, title, subtitle)
    vmax, step = nice_scale(max(v for _, v in rows), 4)
    plot_w = width - left - 70
    k = 0
    while k * step <= vmax + 1e-9:
        x = left + plot_w * k * step / vmax
        svg.line(x, top - 4, x, top + rh * len(rows), 0.2)
        svg.text(x, top + rh * len(rows) + 16, fmt(k * step), size=11, anchor="middle")
        k += 1
    for i, (lab, v) in enumerate(rows):
        y = top + i * rh
        short = short_frame(lab)
        short = short if len(short) <= 48 else short[:47] + "…"
        svg.text(left - 8, y + 15, short, size=11, anchor="end")
        svg.rect(left, y + 4, plot_w * v / vmax, rh - 8, color, f"{lab}: {fmt(v)} {unit}")
        svg.text(left + plot_w * v / vmax + 5, y + 15, f"{fmt(v)}", size=11)
    svg.text(left + plot_w / 2, top + rh * len(rows) + 34, unit, size=11, anchor="middle")
    svg.write(path)
    return True


def stacked_hbars(path, title, subtitle, rows, segs, unit, width=820):
    """rows: [(label, {seg: value})]."""
    if not rows:
        return False
    left, top, rh = 150, 78, 34
    h = top + rh * len(rows) + 44
    svg = Svg(width, h, title, subtitle)
    svg.legend(segs, left, 62)
    vmax, step = nice_scale(max(sum(d.values()) for _, d in rows))
    plot_w = width - left - 30
    k = 0
    while k * step <= vmax + 1e-9:
        x = left + plot_w * k * step / vmax
        svg.line(x, top - 4, x, top + rh * len(rows), 0.2)
        svg.text(x, top + rh * len(rows) + 16, fmt(k * step), size=11, anchor="middle")
        k += 1
    svg.text(left + plot_w / 2, top + rh * len(rows) + 34, unit, size=11, anchor="middle")
    for i, (lab, d) in enumerate(rows):
        y, x = top + i * rh, left
        svg.text(left - 8, y + 20, lab, size=12, anchor="end")
        for si, s in enumerate(segs):
            v = d.get(s, 0)
            if v <= 0:
                continue
            w = plot_w * v / vmax
            svg.rect(x, y + 6, w - 2, rh - 12, PALETTE[si % len(PALETTE)], f"{lab}, {s}: {fmt(v)} {unit}")
            x += w
    svg.write(path)
    return True


# -- reading ----------------------------------------------------------------------------------------------------------------------
def load(files) -> list[dict]:
    out = []
    for f in files:
        for line in open(f, encoding="utf-8"):
            if line.strip():
                out.append(json.loads(line))
    return out


def pick(rows, run, measure, **kw):
    return [r for r in rows if r.get("run") == run and r.get("measure") == measure and all(r.get(k) == v for k, v in kw.items())]


def one(rows, run, measure, **kw):
    m = pick(rows, run, measure, **kw)
    return m[-1]["value"] if m and m[-1]["value"] is not None else None


def by_size(rows) -> dict[int, list[dict]]:
    out: dict[int, list[dict]] = {}
    for r in rows:
        if r.get("positions"):
            out.setdefault(r["positions"], []).append(r)
    return dict(sorted(out.items()))


# -- the charts -------------------------------------------------------------------------------------------------------------------
def chart_rps(sizes, to):
    vals, series = {}, []
    for p, rows in sizes.items():
        for mode, run in (("single", "single-21-g1"), ("cluster", "cluster-21-g1")):
            s = f"{size_label(p)} {mode}"
            for r in pick(rows, run, "users"):
                if r["users"] in (50, 100, 200):
                    vals[(f"{r['users']} users", s)] = r["value"]
                    if s not in series:
                        series.append(s)
    return grouped_bars(to / "rps-by-users.svg", "Requests a second at 50, 100 and 200 users",
                        "VaR and trades mix (50/50), Java 21, G1; single server against a three-node cluster on one machine",
                        ["50 users", "100 users", "200 users"], series, vals, "requests / s")


def chart_p99(sizes, to):
    done = []
    for p, rows in sizes.items():
        vals = {}
        for r in pick(rows, "single-21-g1", "users"):
            for k, lab in KINDS:
                d = r.get("by_kind", {}).get(k)
                if d and r["users"] in (50, 100, 200):
                    vals[(lab, f"{r['users']} users")] = d["p99"]
        cats = [lab for k, lab in KINDS if any(c == lab for c, _ in vals)]
        name = f"p99-by-request-{size_label(p).lower()}.svg"
        if grouped_bars(to / name, f"p99 latency by request type, {p:,} positions",
                        "single server, Java 21, G1; VaR and trades mix", cats, ["50 users", "100 users", "200 users"], vals, "seconds", width=900):
            done.append(name)
    return done


STAGE_SEGS = [("read", ("read",)), ("intake", ("intake",)), ("sort and sums", ("sort", "aggregate")), ("hot-file write", ("write",)),
              ("other", ("other",))]


def chart_first_open(sizes, to):
    rows = []
    for p, rs in sizes.items():
        for run in ("single-21-g1", "cluster-21-g1"):
            for st in pick(rs, run, "first_open_stages"):
                secs = one(rs, run, "first_open_s", businessDate=st["businessDate"])
                if secs is None:
                    continue
                share = st["value"]
                d = {name: secs * sum(share.get(k, 0) or 0 for k in keys) for name, keys in STAGE_SEGS}
                rows.append((f"{size_label(p)} {run.split('-')[0]} {st['businessDate'][5:]}", d))
    return stacked_hbars(to / "first-open-stages.svg", "A date's first open, split by stage",
                         "Java 21, G1; seconds end to end, split by the load's CPU samples (JFR); one bar a date",
                         rows, [n for n, _ in STAGE_SEGS], "seconds")


def chart_heap(sizes, to):
    """Measured heap and RSS beside the two sizing formulas of section 12.1: the linear one and the one with the scope-set term."""
    series = ["linear formula", "formula with scope sets", "heap after GC, two dates", "heap after GC, end", "RSS, end"]
    vals = {}
    for p, rs in sizes.items():
        loads = pick(rs, "single-21-g1", "server_load_ms")
        n_nodes = loads[-1].get("nodes") if loads else None
        agg = loads[-1].get("aggregateBytes") if loads else None
        cat = f"{p:,} positions"
        if n_nodes:
            vals[(cat, series[0])] = (H0_MB * 1e6 + 2 * (B_POS * p + A_NODE * n_nodes)) / 1e9
            per_node = (agg / n_nodes) if agg else A_NODE
            vals[(cat, series[1])] = (H0_BASE_MB * 1e6 + H0_POS * p + 2 * (B_POS * p + per_node * n_nodes + p * n_nodes / 16)) / 1e9
        for s, m in ((series[2], "heap_after_gc_mb_two_dates"), (series[3], "heap_after_gc_mb_end"), (series[4], "rss_mb_end")):
            v = one(rs, "single-21-g1", m)
            if v is not None:
                vals[(cat, s)] = v / 1000
    cats = [f"{p:,} positions" for p in sizes]
    return grouped_bars(to / "heap-vs-formula.svg", "Heap and resident memory against the sizing formulas",
                        "single server, Java 21, G1, two dates open; formulas of section 12.1", cats, series, vals, "GB", width=900)


def chart_gc(sizes, to):
    vals, series = {}, []
    configs = [("single-21-g1", "Java 21 G1"), ("single-25-g1", "Java 25 G1"), ("single-21-zgc", "Java 21 ZGC"), ("single-25-zgc", "Java 25 ZGC")]
    for p, rs in sizes.items():
        s = f"{p:,} positions"
        for run, lab in configs:
            v = one(rs, run, "jfr_longest_gc_pause_ms")
            if v is not None:
                vals[(lab, s)] = v
                if s not in series:
                    series.append(s)
    return grouped_bars(to / "gc-longest-pause.svg", "Longest GC pause by Java version and collector",
                        "single server over the whole run (start, first opens, 50 to 200 users); from JFR", [l for _, l in configs], series, vals, "ms")


def chart_frames(sizes, to):
    done = []
    for p, rs in sizes.items():
        for measure, what, name in (("first_open_hot_methods", "a date's first open", "first-open"), ("jfr_hot_methods", "the run under load", "load")):
            m = [r for r in rs if r.get("measure") == measure and r.get("run") == "single-21-g1"]
            if not m:
                continue
            rows = [(x["name"], x["pct"]) for x in m[0]["value"][:12]]
            fn = f"hot-frames-{name}-{size_label(p).lower()}.svg"
            if hbars(to / fn, f"Where the time goes: hottest CPU frames, {what}, {p:,} positions",
                     "share of JFR execution samples (top frame), single server, Java 21, G1", rows, "% of samples", PALETTE[0 if name == "first-open" else 2]):
                done.append(fn)
    return done


def chart_disk(sizes, to):
    vals, series = {}, ["measured", "formula"]
    for p, rs in sizes.items():
        lake = one(rs, "loader-java", "disk_lake_pnl_mb_per_date")
        if lake is not None:
            vals[(f"lake a date, {size_label(p)}", "measured")] = lake / 1000
            vals[(f"lake a date, {size_label(p)}", "formula")] = LAKE_B * p / 1e9
        hot = [r for r in rs if r.get("measure") == "disk_hot_files_mb" and r.get("run") == "single-21-g1"]
        if hot:
            vals[(f"hot file a date, {size_label(p)}", "measured")] = hot[0]["value"] / hot[0]["files"] / 1000
            vals[(f"hot file a date, {size_label(p)}", "formula")] = HOT_B * p / 1e9
    cats = []
    for c, _ in vals:
        if c not in cats:
            cats.append(c)
    a = grouped_bars(to / "disk-vs-formula.svg", "Disk a date against the formulas",
                     "P&L vectors in the lake (Java loader; 1,362 B a position) and the hot file (2,783 B a position)", cats, series, vals, "GB")
    vals2, series2 = {}, ["pnl-vector", "all five kinds"]
    for p, rs in sizes.items():
        for s, m in (("pnl-vector", "load_pnl-vector_s"), ("all five kinds", "load_total_s")):
            v = one(rs, "loader-java", m)
            if v is not None:
                vals2[(f"{p:,} positions", s)] = v
    b = grouped_bars(to / "load-time.svg", "Load time with the Java loader", "two dates of JSON Lines into Delta, Java 21",
                     [f"{p:,} positions" for p in sizes], series2, vals2, "seconds", width=560)
    return a, b


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("files", nargs="*", help="results JSON lines (default: tools/bench/results/2.9.8-*.jsonl)")
    ap.add_argument("--to", default=str(ROOT / "docs" / "admin" / "images" / "benchmark"))
    a = ap.parse_args(argv)
    files = a.files or sorted(glob.glob(str(ROOT / "tools" / "bench" / "results" / "2.9.8-*.jsonl")))
    if not files:
        ap.error("no results files")
    to = Path(a.to)
    to.mkdir(parents=True, exist_ok=True)
    sizes = by_size(load(files))
    written = {"rps": chart_rps(sizes, to), "p99": chart_p99(sizes, to), "first_open": chart_first_open(sizes, to),
               "heap": chart_heap(sizes, to), "gc": chart_gc(sizes, to), "frames": chart_frames(sizes, to), "disk": chart_disk(sizes, to)}
    print(json.dumps(written))
    return 0


if __name__ == "__main__":
    sys.exit(main())
