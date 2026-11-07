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

"""The console's CSS takes its values from the design tokens (static/css/drishti_tokens.css; docs/architecture/ELEMENTS.md, "Design
tokens"): no colour literal outside the token file, font sizes only from the type scale, @media widths only from the breakpoints, every
token a rule names defined, and !important only on the allow-list below, which can only shrink."""
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

CSS = Path(__file__).resolve().parent.parent / "web" / "static" / "css"
TOKENS = CSS / "drishti_tokens.css"
# Sheets wip/e-inputs-convert is rewriting (forms, pickers, multi-selects, record forms): tokenised after it merges. Only shrinks.
PENDING = {"drishti_form.css", "drishti_record-forms.css", "drishti_controls.css"}
COLOUR = re.compile(r"#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch)\(")
LENGTH = re.compile(r"(?<![\w.#-])\d*\.?\d+(?:px|rem|em|pt|%|vw|vh|cqi|cqw)(?![\w-])")


def _bare(text: str) -> str:
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def _sheets(pending: bool = False):
    return [f for f in sorted(CSS.glob("*.css")) if f.name != TOKENS.name and (pending or f.name not in PENDING)]


def _decls(text: str):
    """(property, value) of every declaration of a sheet, comments dropped."""
    return [(p.strip().lower(), v.strip()) for p, v in re.findall(r"(?<![\w-])([\w-]+)\s*:\s*([^;{}]+?)\s*(?=[;}])", _bare(text))]


def _defined() -> set:
    names = set(re.findall(r"(--d-[\w-]+)\s*:", _bare(TOKENS.read_text(encoding="utf-8"))))
    for f in CSS.glob("*.css"):                  # a sheet may set its own --d-* (none should; the check below keeps them visible)
        names |= set(re.findall(r"(--d-[\w-]+)\s*:", _bare(f.read_text(encoding="utf-8"))))
    return names


def test_no_colour_literal_outside_the_tokens():
    """Every colour is a token, so every theme draws every rule (a hex or rgb() in a sheet is one colour in all ten themes)."""
    found = [f"{f.name}: {m.group()}" for f in _sheets() for m in COLOUR.finditer(_bare(f.read_text(encoding="utf-8")))]
    assert not found, "colour literals outside drishti_tokens.css (add or reuse a token):\n" + "\n".join(found[:40])


def test_font_sizes_come_from_the_type_scale():
    """font-size and the size in a font shorthand name a --d-fs-* step (or inherit): the type scale is the one list of sizes."""
    bad = []
    for f in _sheets():
        for prop, val in _decls(f.read_text(encoding="utf-8")):
            v = val.replace("!important", "").strip()
            if prop == "font-size":
                ok = v in ("inherit", "initial", "unset") or (re.search(r"var\(--d-fs-", v) and not LENGTH.search(re.sub(r"var\([^()]*\)", "", v)))
            elif prop == "font":
                ok = v in ("inherit", "initial", "unset") or not LENGTH.search(re.sub(r"var\([^()]*\)", "", v.split("/")[0]))
            else:
                continue
            if not ok:
                bad.append(f"{f.name}: {prop}: {val}")
    assert not bad, "font sizes outside the type scale:\n" + "\n".join(bad)


def test_media_widths_are_the_breakpoints():
    """A media query cannot read a custom property, so @media repeats the number: only the widths the --d-bp-* tokens list."""
    allowed = set(re.findall(r"--d-bp-[\w-]+:\s*([\d.]+px)", TOKENS.read_text(encoding="utf-8")))
    assert allowed, "no --d-bp-* breakpoints in drishti_tokens.css"
    bad = []
    for f in _sheets():
        for q in re.findall(r"@media([^{]+)\{", _bare(f.read_text(encoding="utf-8"))):
            bad += [f"{f.name}: @media{q.strip()}" for w in re.findall(r"(?:min|max)-width\s*:\s*([\d.]+\w+)", q) if w not in allowed]
    assert not bad, "@media widths that are not a --d-bp-* breakpoint:\n" + "\n".join(bad)


def test_every_token_a_rule_names_is_defined():
    """A misspelt var(--d-...) silently draws nothing: every --d-* a sheet reads is declared."""
    defined = _defined()
    missing = sorted({f"{f.name}: {n}" for f in _sheets() for n in re.findall(r"var\((--d-[\w-]+)", _bare(f.read_text(encoding="utf-8")))
                      if n not in defined})
    assert not missing, "undefined tokens:\n" + "\n".join(missing)


def test_the_tokens_hold_the_scales():
    """drishti_tokens.css is the one home of the scales: type, spacing, radius, layers, breakpoints, densities, elevation."""
    text = TOKENS.read_text(encoding="utf-8")
    for family in ("--d-fs-", "--d-fs-em-", "--d-fs-display-", "--d-sp-", "--d-radius-", "--d-z-", "--d-bp-", "--d-density-", "--d-elev-"):
        assert family in text, family


# ---- !important ----------------------------------------------------------------------------------------------------------------------
# Every !important left in the console's CSS, as "sheet|[@media ]first selector|property", with the reason a plain rule cannot do it.
# The list only shrinks: a new !important fails the test; one removed must leave the list too (and IMPORTANT_CEILING comes down).
_PRINT = "print: paper wins over every screen rule, whatever its specificity"
_MOTION = "reduced motion / no flashing: the reader's setting wins over every animation"
_DRAG = "while dragging: one cursor over every element's own"
_INLINE = "beats a position the script writes inline (style attribute)"
_UTILITY = "utility class: wins over the colour of the component it decorates"
_HIDE = "hide utility: wins over the shown element's own display (as Bootstrap's d-none)"
_HIDDEN_EMBED = "[hidden] in a sheet the embedded view/report also carries: no Bootstrap there, whose [hidden] rule the console uses"
_HIDDEN_CHK = "[hidden] over .chk, whose display is itself !important"
_CHECKROW = ("a checkbox row inside a form whose labels stack (the .X label rules, one per form): to be folded into one form-label "
             "convention after wip/e-inputs-convert merges")
_BOOTSTRAP = "Bootstrap's own rule is more specific (.dropdown-menu[data-bs-popper] margin)"
_PENDING = "in a sheet wip/e-inputs-convert is rewriting"
_LAYOUT = "layout mode: shows the switched-off panels over .pnl-off, itself a hide utility"
_GRID = "the phone's one column over the screen grid's placements, which come in several specificities"
_TONE = "the timeline's text stays ink under its tone class (.t-ok and the rest are !important utilities)"
IMPORTANT_ALLOW = {
    "drishti_about.css|@media (max-width: 640px) .about-tip|left": _INLINE,
    "drishti_about.css|@media (max-width: 640px) .about-tip|top": _INLINE,
    "drishti_admin-artifacts.css|.art-cascade[hidden]|display": _HIDDEN_CHK,
    "drishti_admin-capacity.css|@media (max-width: 639.98px) .cap-desk-only|display": _HIDE,
    "drishti_admin-compute.css|.cmp-danger|color": _UTILITY,
    "drishti_admin-connectors.css|.conn-dialog [hidden]|display": _HIDDEN_CHK,
    "drishti_admin-embedding.css|.emb-danger|color": _UTILITY,
    "drishti_bi-notebook.css|.bx-check|flex-direction": _CHECKROW,
    "drishti_bi-notebook.css|.bx-check|gap": _CHECKROW,
    "drishti_bi-notebook.css|.bx-inline|flex-direction": _CHECKROW,
    "drishti_bi-notebook.css|.bx-inline|gap": _CHECKROW,
    "drishti_bi-report.css|@media (max-width: 760px) .bi-rgrid .bi-vis|grid-column": _INLINE,
    "drishti_bi-report.css|@media (max-width: 760px) .bi-rgrid .bi-vis|grid-row": _INLINE,
    "drishti_bi-transform.css|.tx-radio|flex-direction": _CHECKROW,
    "drishti_bi-transform.css|.tx-radio|gap": _CHECKROW,
    "drishti_bi.css|.bi-form[hidden]|display": _HIDDEN_EMBED,
    "drishti_bi.css|.bi-checkrow|flex-direction": _CHECKROW,
    "drishti_bi.css|.bi-checkrow|gap": _CHECKROW,
    "drishti_bi.css|.bi-checkrow|color": _CHECKROW,
    "drishti_bi.css|.bi-danger|color": _UTILITY,
    "drishti_bi.css|.bi-danger|border-color": _UTILITY,
    "drishti_bi.css|.bi-danger[data-sure]|background": _UTILITY,
    "drishti_bi.css|.bi-danger[data-sure]|color": _UTILITY,
    "drishti_bi.css|.bi-callout[hidden]|display": _HIDDEN_EMBED,
    "drishti_bi.css|.bi-confirm[hidden]|display": _HIDDEN_EMBED,
    "drishti_build-workbench.css|body.wb-dragging|cursor": _DRAG,
    "drishti_colfilter.css|@media print .cf-btn|display": _PRINT,
    "drishti_collab.css|.acol-check|display": _CHECKROW,
    "drishti_collab.css|.acol-check|flex-direction": _CHECKROW,
    "drishti_collab.css|.acol-check|min-width": _CHECKROW,
    "drishti_controls.css|@media print .ctl-bar|display": _PRINT,
    "drishti_dist.css|.al-check|flex-direction": _CHECKROW,
    "drishti_dist.css|.al-check|font-size": _CHECKROW,
    "drishti_dist.css|.al-check|color": _CHECKROW,
    "drishti_explain.css|.dx-pop.dx-sheet|left": _INLINE,
    "drishti_explain.css|.dx-pop.dx-sheet|top": _INLINE,
    "drishti_explain.css|@media print .dx-page-head .dx-btn|display": _PRINT,
    "drishti_gradients.css|@media print body|background": _PRINT,
    "drishti_history.css|@media (prefers-reduced-motion: reduce) .hx *|transition": _MOTION,
    "drishti_layout.css|.pnl-off|display": _HIDE,
    "drishti_layout.css|.layout-on .pnl-off|display": _LAYOUT,
    "drishti_layout.css|.layout-on .pnl-off[class*=\" c-h-\"]|display": _LAYOUT,
    "drishti_layout.css|@media print .pnl[class*=\" c-h-\"]|height": _PRINT,
    "drishti_layout.css|@media print .pnl[class*=\" c-h-\"]|display": _PRINT,
    "drishti_layout.css|@media print .pnl[class*=\" c-h-\"] > .pnl-b|overflow": _PRINT,
    "drishti_layout.css|@media print .lay-bar|display": _PRINT,
    "drishti_layout.css|@media print .layout-on .pnl-off|display": _PRINT,
    "drishti_layout.css|.pnl.pnl-zoom|height": "a zoomed panel fills the view over its height class and the height the resize handle writes inline",
    "drishti_layout.css|@media print .has-zoom .pnl:not(.pnl-zoom)|display": _PRINT,
    "drishti_layout.css|@media print .pnl-zoom-btn|display": _PRINT,
    "drishti_nav.css|.mega-panel|margin-top": _BOOTSTRAP,
    "drishti_nav.css|@media (max-width: 640px) .tbar-menus .mega-panel|margin-top": _BOOTSTRAP,
    "drishti_nav.css|@media (max-width: 640px) .tbar-tools .dropdown-menu|position": _INLINE + " (Popper)",
    "drishti_nav.css|@media (max-width: 640px) .tbar-tools .dropdown-menu|left": _INLINE + " (Popper)",
    "drishti_nav.css|@media (max-width: 640px) .tbar-tools .dropdown-menu|right": _INLINE + " (Popper)",
    "drishti_nav.css|@media (max-width: 640px) .tbar-tools .dropdown-menu|top": _INLINE + " (Popper)",
    "drishti_nav.css|@media (max-width: 640px) .tbar-tools .dropdown-menu|inset-inline": _INLINE + " (Popper)",
    "drishti_nav.css|@media (max-width: 640px) .tbar-tools .dropdown-menu|transform": _INLINE + " (Popper)",
    "drishti_phone.css|[data-phone-tier=\"more\"]:not(.ph-open)|display": _HIDE,
    "drishti_pivot.css|@media print .tr-tog|display": _PRINT,
    "drishti_record-forms.css|.fm-bad .fm-in|border-color": _PENDING,
    "drishti_resize.css|html[data-rz-dragging=\"x\"]|cursor": _DRAG,
    "drishti_resize.css|html[data-rz-dragging=\"y\"]|cursor": _DRAG,
    "drishti_screen-grid.css|@media (max-width: 640px) .sg-area > .sg-cells > .pnl|grid-column": _GRID,
    "drishti_screen-grid.css|@media (max-width: 640px) .sg-cells > *|grid-column": _GRID,
    "drishti_table-chart.css|@media print body.tc-open > *:not(.tc-dlg)|display": _PRINT,
    "drishti_table-chart.css|@media print .tc-side|display": _PRINT,
    "drishti_terminal.css|.t-pos|color": _UTILITY,
    "drishti_terminal.css|.t-neg|color": _UTILITY,
    "drishti_terminal.css|.t-ok|color": _UTILITY,
    "drishti_terminal.css|.t-warn|color": _UTILITY,
    "drishti_terminal.css|.t-bad|color": _UTILITY,
    "drishti_terminal.css|.chk|flex-direction": _CHECKROW,
    "drishti_terminal.css|.chk|gap": _CHECKROW,
    "drishti_terminal.css|.chk|color": _CHECKROW,
    "drishti_terminal.css|.chk|display": _CHECKROW,
    "drishti_terminal.css|@media (max-width: 1700px) .tbar:has(.asof-past) [data-live-text]|display": _HIDE,
    "drishti_terminal.css|.tbar:has(.asof-known.on) .tbar-link > span|display": _HIDE,
    "drishti_terminal.css|.tl-i|color": _TONE,
    "drishti_terminal.css|@media print body|background": _PRINT,
    "drishti_terminal.css|@media print body|color": _PRINT,
    "drishti_terminal.css|@media print .tbar|display": _PRINT,
    "drishti_terminal.css|@media print .fkeys|position": _PRINT,
    "drishti_terminal.css|@media print .fkeys|border": _PRINT,
    "drishti_terminal.css|@media print .fkeys|background": _PRINT,
    "drishti_terminal.css|@media print .vgrid|display": _PRINT,
    "drishti_terminal.css|@media print .pnl|box-shadow": _PRINT,
    "drishti_terminal.css|@media print .pnl|border-color": _PRINT,
    "drishti_terminal.css|@media print a|color": _PRINT,
    "drishti_terminal.css|@media print a|text-decoration": _PRINT,
    "drishti_terminal.css|.no-flash .flash|animation": _MOTION,
    "drishti_terminal.css|.pin-on|color": _UTILITY,
    "drishti_terminal.css|@media print table.tbl tbody tr[hidden]|display": _PRINT,
    "drishti_theme.css|.text-muted-d|color": _UTILITY,
    "drishti_theme.css|@media (prefers-reduced-motion: reduce) *|animation": _MOTION,
    "drishti_theme.css|@media (prefers-reduced-motion: reduce) *|transition": _MOTION,
}
IMPORTANT_CEILING = 96   # 2.9.11.e: 149 before the token pass; lower it with every entry that leaves the list


def important_keys(name: str, text: str) -> list:
    """'sheet|[@media ]first selector|property' of every !important declaration of a sheet."""
    out, stack = [], []
    for m in re.finditer(r"([^{};]*)\{|\}|([^{};]*);", _bare(text)):
        if m.group(0).endswith("{"):
            stack.append(" ".join(m.group(1).split()))
        elif m.group(0) == "}":
            if stack:
                stack.pop()
        elif m.group(2) is not None and "!important" in m.group(2) and stack:
            media = [s for s in stack[:-1] if s.startswith("@")]
            first = re.split(r",(?![^(]*\))", stack[-1])[0].strip()
            out.append(f"{name}|{(media[-1] + ' ') if media else ''}{first}|{m.group(2).split(':', 1)[0].strip()}")
    return out


def test_important_only_on_the_allow_list():
    found = [k for f in sorted(CSS.glob("*.css")) for k in important_keys(f.name, f.read_text(encoding="utf-8"))]
    new = [k for k in found if k not in IMPORTANT_ALLOW]
    gone = [k for k in IMPORTANT_ALLOW if k not in found]
    assert not new, "!important not on the allow-list (fix the specificity or the order instead):\n" + "\n".join(new)
    assert not gone, "allow-list entries with no !important left: take them off (and lower IMPORTANT_CEILING):\n" + "\n".join(gone)
    assert len(IMPORTANT_ALLOW) <= IMPORTANT_CEILING and len(found) == len(IMPORTANT_ALLOW)


def test_the_important_guard_catches_a_new_one():
    assert important_keys("x.css", ".a { color: red !important; } @media print { .b, .c { display: none !important; } }") == [
        "x.css|.a|color", "x.css|@media print .b|display"]
