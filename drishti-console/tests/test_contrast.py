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

"""WCAG contrast is computed, not asserted: text >= 4.5:1, accents >= 3:1, for every theme."""
import re
from pathlib import Path

TOKENS = (Path(__file__).resolve().parent.parent / "web" / "static" / "css" / "tokens.css").read_text()


def _theme(name):
    block = re.search(r':root\[data-theme="%s"\][^{]*\{([^}]*)\}' % name, TOKENS).group(1)
    base = dict(re.findall(r"--d-([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})", re.search(r":root,\s*:root\[data-theme=\"terminal\"\]\s*\{([^}]*)\}", TOKENS).group(1)))
    base.update(re.findall(r"--d-([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})", block))
    return base


def _lum(hex_):
    c = [int(hex_[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    c = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def _ratio(a, b):
    la, lb = sorted((_lum(a), _lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def test_contrast_all_themes():
    for name in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"):
        t = _theme(name)
        for fg in ("ink", "muted", "link", "neg", "pos"):
            for bg in ("bg", "surface"):
                assert _ratio(t[fg], t[bg]) >= 4.5, (name, fg, bg, round(_ratio(t[fg], t[bg]), 2))
        assert _ratio(t["accent"], t["bg"]) >= 3.0, name


def test_the_about_drawer_text_reads_in_every_theme():
    """About this page (about.css) draws ink, muted and link text on the surface, its headings in the accent, its health words in
    pos / accent / neg, and ink on the key caps (surface-2): all at 4.5:1."""
    for name in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"):
        t = _theme(name)
        for fg in ("ink", "muted", "link", "accent", "pos", "neg"):
            assert _ratio(t[fg], t["surface"]) >= 4.5, (name, fg, round(_ratio(t[fg], t["surface"]), 2))
        assert _ratio(t["ink"], t["surface-2"]) >= 4.5, name


def test_waterfall_bars_stand_out_in_every_theme():
    """A waterfall fills its bars with ok/bad (rises green, falls red; the default), pos/neg (colors: theme) and muted
    (totals): graphical objects need 3:1 against the panel (WCAG 1.4.11), and the two colours of a step must differ."""
    for name in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"):
        t = _theme(name)
        for fill in ("ok", "bad", "pos", "neg", "muted"):
            for bg in ("bg", "surface"):
                assert _ratio(t[fill], t[bg]) >= 3.0, (name, fill, bg, round(_ratio(t[fill], t[bg]), 2))
        assert t["ok"].lower() != t["bad"].lower() and t["pos"].lower() != t["neg"].lower(), name


THEMES = ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue")
# Every token the console's CSS sets as a text colour, and every ground text sits on: the page, the second ground
# (inputs, the command line), panels and the second surface (code, headers).
TEXT_TOKENS = ("ink", "muted", "faint", "link", "accent", "accent-strong", "pos", "neg", "ok", "warn", "bad")
GROUNDS = ("bg", "bg-2", "surface", "surface-2")
CSS = Path(__file__).resolve().parent.parent / "web" / "static" / "css"


def test_every_text_colour_reads_on_every_ground_in_every_theme():
    """UX-04: faint text ("No data available", the footer, placeholders, key notes) and accent used as text (example
    commands, inline code, kickers) need 4.5:1 like any text (WCAG 1.4.3), on every ground, in all ten themes."""
    low = []
    for name in THEMES:
        t = _theme(name)
        for fg in TEXT_TOKENS:
            for bg in GROUNDS:
                r = _ratio(t[fg], t[bg])
                if r < 4.5:
                    low.append(f"{name}: --d-{fg} {t[fg]} on --d-{bg} {t[bg]} is {r:.2f}:1")
    assert not low, "\n".join(low)


def test_labels_on_accent_fills_read_in_every_theme():
    """Accent buttons and the skip link put --d-on-accent on the accent (and accent-strong on hover): dark on the bright
    accents, white on the light theme's amber and on crimson."""
    for name in THEMES:
        t = _theme(name)
        for fill in ("accent", "accent-strong"):
            assert _ratio(t["on-accent"], t[fill]) >= 4.5, (name, fill, round(_ratio(t["on-accent"], t[fill]), 2))


def test_labels_on_button_fills_read_in_every_theme():
    """Buttons and pressed controls fill with --d-fill (the accent unless a theme sets its own, as terminal-crimson does: a
    deep crimson with white labels instead of its bright text accent): the label reads at 4.5:1 on the fill and its hover."""
    for name in THEMES:
        t = _theme(name)
        fill, strong, on = t.get("fill", t["accent"]), t.get("fill-strong", t["accent-strong"]), t.get("on-fill", t["on-accent"])
        for f in (fill, strong):
            assert _ratio(on, f) >= 4.5, (name, f, round(_ratio(on, f), 2))


def test_the_css_colours_text_only_with_checked_tokens():
    """A text colour taken from a token outside TEXT_TOKENS would escape the checks above: every `color:` in the
    console's CSS is one of them or --d-on-accent (or a fixed colour on a fixed ground, such as white on a badge)."""
    used = set()
    for f in CSS.glob("*.css"):
        if f.name == "tokens.css":
            continue
        used |= set(re.findall(r"(?<![-\w])color:\s*var\(--d-([a-z0-9-]+)\)", f.read_text()))
    checked = set(TEXT_TOKENS) | {"on-accent", "on-fill"}
    assert {"faint", "accent"} <= used <= checked, sorted(used - checked)


def test_the_light_fallback_is_the_light_theme():
    """With no theme chosen and a light system preference the console uses a copy of the light theme's colours: the
    copy has every text and ground colour, and none drifts from the checked light theme."""
    block = re.search(r"@media \(prefers-color-scheme: light\)\s*\{\s*:root:not\(\[data-theme\]\)\s*\{([^}]*)\}", TOKENS).group(1)
    light = _theme("light")
    fallback = {k: v.lower() for k, v in re.findall(r"--d-([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})", block)}
    assert set(TEXT_TOKENS) | set(GROUNDS) | {"on-accent"} <= set(fallback)
    assert fallback == {k: light[k].lower() for k in fallback}


def test_the_share_dialog_and_inbox_read_in_every_theme():
    """Share with a note (collab.css): ink, muted, accent, warn and neg text on the surface; ink on the chips (surface-2), on an unread row
    (emph-bg) and on the highlighted option (accent-tint): all at 4.5:1."""
    for name in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"):
        t = _theme(name)
        for fg in ("ink", "muted", "link", "accent", "warn", "neg"):
            assert _ratio(t[fg], t["surface"]) >= 4.5, (name, fg, round(_ratio(t[fg], t["surface"]), 2))
        for bg in ("surface-2", "emph-bg", "accent-tint"):
            if bg in t:
                assert _ratio(t["ink"], t[bg]) >= 4.5, (name, bg, round(_ratio(t["ink"], t[bg]), 2))


BUTTON = re.compile(r"btn|button|aria-pressed|aria-checked")


def _rules(text):
    """(selector, body) of each plain rule of a stylesheet, comments dropped; @media wrappers are looked through."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return [(sel.strip(), body) for sel, body in re.findall(r"([^{}]+)\{([^{}]*)\}", text)]


def button_fill_problems(sheets):
    """The rules of `sheets` that fill a button with something other than --d-fill / --d-fill-strong, or write --d-on-fill on
    another ground."""
    bad = []
    for f in sheets:
        for sel, body in _rules(f.read_text()):
            # a faint tint of a colour (color-mix with transparent, under the page's own ink) is a hover wash, not a fill
            grounds = [re.sub(r"color-mix\([^()]*(?:var\([^()]*\)[^()]*)*transparent\)", "", g)
                       for g in re.findall(r"background(?:-color|-image)?\s*:([^;]*)", body)]
            used = {v for g in grounds for v in re.findall(r"var\(--d-([a-z0-9-]+)", g)}
            if BUTTON.search(sel) and used & {"accent", "accent-strong"}:
                bad.append(f"{f.name}: {sel} fills with --d-accent (use --d-fill / --d-fill-strong)")
            if re.search(r"(?<![-\w])color:\s*var\(--d-on-fill\)", body) and used - {"fill", "fill-strong"}:
                bad.append(f"{f.name}: {sel} writes --d-on-fill on --d-{', --d-'.join(sorted(used - {'fill', 'fill-strong'}))}")
    return bad


def test_buttons_fill_with_the_fill_tokens_only():
    """QA2-03: a button (or a pressed control) takes its ground from --d-fill / --d-fill-strong, the pair whose label
    (--d-on-fill) test_labels_on_button_fills_read_in_every_theme checks: a gradient of the bright text accent under a white
    label read 2:1 in Crimson Terminal. A rule that writes --d-on-fill puts it on those fills, never on another token."""
    sheets = [f for f in sorted(CSS.glob("*.css")) if f.name != "tokens.css"] + [CSS.parent.parent / "elements" / "drishti-view.css"]
    assert not (bad := button_fill_problems(sheets)), "\n".join(bad)


def test_the_button_guard_catches_an_accent_gradient(tmp_path):
    """The guard above fails on the 2.6.5 rule that made Crimson Terminal's Save and Preview pale pink."""
    css = tmp_path / "old.css"
    css.write_text(".btn-accent, .btn-pill.btn-accent {\n  background: linear-gradient(135deg, var(--d-accent), "
                   "color-mix(in srgb, var(--d-accent) 72%, var(--d-grad-to)));\n  border: 0;\n}\n")
    assert button_fill_problems([css])
