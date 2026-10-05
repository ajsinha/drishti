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
    for name in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark"):
        t = _theme(name)
        for fg in ("ink", "muted", "link", "neg", "pos"):
            for bg in ("bg", "surface"):
                assert _ratio(t[fg], t[bg]) >= 4.5, (name, fg, bg, round(_ratio(t[fg], t[bg]), 2))
        assert _ratio(t["accent"], t["bg"]) >= 3.0, name


def test_the_about_drawer_text_reads_in_every_theme():
    """About this page (about.css) draws ink, muted and link text on the surface, its headings in the accent, its health words in
    pos / accent / neg, and ink on the key caps (surface-2): all at 4.5:1."""
    for name in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark"):
        t = _theme(name)
        for fg in ("ink", "muted", "link", "accent", "pos", "neg"):
            assert _ratio(t[fg], t["surface"]) >= 4.5, (name, fg, round(_ratio(t[fg], t["surface"]), 2))
        assert _ratio(t["ink"], t["surface-2"]) >= 4.5, name


def test_waterfall_bars_stand_out_in_every_theme():
    """A waterfall fills its bars with ok/bad (rises green, falls red; the default), pos/neg (colors: theme) and muted
    (totals): graphical objects need 3:1 against the panel (WCAG 1.4.11), and the two colours of a step must differ."""
    for name in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark"):
        t = _theme(name)
        for fill in ("ok", "bad", "pos", "neg", "muted"):
            for bg in ("bg", "surface"):
                assert _ratio(t[fill], t[bg]) >= 3.0, (name, fill, bg, round(_ratio(t[fill], t[bg]), 2))
        assert t["ok"].lower() != t["bad"].lower() and t["pos"].lower() != t["neg"].lower(), name


THEMES = ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark")
# Every token the console's CSS sets as a text colour, and every ground text sits on: the page, the second ground
# (inputs, the command line), panels and the second surface (code, headers).
TEXT_TOKENS = ("ink", "muted", "faint", "link", "accent", "accent-strong", "pos", "neg", "ok", "warn", "bad")
GROUNDS = ("bg", "bg-2", "surface", "surface-2")
CSS = Path(__file__).resolve().parent.parent / "web" / "static" / "css"


def test_every_text_colour_reads_on_every_ground_in_every_theme():
    """UX-04: faint text ("No data available", the footer, placeholders, key notes) and accent used as text (example
    commands, inline code, kickers) need 4.5:1 like any text (WCAG 1.4.3), on every ground, in all seven themes."""
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


def test_the_css_colours_text_only_with_checked_tokens():
    """A text colour taken from a token outside TEXT_TOKENS would escape the checks above: every `color:` in the
    console's CSS is one of them or --d-on-accent (or a fixed colour on a fixed ground, such as white on a badge)."""
    used = set()
    for f in CSS.glob("*.css"):
        if f.name == "tokens.css":
            continue
        used |= set(re.findall(r"(?<![-\w])color:\s*var\(--d-([a-z0-9-]+)\)", f.read_text()))
    checked = set(TEXT_TOKENS) | {"on-accent"}
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
    for name in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark"):
        t = _theme(name)
        for fg in ("ink", "muted", "link", "accent", "warn", "neg"):
            assert _ratio(t[fg], t["surface"]) >= 4.5, (name, fg, round(_ratio(t[fg], t["surface"]), 2))
        for bg in ("surface-2", "emph-bg", "accent-tint"):
            if bg in t:
                assert _ratio(t["ink"], t[bg]) >= 4.5, (name, bg, round(_ratio(t["ink"], t[bg]), 2))
