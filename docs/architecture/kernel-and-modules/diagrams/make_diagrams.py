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

"""The diagrams of docs/architecture/KERNEL_AND_MODULES.md, written as self-contained SVG files beside this script.

    python3 docs/architecture/kernel-and-modules/diagrams/make_diagrams.py

Each SVG carries its own opaque card background (legible on light and dark pages), the system font stack (no external font or link) and a
viewBox, so it scales to any width; the text is at least 13 px at the drawing's natural width, which stays narrow (760 px) for phones.
"""
from __future__ import annotations

from html import escape
from pathlib import Path

HERE = Path(__file__).resolve().parent
W = 760
FONT = "system-ui, -apple-system, 'Segoe UI', Roboto, 'DejaVu Sans', Arial, sans-serif"
MONO = "ui-monospace, Menlo, Consolas, 'DejaVu Sans Mono', monospace"

# the Drishti palette (the console's tokens, light card)
CARD, INK, MUTED, LINE = "#f7f9fc", "#1a2333", "#5c6a80", "#cdd6e3"
NAVY, AMBER, BLUE, GREEN, RED, TEAL, VIOLET = "#12324f", "#e8a33a", "#1f5fbf", "#1e8a55", "#c62828", "#2bb3a3", "#6a4fb3"
TINT = {NAVY: "#e3eaf3", AMBER: "#fbefd9", BLUE: "#e1ebfa", GREEN: "#dff1e7", RED: "#fbe3e3", TEAL: "#dcf3f0", VIOLET: "#ebe5f7",
        MUTED: "#eceff4"}
COPY = "Copyright (c) 2026 Ashutosh Sinha. All rights reserved. PROPRIETARY AND CONFIDENTIAL."


def svg(name: str, title: str, subtitle: str, h: int, body: list[str]) -> None:
    markers = "".join(
        f'<marker id="a{c[1:]}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
        f'<path d="M0,0L10,5L0,10z" fill="{c}"/></marker>' for c in (INK, BLUE, GREEN, RED, AMBER, TEAL, MUTED, NAVY))
    out = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {h}" width="{W}" height="{h}" role="img" '
           f'aria-label="{escape(title)}" font-family="{FONT}">\n<!-- {COPY} -->\n<title>{escape(title)}</title>\n'
           f'<defs>{markers}</defs>\n'
           f'<rect x="0.5" y="0.5" width="{W - 1}" height="{h - 1}" rx="14" fill="{CARD}" stroke="{LINE}"/>\n'
           f'<text x="24" y="38" font-size="20" font-weight="700" fill="{NAVY}">{escape(title)}</text>\n'
           f'<text x="24" y="60" font-size="13" fill="{MUTED}">{escape(subtitle)}</text>\n'
           + "\n".join(body) + "\n</svg>\n")
    (HERE / f"{name}.svg").write_text(out, encoding="utf-8")
    print(f"wrote {name}.svg")


def text(x, y, s, size=14, color=INK, weight=400, anchor="start", mono=False, italic=False) -> str:
    fam = f' font-family="{MONO}"' if mono else ""
    it = ' font-style="italic"' if italic else ""
    return (f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{color}" text-anchor="{anchor}"{fam}{it}>'
            f'{escape(s)}</text>')


def box(x, y, w, h, color=NAVY, fill=None, lines=(), size=14, head=None, rx=8, dash=False, mono=False, center=False, stroke_w=1.5):
    fill = fill or TINT.get(color, "#ffffff")
    d = ' stroke-dasharray="6 4"' if dash else ""
    parts = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{color}" stroke-width="{stroke_w}"{d}/>']
    cy = y + 22
    if head:
        parts.append(text(x + w / 2 if center else x + 12, cy, head, size=size + 1, color=color, weight=700,
                          anchor="middle" if center else "start"))
        cy += size + 6
    for ln in lines:
        parts.append(text(x + w / 2 if center else x + 12, cy, ln, size=size, color=INK, anchor="middle" if center else "start", mono=mono))
        cy += size + 5
    return "\n".join(parts)


def arrow(x1, y1, x2, y2, color=INK, dash=False, width=1.8, curve=None) -> str:
    d = ' stroke-dasharray="6 4"' if dash else ""
    if curve:
        cx1, cy1, cx2, cy2 = curve
        path = f"M{x1},{y1} C{cx1},{cy1} {cx2},{cy2} {x2},{y2}"
    else:
        path = f"M{x1},{y1} L{x2},{y2}"
    return f'<path d="{path}" fill="none" stroke="{color}" stroke-width="{width}"{d} marker-end="url(#a{color[1:]})"/>'


def label(x, y, s, color=MUTED, size=12, anchor="middle"):
    return text(x, y, s, size=size, color=color, anchor=anchor, italic=True)


# ---------------------------------------------------------------------------------------------------------------------------------
def layers():
    b = []
    b.append(text(24, 92, "DISTRIBUTIONS · configuration only", 12, MUTED, 700))
    b.append(box(24, 102, 348, 92, AMBER, head="drishti.finance", lines=["brand · theme · lenses · Home · Help", "modules: banking, capitalmarkets, economy"]))
    b.append(box(388, 102, 348, 92, TEAL, head="drishti.genomics", lines=["brand · theme · lenses · Home · Help", "modules: genomics"]))
    b.append(arrow(198, 194, 198, 222, AMBER)); b.append(arrow(562, 194, 562, 222, TEAL))
    b.append(text(24, 240, "MODULES · signed .dmod packages, loaded from drishti.modules.path", 12, MUTED, 700))
    mods = [("banking", "VaR · exposure · SIMM", BLUE), ("capitalmarkets", "equities · funds · rates", BLUE),
            ("economy", "world · public sector", BLUE), ("genomics", "cohorts · variants (sample)", TEAL)]
    for i, (m, d, c) in enumerate(mods):
        x = 24 + i * 180
        b.append(box(x, 250, 168, 86, c, head=m, lines=["Java · Python · packs", d], size=12))
    b.append(f'<rect x="24" y="352" width="712" height="40" rx="8" fill="{NAVY}"/>')
    b.append(text(380, 377, "KERNEL API 1.x  ·  SPIs · Rachana 1 · contract format 1 · Elements core · REST namespace · events",
                  13, "#ffffff", 700, "middle"))
    b.append(f'<rect x="24" y="404" width="712" height="196" rx="10" fill="#ffffff" stroke="{NAVY}" stroke-width="2"/>')
    b.append(text(40, 428, "THE KERNEL · domain-free BI and aggregation", 14, NAVY, 700))
    cols = [("Grammar & screens", ["Rachana, Sutras", "packs runtime", "view pipeline", "search, Explain"]),
            ("Analysis", ["Pivot engine", "BI, tables, models", "Notebook", "Elements core"]),
            ("Data", ["lakes (Delta, Iceberg)", "loaders, inbox", "bindings (kind→source)", "demo lake"]),
            ("Platform", ["registry & licence", "security, approvals", "cluster, Health", "Python pool + sandbox"])]
    for i, (h, ls) in enumerate(cols):
        b.append(box(40 + i * 172, 440, 160, 146, NAVY, head=h, lines=ls, size=12))
    b.append(text(24, 628, "CONNECTORS · the kernel's drivers (never in a module)", 12, MUTED, 700))
    for i, c in enumerate(["Delta", "Iceberg", "Kafka", "JDBC", "S3", "files", "REST", "demo"]):
        b.append(box(24 + i * 89, 638, 81, 34, GREEN, lines=[c], size=13, center=True, rx=17))
    b.append(arrow(380, 604, 380, 634, GREEN))
    b.append(text(380, 700, "the site's data · credentials in the kernel's secret store", 13, MUTED, 400, "middle"))
    svg("layers", "Kernel, modules, distributions", "One kernel; modules plug in through the kernel API; a distribution file picks them and the brand.", 720, b)


def anatomy():
    b = [box(24, 80, 712, 46, NAVY, fill=NAVY, lines=[], rx=8)]
    b.append(text(40, 109, "banking-3.0.0.dmod", 17, "#ffffff", 700, mono=True))
    b.append(text(720, 109, "a signed zip · unpacked into modules/banking/3.0.0/", 13, "#dfe7f2", 400, "end"))
    groups = [
        ("Manifest & trust", NAVY, [("module.yaml", "id, version, kernel range, needs, licence"),
                                    ("META-INF/", "DMOD.MF hashes · DMOD.SIG Ed25519 · cert")]),
        ("Code", BLUE, [("java/", "drishti-banking, drishti-compute-banking"), ("python/", "drishti_banking (kernel's pool)")]),
        ("Data (kinds, not sources)", GREEN, [("data/needs.yaml", "41 needs: trade, scenario-pv …"),
                                               ("packs/", "4 packs: contracts, Sutras, samples"),
                                               ("lakes/", "var-official, ccr-official forms")]),
        ("Experience", AMBER, [("console/", "routes, templates, static, help.yaml"), ("elements/drishti-risk/", "domain JS library + .d.ts"),
                               ("lenses/ · functions.yaml", "6 lenses · 60 words · Home cards")]),
        ("Content & settings", VIOLET, [("bi/ · feeds/", "reports, models, notebooks"), ("docs/ · i18n/", "guides, tutorials, Help, glossary"),
                                        ("settings/ · tokens/", "JSON Schema · risk:write")]),
    ]
    y = 142
    for title, c, rows in groups:
        hgt = 34 + 44 * len(rows)
        b.append(f'<rect x="24" y="{y}" width="712" height="{hgt}" rx="8" fill="{TINT[c]}" stroke="{c}" stroke-width="1.5"/>')
        b.append(text(40, y + 22, title, 14, c, 700))
        for i, (p, d) in enumerate(rows):
            ry = y + 32 + i * 44
            b.append(f'<rect x="40" y="{ry}" width="680" height="36" rx="6" fill="#ffffff" stroke="{LINE}"/>')
            b.append(text(54, ry + 23, p, 14, INK, 700, mono=True))
            b.append(text(706, ry + 23, d, 13, MUTED, 400, "end"))
        y += hgt + 10
    b.append(f'<rect x="24" y="{y}" width="712" height="52" rx="8" fill="{TINT[RED]}" stroke="{RED}" stroke-width="2" stroke-dasharray="7 4"/>')
    b.append(text(40, y + 22, "Never inside", 14, RED, 700))
    b.append(text(40, y + 42, "connector files or plugins · hosts · URLs of data · passwords · keys · lake roots", 13, INK))
    svg("dmod-anatomy", "Anatomy of a module package: banking", "Everything the capability needs, in one signed file; the data comes from the kernel.", y + 72, b)


def load_sequence():
    steps = [("1  Discover", "list modules.path · read module.yaml · pick the active version"),
             ("2  Verify", "V1 manifest · V2 signature · V3 kernel range (vermagic)"),
             ("3  Resolve dependencies", "V4 requires / conflicts · topological order"),
             ("4  Ask the registry", "V5 licence feature · V6 claims · switch on?"),
             ("5  Bind data", "every need → a source, or DEMO, or UNBOUND (degrades)"),
             ("6  Class loader + context", "own loader · child Spring context · DrishtiModule.start"),
             ("7  Contribute", "SPIs · packs · Sutras · lenses · functions · REST /api/v1/risk"),
             ("8  Console plugin", "routes · templates · /m/banking/static · Help merged")]
    b, y = [], 84
    for i, (h, d) in enumerate(steps):
        c = GREEN if i == len(steps) - 1 else BLUE
        b.append(box(24, y, 470, 58, c, head=h, lines=[d], size=13))
        if i < len(steps) - 1:
            b.append(arrow(259, y + 58, 259, y + 74, BLUE))
        if i in (1, 2, 3, 5):
            b.append(arrow(494, y + 29, 548, y + 29, RED, dash=True))
        y += 74
    b.append(box(552, 150, 184, 330, RED, head="Refused", size=13, lines=[
        "absent everywhere:", "no class, bean, API,", "page, word, Help,", "asset or AI tool", "", "reason in words in", "Health and", "Admin → Modules", "",
        "the kernel and other", "modules keep running"]))
    b.append(box(552, 500, 184, 120, AMBER, head="Degraded", size=13, lines=["a required need", "unbound: only the", "functions that use it", "are off, with why"]))
    b.append(arrow(494, 84 + 4 * 74 + 29, 548, 560, AMBER, dash=True, curve=(520, 84 + 4 * 74 + 29, 520, 560)))
    b.append(box(24, y + 4, 712, 48, GREEN, fill=TINT[GREEN], lines=["LOADED · events: module.started · Health: one row per module · cluster fingerprint gossiped"], size=13))
    svg("load-sequence", "Loading a module", "From the folder to a running capability, in order; each check refuses with a reason.", y + 72, b)


def data_binding():
    b = []
    b.append(text(24, 92, "BANKING DECLARES (data/needs.yaml)", 12, MUTED, 700))
    b.append(text(282, 92, "THE KERNEL BINDS (config/bindings.yaml)", 12, MUTED, 700))
    b.append(text(560, 92, "THE SITE'S SOURCES", 12, MUTED, 700))
    needs = [("trade", "read · live"), ("scenario-pv", "read · live"), ("pnl-vector", "any-of"), ("risk-hierarchy", "read"),
             ("fx-vol-surface", "read"), ("position-sensitivity", "required"), ("var-official", "write")]
    ys = []
    for i, (k, u) in enumerate(needs):
        y = 102 + i * 62
        c = RED if k == "position-sensitivity" else BLUE
        b.append(box(24, y, 212, 50, c, lines=[], rx=8))
        b.append(text(36, y + 22, k, 14, INK, 700, mono=True)); b.append(text(36, y + 41, u, 12, MUTED))
        ys.append(y + 25)
    b.append(f'<rect x="270" y="102" width="250" height="428" rx="10" fill="#ffffff" stroke="{NAVY}" stroke-width="2"/>')
    rows = [("trade", "trade-endur + calypso + murex", "live: trade-kafka"), ("scenario-pv", "risk-store", "live: var-pv-kafka"),
            ("pnl-vector", "risk-store", ""), ("risk-hierarchy", "risk-store", ""), ("fx-vol-surface", "demo: true", ""),
            ("position-sensitivity", "— UNBOUND —", "FRSA, SIMM off"), ("var-official", "write: risklake", "banking.var-official")]
    for i, (k, s1, s2) in enumerate(rows):
        y = ys[i]
        col = RED if "UNBOUND" in s1 else (AMBER if "demo" in s1 else NAVY)
        b.append(text(284, y - 4, s1, 13, col, 700))
        if s2:
            b.append(text(284, y + 13, s2, 12, MUTED))
        b.append(arrow(236, y, 268, y, BLUE if col != RED else RED, dash=col == RED))
    srcs = [(110, "risklake (Delta)", ["risk-store · trade-endur", "trade-calypso · trade-murex"], GREEN),
            (222, "demo lake", ["packs' samples", "DEMO badge on screens"], AMBER),
            (334, "Kafka", ["trade-kafka", "var-pv-kafka"], VIOLET)]
    for y, h, ls, c in srcs:
        b.append(box(560, y, 176, 96 if len(ls) > 1 else 70, c, head=h, lines=ls, size=12))
    # arrows from the binding box to the sources: each row to one point on the source's left edge (few crossings)
    def link(y0, y1, c, dash=False):
        b.append(arrow(520, y0, 558, y1, c, dash=dash, curve=(542, y0, 536, y1)))
    link(ys[0] - 8, 128, GREEN); link(ys[1] - 8, 146, GREEN); link(ys[2] - 4, 164, GREEN); link(ys[3] - 4, 182, GREEN)
    link(ys[0] + 10, 364, VIOLET); link(ys[1] + 10, 392, VIOLET)
    link(ys[4] - 4, 262, AMBER); link(ys[6] - 4, 198, GREEN, dash=True)
    b.append(box(560, 456, 176, 74, RED, fill="#ffffff", head="Secret store", lines=["Kafka SASL, JDBC users", "for connectors only"], size=12, dash=True))
    b.append(arrow(648, 456, 648, 434, RED, dash=True))
    b.append(box(24, 548, 712, 150, NAVY, fill=TINT[NAVY], head="How a kind finds its data (first hit wins)", size=13, lines=[
        "1  the site's binding        2  the distribution's default",
        "3  a lake table named by the contract's hint (AUTO, confirm)",
        "4  the demo lake while demo is on (DEMO)    5  otherwise UNBOUND",
        "One binding per kind, shared by every module · contract ranges must meet",
        "Changes: four eyes, audited, hot · credentials only in the kernel's secret store"]))
    svg("data-binding", "Data needs and kernel-controlled binding", "Banking names kinds; the kernel decides which connector or lake table serves each.", 718, b)


def distributions():
    b = []
    b.append(box(24, 84, 340, 214, AMBER, fill="#0e1628", rx=10))
    b.append(f'<rect x="24" y="84" width="340" height="40" rx="10" fill="#0b1a33"/>')
    b.append(text(40, 110, "◆ Drishti Finance", 16, "#ffffff", 700)); b.append(text(350, 110, "Risk lens", 12, AMBER, 700, "end"))
    for i, (t, v) in enumerate([("VaR 99% 1D", "48.2m"), ("ES 97.5%", "71.9m"), ("Limit use", "64%")]):
        x = 36 + i * 108
        b.append(f'<rect x="{x}" y="138" width="100" height="58" rx="6" fill="#16213a" stroke="#26345a"/>')
        b.append(text(x + 8, 158, t, 11, "#93a0b8")); b.append(text(x + 8, 184, v, 17, "#e7ecf5", 700, mono=True))
    for i, t in enumerate(["VAR BANK", "EXP CPTY:CP-ALDERSHOT", "CRV CRV-USD-OIS"]):
        b.append(f'<rect x="36" y="{208 + i * 28}" width="316" height="22" rx="4" fill="#16213a" stroke="{AMBER}"/>')
        b.append(text(46, 224 + i * 28, t, 12, "#e7ecf5", mono=True))
    b.append(box(396, 84, 340, 214, TEAL, fill="#f3faf9", rx=10))
    b.append(f'<rect x="397" y="85" width="338" height="39" rx="10" fill="#ffffff"/>')
    b.append(f'<line x1="397" y1="124" x2="735" y2="124" stroke="{TEAL}" stroke-width="2"/>')
    b.append(text(412, 110, "⬢ Drishti Genomics", 16, "#14302d", 700)); b.append(text(722, 110, "Cohorts lens", 12, TEAL, 700, "end"))
    for i, (t, v) in enumerate([("Samples", "12,480"), ("Variants", "4.71m"), ("QC pass", "97.3%")]):
        x = 408 + i * 108
        b.append(f'<rect x="{x}" y="138" width="100" height="58" rx="6" fill="#ffffff" stroke="#cfe5e2"/>')
        b.append(text(x + 8, 158, t, 11, "#5a7a76")); b.append(text(x + 8, 184, v, 17, "#14302d", 700, mono=True))
    for i, t in enumerate(["COHORT BRCA-2026", "VARIANT chr17:43044295", "SAMPLE S-00042"]):
        b.append(f'<rect x="408" y="{208 + i * 28}" width="316" height="22" rx="4" fill="#ffffff" stroke="{TEAL}"/>')
        b.append(text(418, 224 + i * 28, t, 12, "#14302d", mono=True))
    b.append(box(24, 330, 340, 70, AMBER, head="drishti.finance.yaml", lines=["navy theme · Risk default · Finance Help"], size=13, mono=False))
    b.append(box(396, 330, 340, 70, TEAL, head="drishti.genomics.yaml", lines=["teal theme · Cohorts default · Genomics Help"], size=13))
    b.append(arrow(194, 330, 194, 302, AMBER)); b.append(arrow(566, 330, 566, 302, TEAL))
    mods = [(24, "banking", BLUE), (138, "capitalmarkets", BLUE), (252, "economy", BLUE), (566, "genomics", TEAL)]
    for x, m, c in mods:
        b.append(box(x, 432, 108 if m != "genomics" else 170, 40, c, lines=[m], size=13, center=True))
    b.append(arrow(78, 432, 120, 404, AMBER)); b.append(arrow(192, 432, 194, 404, AMBER)); b.append(arrow(306, 432, 270, 404, AMBER))
    b.append(arrow(651, 432, 600, 404, TEAL))
    b.append(text(450, 456, "(not installed here: banking …)", 12, MUTED, italic=True, anchor="middle"))
    b.append(f'<rect x="24" y="500" width="712" height="64" rx="10" fill="{NAVY}"/>')
    b.append(text(380, 528, "ONE KERNEL BUILD", 16, "#ffffff", 700, "middle"))
    b.append(text(380, 550, "same jar · same connectors · Pivot, BI, Build, Data, Admin, the command line", 13, "#dfe7f2", 400, "middle"))
    b.append(arrow(194, 500, 194, 476, NAVY)); b.append(arrow(651, 500, 651, 476, NAVY))
    svg("distributions", "White label: two products from one kernel", "Name, logo, theme, lenses, menus, Home and Help come from the distribution file.", 590, b)


def phases():
    ph = [("P1", "Foundations: kernel boundary to zero", BLUE, "2.9.11.i1",
           ["data standards · kernel API jar · KindProvider", "domain strings → 0 · drill under 60 min · memory"],
           "kernel boots with no module · VaR exactly equal"),
          ("P2", "Modules on one node", BLUE, "2.9.11.i2",
           [".dmod · pages, route table · clean unload", "SDK (archetype, Maven plugin) · deploy CLI + Admin"],
           "banking as .dmod · install, remove, reinstall clean"),
          ("P3", "Cluster and white label", TEAL, "2.9.11.i3",
           ["placement per part · singletons · heavy guards", "cluster deploy · rolling · distributions"],
           "banking on main + b only · genomics from config"),
          ("P4", "Trust", AMBER, "2.9.11.j",
           ["publisher signing · revocation · signed licence", "taint · CSP + SRI · signed kernel"],
           "tampered or unlicensed module absent"),
          ("P5", "Repository and hot load", MUTED, "later",
           ["internal module repository", "hot load / unload of code"],
           "50 load/unload cycles, no leak")]
    b, y = [], 84
    for i, (p, t, c, rel, ls, proof) in enumerate(ph):
        b.append(f'<path d="M24,{y} L104,{y} L124,{y + 52} L104,{y + 104} L24,{y + 104} Z" fill="{c}"/>')
        b.append(text(64, y + 60, p, 22, "#ffffff", 800, "middle"))
        b.append(f'<rect x="134" y="{y}" width="602" height="104" rx="8" fill="{TINT[c]}" stroke="{c}" stroke-width="1.5"/>')
        b.append(text(148, y + 24, t, 16, INK, 700))
        b.append(f'<rect x="{722 - 9 * len(rel) - 16}" y="{y + 9}" width="{9 * len(rel) + 16}" height="22" rx="11" fill="#ffffff" stroke="{c}"/>')
        b.append(text(722 - 8, y + 25, rel, 12, c if c != MUTED else INK, 700, "end"))
        for j, ln in enumerate(ls):
            b.append(text(148, y + 48 + j * 20, "• " + ln, 13, INK))
        b.append(text(148, y + 94, "proof: " + proof, 13, GREEN, 700))
        y += 116
    svg("phases", "Migration in three releases, banking as the proving ground", "i1, i2, i3, then trust in j; each phase ends with a proof; VaR exactly equal.", y + 12, b)


def analogy():
    rows = [("kernel", "Drishti kernel (server, console, Elements core, connectors)"), (".ko module", ".dmod module package"),
            ("module ABI · vermagic", "kernel API version · manifest's kernel: range"), ("modinfo", "module.yaml · module info"),
            ("depmod · modprobe", "requires: with version ranges, resolved first"), ("module signing · Secure Boot", "signed .dmod · trusted publishers · licence"),
            ("insmod · rmmod · lsmod", "module install · remove · list · Admin → Modules"), ("taint", "Health: TAINTED (dev-signed, override …)"),
            ("device drivers", "connectors: the kernel's, bound to kinds"), ("distribution (Debian …)", "distribution file (drishti.finance …)")]
    b = [f'<rect x="24" y="80" width="712" height="34" rx="6" fill="{NAVY}"/>',
         text(40, 102, "Linux", 14, "#ffffff", 700), text(276, 102, "Drishti", 14, "#ffffff", 700)]
    y = 114
    for i, (l, d) in enumerate(rows):
        fill = "#ffffff" if i % 2 == 0 else TINT[NAVY]
        b.append(f'<rect x="24" y="{y}" width="712" height="40" fill="{fill}"/>')
        b.append(text(40, y + 26, l, 14, NAVY, 700, mono=True))
        b.append(text(276, y + 26, d, 14, INK))
        y += 40
    b.append(f'<rect x="24" y="80" width="712" height="{y - 80}" rx="6" fill="none" stroke="{LINE}"/>')
    b.append(f'<line x1="262" y1="80" x2="262" y2="{y}" stroke="{LINE}"/>')
    svg("linux-analogy", "The Linux-module analogy", "The owner's frame: modules are compiled and packaged apart, and the kernel runs them.", y + 24, b)


def page_flow():
    """A module page's request: browser -> console dispatcher -> live route table -> module template + Elements -> API proxy -> server."""
    b = []
    b.append(box(24, 84, 180, 92, NAVY, head="Browser", lines=["GET /m/genomics/", "variants/BRCA-2026"], size=13, mono=False))
    b.append(box(24, 196, 180, 74, NAVY, fill="#ffffff", head="the open page", lines=["Elements grid + chart"], size=12))
    b.append(box(236, 84, 248, 186, BLUE, head="Console dispatcher (kernel)", size=12, lines=[
        "1 identity · CSRF · lens", "2 look up the live route table", "3 module on for this person?",
        "   its function allowed?", "4 render in the kernel shell", "   (the module's namespace)"]))
    b.append(box(516, 84, 220, 124, VIOLET, head="Live route table", size=12, lines=[
        "/m/genomics/variants/{cohort}", "  → genomics:variant_explorer.html", "/m/banking/var  (alias /var)",
        "/m/oldmod/…  → TOMBSTONE (410)"]))
    b.append(box(516, 220, 220, 50, MUTED, fill="#ffffff", lines=["rebuilt on module.* events", "no console restart"], size=12, dash=True))
    b.append(arrow(204, 120, 234, 120, NAVY))
    b.append(arrow(484, 130, 514, 130, BLUE)); b.append(arrow(514, 160, 486, 160, VIOLET))
    b.append(box(236, 302, 248, 130, AMBER, head="Module template", size=12, lines=[
        "genomics:variant_explorer.html", "{% extends 'kernel/page.html' %}", "Elements macros only", "kernel context: user, lens, theme,",
        "i18n, csrf, module.settings"]))
    b.append(box(516, 302, 220, 130, TEAL, head="Elements (static)", size=12, lines=[
        "/static/elements/… (kernel)", "/m/genomics/static/0.1.0/", "  drishti_genomics.js", "CSP 'self' + SRI hashes"]))
    b.append(arrow(360, 270, 360, 300, BLUE)); b.append(arrow(484, 366, 514, 366, AMBER))
    b.append(arrow(236, 380, 120, 272, AMBER, curve=(160, 380, 120, 330))); b.append(label(150, 300, "HTML"))
    b.append(box(24, 464, 330, 104, GREEN, head="Kernel API proxy (console)", size=12, lines=[
        "fetch('/m/genomics/api/variants?…')", "same origin · session cookie · CSRF on writes", "→ /api/v1/genomics/… as the person"]))
    b.append(arrow(80, 270, 80, 462, GREEN, dash=True)); b.append(label(116, 420, "data calls"))
    b.append(box(406, 464, 330, 104, NAVY, head="Server: the module's child context", size=12, lines=[
        "gates: module → function → permission → audit", "@ModuleApi controllers under /api/v1/genomics", "Kinds read as the person (row rules)"]))
    b.append(arrow(354, 516, 404, 516, GREEN))
    b.append(box(24, 590, 712, 50, RED, fill="#ffffff", size=12, lines=[
        "Never: a module template reaching another module's templates, the server directly, other origins, or inline script."]))
    svg("page-flow", "A module page, request to data", "Routes are looked up per request; templates render inside the kernel's shell; data goes through the proxy.", 662, b)


def unload_sequence():
    steps = [("0  Approved", "four eyes (remove) or the switch (disable) · cluster config version N+1", NAVY),
             ("1  Gate closes", "registry: module LEAVING · new requests refused · route entries → tombstones", RED),
             ("2  Tell", "module.leaving on the bus and drishti-net · open tabs get the notice", AMBER),
             ("3  Drain", "in-flight requests ≤ 30 s · jobs, feeds, Python runs: cancel after 60 s", AMBER),
             ("4  Withdraw contributions", "SPIs · functions, words · AI tools · exports · KindProviders · caches", BLUE),
             ("5  Withdraw content", "packs · Sutras · lenses · menus · Home cards · Help · search · glossary", BLUE),
             ("6  Console", "template namespace · static mount · Elements entries · hooks · settings pages", BLUE),
             ("7  Server", "stop() · child context closed (REST gone) · Python path removed · loader dropped (P5)", BLUE),
             ("8  Mark saved artefacts", "views, workspaces, dashboards, reports, exports: 'needs module X'", VIOLET),
             ("9  Record", "audit line · metrics series ended · Health REMOVED · fingerprint · module.removed", GREEN)]
    b, y = [], 84
    for i, (h, d, c) in enumerate(steps):
        b.append(box(24, y, 520, 52, c, head=h, lines=[d], size=12))
        if i < len(steps) - 1:
            b.append(arrow(284, y + 52, 284, y + 64, MUTED))
        y += 64
    b.append(box(568, 84, 168, 180, GREEN, head="Stays", size=12, lines=["the lake data", "bindings (orphaned)", "users' saved items", "  (marked, kept)",
                                                                       "the .dmod files", "  (until prune)", "its state folder"]))
    b.append(box(568, 280, 168, 162, RED, head="Gone", size=12, lines=["every page (410)", "every word, menu,", "card, Help page,", "search hit, asset,",
                                                                   "API, job, AI tool,", "cache entry"]))
    b.append(box(568, 458, 168, 170, NAVY, fill="#ffffff", head="P2 restart?", size=12, lines=["disappearance: no", "(steps 0–6, 8, 9 hot)", "code unload: at the",
                                                                                     "next rolling restart", "P5: all hot, with", "the leak check"]))
    svg("unload-sequence", "Offloading a module", "Ordered, server and console: refuse first, then drain, then withdraw every surface; data and saved work stay.", y + 16, b)


def dev_path():
    st = [("new", "drishti.py module new genomics", "the archetype: a parent pom and compute, service, python, packs, console, docs, test", BLUE),
          ("run", "drishti.py module run", "the kernel + this module only, sample data; templates, Sutras, packs, static reload live", TEAL),
          ("test", "mvn verify", "unit tests, the kernel harness, the conformance suite (the absence test too)", GREEN),
          ("package", "mvn package", "drishti:validate → compile → test → conformance → assemble the .dmod", AMBER),
          ("sign", "mvn deploy -Prelease", "drishti:sign with the publisher key (HSM), publish .dmod + .sha256", AMBER),
          ("install", "drishti.py module install", "verify V1–V9 · four eyes · pages live at once · code at the rolling restart", VIOLET)]
    b, y = [], 84
    for i, (h, cmd, d, c) in enumerate(st):
        b.append(f'<path d="M24,{y} L130,{y} L148,{y + 30} L130,{y + 60} L24,{y + 60} Z" fill="{c}"/>')
        b.append(text(80, y + 36, h, 15, "#ffffff", 700, "middle"))
        b.append(f'<rect x="160" y="{y}" width="576" height="60" rx="8" fill="{TINT[c]}" stroke="{c}" stroke-width="1.5"/>')
        b.append(text(174, y + 24, cmd, 14, INK, 700, mono=True))
        b.append(text(174, y + 45, d, 13, INK))
        y += 70
    y += 6
    b.append(box(24, y, 346, 118, NAVY, head="On the developer's machine", size=13, lines=[
        "JDK 21 · Maven 3.9 · the kernel's Python", "drishti-kernel-api (scope provided)", "drishti-kernel-testkit (scope test)",
        "a development key (taint D)"]))
    b.append(box(390, y, 346, 118, AMBER, head="In the publisher's CI", size=13, lines=[
        "the same mvn verify, then -Prelease", "the signing key never in Git or on disks", "release notes · licence feature",
        "the .dmod to the module repository"]))
    y += 136
    b.append(box(24, y, 712, 58, GREEN, fill=TINT[GREEN], size=13, lines=[
        "Customer: Admin → Modules → Install → verify report → a second administrator approves",
        "copied to the placed nodes only · fingerprint gossiped · remove and roll back with the same four eyes"]))
    svg("dev-path", "The module developer path", "From an empty folder to a signed module at a customer, with the command at each step.", y + 80, b)


def cluster_modules():
    """Placement, not everything everywhere: banking api on main+b, compute on b, feeds/jobs on main, nothing on c; genomics on the GPU node."""
    b = []
    b.append(box(24, 84, 712, 74, VIOLET, head="Console (any number; nothing installed by hand)", size=13, lines=[
        "asks the registry which modules are active and where · console bundles from the kernel (signed)"]))
    b.append(arrow(200, 158, 200, 190, NAVY)); b.append(label(262, 178, "registry, bundles"))
    b.append(arrow(560, 158, 560, 190, GREEN)); b.append(label(636, 178, "API proxy calls"))
    b.append(box(24, 194, 712, 44, NAVY, fill=TINT[NAVY], size=13, lines=[
        "Drishti*net routes each part only to nodes where it is placed and active; failover inside the placement"]))
    nodes = [("drishti-main", "role: data-serving", [("banking  api", GREEN), ("banking  feeds, jobs ★", AMBER), ("genomics  api", GREEN)], NAVY),
             ("drishti-b", "role: risk-compute", [("banking  api", GREEN), ("banking  COMPUTE (heavy)", BLUE), ("  VaR, FRTB, exposure", BLUE)], BLUE),
             ("drishti-c", "no role", [("banking  not placed", MUTED), ("  no .dmod here;", MUTED), ("  forwards, never computes", MUTED)], MUTED),
             ("drishti-gpu", "role: gpu", [("genomics  api", GREEN), ("genomics  ALIGNMENT", TEAL), ("  heavy, max-nodes 1", TEAL)], TEAL)]
    for i, (h, role, ls, c) in enumerate(nodes):
        x = 24 + i * 182
        b.append(box(x, 270, 170, 140, c, head=h, size=13, dash=(c == MUTED)))
        b.append(text(x + 12, 312, role, 12, MUTED, italic=True))
        for j, (ln, lc) in enumerate(ls):
            b.append(text(x + 12, 336 + j * 20, ln, 12, lc if lc != MUTED else INK, 700 if lc in (BLUE, TEAL, AMBER) else 400))
        b.append(arrow(x + 85, 238, x + 85, 268, MUTED if c == MUTED else NAVY, dash=(c == MUTED)))
    b.append(text(24, 432, "★ singleton: one elected runner among the placed nodes", 12, AMBER, 700))
    b.append(box(24, 446, 712, 64, AMBER, fill=TINT[AMBER], head="Guards", size=12, lines=[
        "no default 'all nodes' · heavy: max-nodes + memory check at activation · heavy on every node only with allow-all + four eyes"]))
    b.append(box(24, 526, 712, 112, MUTED, fill="#ffffff", head="Node × module matrix (Health, Admin → Cluster)", size=13))
    cols = [(150, "main"), (290, "b"), (430, "c"), (570, "gpu")]
    for x, h in cols:
        b.append(text(x, 574, h, 13, MUTED, 700))
    rows = [("banking", [("api, feeds ✓", GREEN), ("api, compute ✓", GREEN), ("not placed", MUTED), ("not placed", MUTED)]),
            ("genomics", [("api ✓", GREEN), ("not placed", MUTED), ("present ! amber", AMBER), ("placed, missing ✗", RED)])]
    for j, (m, cells) in enumerate(rows):
        y = 598 + j * 22
        b.append(text(40, y, m, 13, INK, 700, mono=True))
        for (x, _), (v, c) in zip(cols, cells):
            b.append(text(x, y, v, 13, c, 700 if c in (RED, AMBER) else 400))
    svg("cluster-modules", "Modules in a cluster: placed, not everywhere", "The deployer places each part on named nodes or roles; others forward; drift shows in the matrix.", 660, b)


if __name__ == "__main__":
    for f in (layers, anatomy, load_sequence, data_binding, distributions, phases, analogy, page_flow, unload_sequence, dev_path, cluster_modules):
        f()
