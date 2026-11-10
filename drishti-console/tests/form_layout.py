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

"""How a drishti-inputs Form in columns (layout columns) is laid out, measured in a real browser: a group takes the form's width, the
fields of a row sit side by side, each label sits on its control, and no box is taller than what it holds."""

_MEASURE = """(sel) => {
  const host = document.querySelector(sel), body = host && host.querySelector('.di-form-body');
  if (!body) { return null; }
  const vis = (e) => e.offsetParent !== null && e.getBoundingClientRect().height > 0;
  const R = (e) => e.getBoundingClientRect();
  const width = R(body).width;
  const groups = [...body.querySelectorAll(':scope > .di-t-object > .di-fieldset')].filter(vis).map((g) => ({ w: R(g).width }));
  // the cells of each row: the body's own fields and each group's fields
  const rows = [body, ...body.querySelectorAll(':scope > .di-t-object > .di-fieldset')].filter(vis).map((box) => {
    const cells = [...box.children].filter((c) => c.classList.contains('di-form-field') && vis(c));
    const top = cells.length ? Math.min(...cells.map((c) => R(c).top)) : 0;
    return cells.filter((c) => Math.abs(R(c).top - top) < 2).length;
  });
  // a label and its control: the label's next shown sibling starts just below it and at its left edge
  const pairs = [...body.querySelectorAll('.di-form-field > .di-label')].filter(vis).map((l) => {
    let c = l.nextElementSibling; while (c && !vis(c)) { c = c.nextElementSibling; }
    return c ? { gap: R(c).top - R(l).bottom, dx: Math.abs(R(c).left - R(l).left), label: l.textContent } : null;
  }).filter(Boolean);
  // a box's slack: its height beyond its shown children (plus its padding and border)
  const slack = [...body.querySelectorAll('.di-fieldset')].filter(vis).map((f) => {
    const kids = [...f.children].filter((c) => c.tagName !== 'LEGEND' && vis(c));
    if (!kids.length) { return 0; }
    const cs = getComputedStyle(f), r = R(f);
    const inner = r.bottom - parseFloat(cs.paddingBottom) - parseFloat(cs.borderBottomWidth);
    return Math.round(inner - Math.max(...kids.map((c) => R(c).bottom)));
  });
  return { width, groups, rows, pairs, slack };
}"""


def assert_columns_layout(page, host: str, min_per_row: int = 2):
    """The Form in ``host`` (a selector) uses its width: groups span it, a row holds ``min_per_row`` fields or more where there are that
    many, each label sits on its control (within 12 px, same left edge) and no fieldset has more than 16 px of empty space at its foot."""
    m = page.evaluate(_MEASURE, host)
    assert m, f"no drishti-inputs Form in {host}"
    for g in m["groups"]:
        assert g["w"] >= 0.95 * m["width"], f"a group is a narrow column ({g['w']:.0f} of {m['width']:.0f} px)"
    assert max(m["rows"]) >= min_per_row, f"the fields are not laid out across the width: {m['rows']}"
    for p in m["pairs"]:
        assert -1 <= p["gap"] <= 12 and p["dx"] <= 4, f"the label {p['label']!r} floats away from its control: {p}"
    assert max(m["slack"] or [0]) <= 16, f"a field box is taller than what it holds: {m['slack']}"
    return m
