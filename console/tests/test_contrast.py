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
