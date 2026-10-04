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

"""The pictures of the Build workbench tutorial (docs/guides/BUILD_WORKBENCH_TUTORIAL.md), written to docs/guides/img/tutorial/.

The tutorial does four real projects, so this module does them too, on a scratch server with sign-in ON and two people: `asha` (an author)
and `ravi` (an approver), which is what makes "propose, then approve as somebody else" true. Run it on its own, which starts that server:

    console/.venv/bin/python tools/docs/screenshots.py --guide tutorial

Each project is one function (a project's steps build on each other); `p1-` .. `p4-` name the project in a picture's file name."""
from __future__ import annotations

import json
import re

from shotlib import ROOT, Ctx, register

shot = register("tutorial")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": "tutorial-scratch-secret-0123456789abcdef-xyz",
              "DRISHTI_SUTRA_REVIEW": "true", "DRISHTI_SUTRA_FOUR_EYES": "true",
              # the console signs people in (over plain http here, so the cookie may not be "secure")
              "DRISHTI_AUTH_ENABLED": "true", "DRISHTI_SESSION_SECRET": "tutorial-session-secret-0123456789abcdef-xyz", "DRISHTI_SECURE_COOKIE": "false"}
PASSWORD = "tutorial-pass-2026"
TRADES = [ROOT / "docs" / "guides" / "tutorial-data" / f"MX-2000000{i}.json" for i in range(1, 6)]
_state: dict = {"users": False}


def login(c: Ctx, user: str, password: str = PASSWORD):
    """Signs in as `user` (clearing any other person's session first)."""
    c.page.context.clear_cookies()
    c.page.goto(c.base + "/login")
    c.page.fill("input[name=user]", user)
    c.page.fill("input[name=password]", password)
    c.page.locator("form button[type=submit]").first.click()
    c.page.wait_for_url(re.compile(r".*/(t|build.*)$"))


def people(c: Ctx):
    """The two people of the tutorial, made once by the development admin."""
    if _state["users"]:
        return
    login(c, "drishti-dev-admin", "drishti-dev-admin123")
    for user, role in (("asha", "author"), ("ravi", "approver")):
        c.page.request.post(c.base + "/admin/api/users", data=json.dumps({"username": user, "displayName": user.title(), "roles": [role], "password": PASSWORD}),
                            headers={"Content-Type": "application/json"})
    _state["users"] = True


def before(c: Ctx, name: str):
    people(c)


def top(c: Ctx):
    """Back to the top of the page, the toast and status line gone: the picture shows the workbench, not the scroll position."""
    c.page.evaluate("window.scrollTo(0, 0)")
    c.page.wait_for_timeout(9000 if c.page.locator("[data-toast]").count() else 300)


def confirm(c: Ctx, button: str):
    """The workbench's own question (it is not the browser's dialog): press its button."""
    c.page.locator(".wb-ask").get_by_role("button", name=button, exact=True).click()


@shot("p1-01-new-files.jpg", also=("p1-02-start-from.jpg", "p1-03-first-draft.jpg", "p1-04-shape.jpg", "p1-05-reasons.jpg", "p1-06-why.jpg", "p1-07-removed.jpg", "p1-08-suggest.jpg", "p1-09-swapped.jpg", "p1-10-move.jpg", "p1-11-resize.jpg", "p1-12-tests.jpg", "p1-13-rename.jpg", "p1-14-applies-to.jpg", "p1-15-proposed.jpg", "p1-16-reviews.jpg", "p1-17-evidence.jpg", "p1-18-approved.jpg", "p1-19-live-status.jpg", "p1-20-live-view.jpg"))
def project1(c: Ctx):
    login(c, "asha")
    c.page.goto(c.base + "/build/new")
    c.page.locator("#bsFiles").set_input_files([str(f) for f in TRADES])
    c.page.get_by_text("5 files ready.").wait_for()
    c.page.wait_for_timeout(400)
    c.save("p1-01-new-files.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 600})
    c.page.fill("#bsName", "Rates trade screen")
    c.page.fill("#bsDesignKind", "trade")
    c.page.locator("[data-create]").scroll_into_view_if_needed()
    c.page.evaluate("window.scrollBy(0, 220)")
    c.page.wait_for_timeout(300)
    c.save("p1-02-start-from.jpg")
    c.page.locator("[data-create]").click()
    c.page.wait_for_url("**/build/d/*")
    c.ready()
    c.save("p1-03-first-draft.jpg")
    row = c.page.locator(".wb-field-row", has_text="mtm").first
    row.scroll_into_view_if_needed()
    row.click()
    c.page.keyboard.press("Enter")
    c.page.wait_for_timeout(500)
    c.save("p1-04-shape.jpg", ".wb-left")
    c.page.locator("[data-autodesign]").click()
    confirm(c, "Auto-design")
    c.page.locator("[data-draft]:not([hidden])").wait_for(timeout=30000)
    c.page.wait_for_timeout(800)
    c.save("p1-05-reasons.jpg", "[data-draft]")
    c.page.evaluate("(() => { const d = document.querySelector('[data-draft]'); const h = d.querySelectorAll('h3')[1]; d.scrollTop = h.getBoundingClientRect().top - d.getBoundingClientRect().top + d.scrollTop - 4; })()")
    c.page.wait_for_timeout(300)
    c.save("p1-06-why.jpg", "[data-draft]")
    c.page.evaluate("document.querySelector('[data-draft]').hidden = true; window.scrollTo(0, 0)")
    c.page.wait_for_timeout(300)

    # swap a panel for its runner-up: remove it (Undo is one click away) and drop its field again to choose another kind
    yaml = c.page.evaluate("window.drishtiWorkbench.store.state.yaml")
    sens = re.search(r'id: sensitivities.*?rows: "\$\.(\w+)', yaml, re.S).group(1)
    c.panel("sensitivities").hover()
    c.panel("sensitivities").get_by_role("button", name=re.compile("^Remove panel")).first.click()
    c.page.locator("[data-toast]").wait_for()
    c.page.wait_for_timeout(500)
    c.save("p1-07-removed.jpg")
    c.page.locator("[data-filter]").fill(sens)
    c.page.wait_for_timeout(500)
    f = c.page.locator(".wb-field-row", has_text=sens).first
    f.scroll_into_view_if_needed()
    s0, t = c.box(f), c.box(c.panel("legs"))
    c.page.mouse.move(s0["x"] + 30, s0["y"] + 5)
    c.page.mouse.down()
    c.page.mouse.move(s0["x"] + 80, s0["y"] + 30, steps=3)
    c.page.mouse.move(t["x"] + t["width"] / 2, t["y"] + t["height"] - 6, steps=10)
    c.page.mouse.up()
    c.page.locator(".wb-menu").wait_for()
    c.page.wait_for_timeout(400)
    c.save("p1-08-suggest.jpg")
    rev = c.rev()
    c.page.get_by_role("option").filter(has_text=re.compile(r"^\s*line")).first.click()
    c.settle(rev)
    c.page.locator("[data-filter]").fill("")
    top(c)
    c.save("p1-09-swapped.jpg")

    # move a panel with the mouse (by its grip), then resize it by its edge
    risk = c.panel("terms")
    risk.scroll_into_view_if_needed()
    g = c.box(risk.locator(".pnl-h"))
    target = c.box(c.panel("details"))
    c.page.mouse.move(g["x"] + 12, g["y"] + g["height"] / 2)
    c.page.mouse.down()
    c.page.mouse.move(g["x"] - 40, g["y"] - 10, steps=4)
    c.page.mouse.move(target["x"] + 60, target["y"] + 6, steps=12)
    c.page.wait_for_timeout(300)
    c.save("p1-10-move.jpg")
    rev = c.rev()
    c.page.mouse.up()
    c.settle(rev)
    p = c.panel("legs")
    p.scroll_into_view_if_needed()
    p.hover()
    e = c.box(p.locator(".lh-e"))
    c.page.mouse.move(e["x"] + 5, e["y"] + 40)
    c.page.mouse.down()
    c.page.mouse.move(e["x"] - 120, e["y"] + 40, steps=6)
    c.page.wait_for_timeout(300)
    c.save("p1-11-resize.jpg")
    rev = c.rev()
    c.page.mouse.up()
    c.settle(rev)

    # check every sample
    c.page.get_by_role("tab", name="Tests").click()
    c.page.locator(".wb-matrix").wait_for(timeout=30000)
    c.page.wait_for_timeout(1200)
    c.save("p1-12-tests.jpg")

    # a name worth keeping: the YAML tab
    c.page.get_by_role("tab", name="YAML").click()
    c.page.wait_for_timeout(600)
    rev = c.rev()
    c.page.evaluate("(() => { const cm = document.querySelector('.CodeMirror').CodeMirror; cm.replaceRange('sutra: rates-trade', {line: 1, ch: 0}, {line: 1, ch: 40}); })()")
    c.until("window.drishtiWorkbench.store.state.yaml.indexOf('sutra: rates-trade') >= 0")
    c.page.wait_for_timeout(1500)
    c.save("p1-13-rename.jpg")
    c.page.get_by_role("tab", name="Design").click()
    c.page.wait_for_timeout(600)

    # what the screen applies to: with nothing selected the inspector shows the screen's own settings
    c.page.get_by_role("tab", name="Inspector").click()
    c.page.keyboard.press("Escape")
    c.page.locator("[data-preview] [data-panel]").first.click()
    c.page.keyboard.press("Escape")
    where = c.page.get_by_label("where (expression)")
    rev = c.rev()
    where.fill("$.productType == 'IRS_FIXFLOAT'")
    where.blur()
    c.settle(rev)
    prio = c.page.get_by_label("priority")
    rev = c.rev()
    prio.fill("500")
    prio.blur()
    c.settle(rev)
    assert "priority: 500" in c.page.evaluate("window.drishtiWorkbench.store.state.yaml"), "the match was not changed"
    c.save("p1-14-applies-to.jpg", ".wb-right")

    # propose
    c.page.fill("[data-note]", "First draft from five rates trades; checked on all five")
    c.page.locator("[data-save]").click()
    try:
        c.until("document.querySelector('[data-say]').textContent.indexOf('Submitted for review as P-') >= 0", 20)
    except TimeoutError:
        c.page.screenshot(path="/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/dbg.png")
        raise RuntimeError("not submitted: " + c.page.locator("[data-say]").inner_text() + str(c.page.evaluate("window.drishtiWorkbench.store.state.status")))
    pid = re.search(r"P-\d+", c.page.locator("[data-say]").inner_text()).group(0)
    c.page.wait_for_timeout(600)
    c.save("p1-15-proposed.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 190})
    _state["p1"] = {"pid": pid, "design": c.page.url.rsplit("/", 1)[1]}

    # the approver
    login(c, "ravi")
    c.page.goto(c.base + "/build/reviews")
    c.page.wait_for_timeout(1000)
    c.save("p1-16-reviews.jpg")
    c.page.goto(f"{c.base}/build/reviews/{pid}")
    c.page.locator("[data-evidence]").wait_for()
    c.page.wait_for_timeout(600)
    c.page.locator("[data-evidence]").scroll_into_view_if_needed()
    c.save("p1-17-evidence.jpg", "[data-evidence]")
    c.page.get_by_role("button", name="Approve and publish").click()
    c.page.wait_for_timeout(1500)
    c.save("p1-18-approved.jpg")

    # live
    login(c, "asha")
    c.page.goto(c.base + "/build")
    c.page.wait_for_timeout(800)
    c.save("p1-19-live-status.jpg", f"[data-design='{_state['p1']['design']}']")
    c.page.goto(c.base + "/t")
    c.page.fill("#cmdInput", "TRD productType=IRS_FIXFLOAT")
    c.page.keyboard.press("Enter")
    c.page.locator("table a").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1000)
    c.page.locator("table a").first.click()
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(2000)
    c.save("p1-20-live-view.jpg")


# ---- project 2: by hand, from an empty Sutra -------------------------------------------------------------------------------------------------

DESK_FILES = [ROOT / "docs" / "guides" / "tutorial-data" / f"desk-{d}.json" for d in ("rates", "credit", "fx")]


STRIP_JS = r"""(() => { const cm = document.querySelector('.CodeMirror').CodeMirror; let n = -1;
  cm.replaceRange('sutra: desk-report', {line: 1, ch: 0}, {line: 1, ch: 60});
  cm.replaceRange('description: A desk at a glance, with headline figures, facts, P&L, positions by desk and book, and exposure by unit.', {line: 3, ch: 0}, {line: 3, ch: 200});
  cm.eachLine((h) => { if (n < 0 && h.text.indexOf('panels:') === 0) { n = cm.getLineNumber(h); } });
  cm.replaceRange('strip:\n  - { label: Open trades, bind: $.openTrades, fmt: amount0 }\n', {line: n, ch: 0}); })()"""


def drag_field(c: Ctx, text: str, panel_id: str, where: str = "middle"):
    """Drags the shape field whose row holds `text` onto a panel: its middle binds it, its bottom rim asks for suggestions."""
    c.page.locator("[data-filter]").fill(text)
    c.page.wait_for_timeout(500)
    row = c.page.locator('[data-tree] li[aria-level="1"] > .bs-row', has=c.page.locator(".bs-name", has_text=re.compile(rf"^{re.escape(text)}$"))).first
    row.scroll_into_view_if_needed()
    s0, t = c.box(row), c.box(c.panel(panel_id))
    y = t["y"] + t["height"] / 2 if where == "middle" else t["y"] + t["height"] - 6
    c.page.mouse.move(s0["x"] + 30, s0["y"] + 5)
    c.page.mouse.down()
    c.page.mouse.move(s0["x"] + 80, s0["y"] + 30, steps=3)
    c.page.mouse.move(t["x"] + t["width"] / 2, y, steps=10)
    c.page.wait_for_timeout(200)


def add_panel(c: Ctx, kind: str):
    """+ Add panel: the chooser, a filter, Enter."""
    rev = c.rev()
    c.page.get_by_role("button", name="Add panel", exact=True).click()
    c.page.keyboard.type(kind)
    c.page.wait_for_timeout(300)
    c.page.keyboard.press("Enter")
    c.settle(rev)


def new_panel(c: Ctx, kind: str) -> str:
    """Adds a panel of `kind` through the chooser and returns its id (the one that was not there before)."""
    known = c.page.evaluate("window.drishtiWorkbench.canvas.ids()")
    add_panel(c, kind)
    return next(i for i in c.page.evaluate("window.drishtiWorkbench.canvas.ids()") if i not in known)


def field(c: Ctx, label: str, value: str):
    """Types into an inspector field (found by its label) and leaves it, which sends the change."""
    box = c.page.locator("[data-inspector]").get_by_label(label, exact=True).first
    rev = c.rev()
    box.fill(value)
    box.blur()
    c.settle(rev)


@shot("p2-01-empty.jpg", also=("p2-00-start-empty.jpg", "p2-02-chooser.jpg", "p2-03-metric.jpg", "p2-04-expression.jpg", "p2-05-metric-done.jpg", "p2-06-duplicated.jpg", "p2-07-kpi-row.jpg", "p2-08-bind.jpg", "p2-09-kv.jpg", "p2-10-suggest-pivot.jpg", "p2-11-panels.jpg", "p2-12-pivot-by.jpg", "p2-13-title.jpg", "p2-14-strip-yaml.jpg", "p2-15-strip.jpg", "p2-16-screen-settings.jpg", "p2-17-phone-light.jpg", "p2-18-tests.jpg", "p2-19-tests-clean.jpg", "p2-20-proposed.jpg", "p2-21-live.jpg"))
def project2(c: Ctx):
    login(c, "asha")
    c.page.goto(c.base + "/build/new")
    c.page.locator("#bsFiles").set_input_files([str(f) for f in DESK_FILES])
    c.page.get_by_text("3 files ready.").wait_for()
    c.page.locator('input[name=start][value=empty]').check()
    c.page.fill("#bsName", "Desk report")
    c.page.fill("#bsDesignKind", "desk-report")
    c.page.evaluate("window.scrollBy(0, 400)")
    c.page.wait_for_timeout(300)
    c.save("p2-00-start-empty.jpg")
    c.page.locator("[data-create]").click()
    c.page.wait_for_url("**/build/d/*")
    c.until("window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    c.made.append(c.page.url.rsplit("/", 1)[1])
    c.page.wait_for_timeout(1500)
    c.save("p2-01-empty.jpg")
    c.page.get_by_role("button", name="Add panel", exact=True).click()
    c.page.keyboard.type("metr")
    c.page.wait_for_timeout(400)
    c.save("p2-02-chooser.jpg")
    c.page.keyboard.press("Enter")
    c.page.wait_for_timeout(1500)
    c.save("p2-03-metric.jpg")
    # the tile's options, the expression with completion
    field(c, "title", "Mark to market")
    c.page.locator("[data-inspector]").get_by_label("fmt", exact=True).select_option("signed0")
    c.page.locator("[data-inspector]").get_by_label("tone", exact=True).select_option("sign")
    field(c, "unit", "USD")
    field(c, "span", "4")
    delta = c.page.locator("[data-inspector]").get_by_label("delta", exact=True)
    delta.fill("$.mtm - $.mtmY")
    c.page.wait_for_timeout(500)
    c.save("p2-04-expression.jpg", ".wb-right")
    delta.fill("$.mtm - $.mtmYesterday")
    delta.blur()
    field(c, "deltaFmt", "signed0")
    field(c, "caption", "versus yesterday's close")
    c.page.wait_for_timeout(500)
    c.save("p2-05-metric-done.jpg")
    # two more tiles: duplicate (right click), then change what differs
    for n in (1, 2):
        c.panel(c.page.evaluate("window.drishtiWorkbench.canvas.ids()[0]")).click(button="right", position={"x": 40, "y": 14})
        c.page.get_by_role("option", name=re.compile("Duplicate")).click()
        c.page.wait_for_timeout(1500)
    c.save("p2-06-duplicated.jpg")
    for id_, title, value, delta, fmt, tone in (("metric3", "VaR 99%", "$.var99", "$.var99 - $.var99Yesterday", "amount0", ""),
                                                ("metric2", "Limit use", "$.limitUse", "$.limitUse - $.var99Yesterday / $.limit", "pct2", "")):
        c.panel(id_).click(position={"x": 30, "y": 70})
        c.page.wait_for_timeout(500)
        field(c, "value *", value)
        field(c, "title", title)
        field(c, "delta", delta)
        c.page.wait_for_timeout(700)
        c.page.locator("[data-inspector]").get_by_label("fmt", exact=True).select_option(fmt)
        c.page.locator("[data-inspector]").get_by_label("tone", exact=True).select_option(label="(none)")
        field(c, "caption", "versus yesterday's close")
        if fmt == "pct2":
            field(c, "deltaFmt", "pct2")
            field(c, "unit", "of the limit")
    c.page.wait_for_timeout(800)
    c.save("p2-07-kpi-row.jpg")

    # a key/value panel, its columns made by dropping fields on it
    kv = new_panel(c, "kv")
    c.page.wait_for_timeout(1000)
    field(c, "title", "Desk")
    field(c, "title", "Desk")
    for n, name in enumerate(("name", "headOfDesk", "region", "reportDate")):
        drag_field(c, name, kv)
        if n == 1:
            c.save("p2-08-bind.jpg")
        rev = c.rev()
        c.page.mouse.up()
        c.settle(rev)
    c.page.locator("[data-filter]").fill("")
    c.page.wait_for_timeout(500)
    c.save("p2-09-kv.jpg")

    # a field dropped on the edge of a panel asks for suggestions: a line, a pivot, a tree table
    last = kv
    for n, (name, kind) in enumerate((("pnlHistory", "line"), ("positions", "pivot"), ("units", "table"))):
        drag_field(c, name, last, "rim")
        rev = c.rev()
        c.page.mouse.up()
        c.page.locator(".wb-menu").wait_for()
        c.page.wait_for_timeout(400)
        if n == 1:
            c.save("p2-10-suggest-pivot.jpg")
        known = c.page.evaluate("window.drishtiWorkbench.canvas.ids()")
        c.page.get_by_role("option").filter(has_text=re.compile(rf"^\s*{kind}")).first.click()
        c.settle(rev)
        last = next(i for i in c.page.evaluate("window.drishtiWorkbench.canvas.ids()") if i not in known)
    c.page.locator("[data-filter]").fill("")
    c.page.wait_for_timeout(500)
    c.save("p2-11-panels.jpg")

    # the pivot's row groups: by is a list
    c.panel("pivot").locator(".pnl-h").click()
    c.page.wait_for_timeout(700)
    ins = c.page.locator("[data-inspector]")
    field(c, "title", "MTM by desk and book (USD)")
    by1 = ins.get_by_role("textbox", name="by 1", exact=True)
    rev = c.rev()
    by1.fill("desk")
    by1.blur()
    c.settle(rev)
    ins.get_by_role("button", name="Add by").click()
    c.page.wait_for_timeout(300)
    by2 = ins.get_by_role("textbox", name="by 2", exact=True)
    rev = c.rev()
    by2.fill("book")
    by2.blur()
    c.settle(rev)
    ins.get_by_label("expand", exact=True).fill("1")
    ins.get_by_label("expand", exact=True).blur()
    c.page.wait_for_timeout(1200)
    c.panel("pivot").scroll_into_view_if_needed()
    c.save("p2-12-pivot-by.jpg")

    # the title line
    c.page.evaluate("window.scrollTo(0, 0); document.querySelector('[data-canvas]').scrollTo(0, 0)")
    c.page.locator("[data-preview] .vtitle").click()
    c.page.wait_for_timeout(600)
    field(c, "Id (expression)", "$.deskId")
    field(c, "Pill (text, may hold ${…})", "Desk · ${$.name}")
    c.page.wait_for_timeout(800)
    c.save("p2-13-title.jpg")

    # the strip under the title: typed in the YAML tab (it is an operation like any other), then edited as a form
    c.page.get_by_role("tab", name="YAML").click()
    c.page.wait_for_timeout(600)
    rev = c.rev()
    c.page.evaluate(STRIP_JS)
    c.until("window.drishtiWorkbench.store.state.yaml.indexOf('sutra: desk-report') >= 0 && window.drishtiWorkbench.store.state.yaml.indexOf('strip:') >= 0")
    c.page.wait_for_timeout(1500)
    c.page.evaluate("document.querySelector('.CodeMirror').CodeMirror.scrollTo(0, 0)")
    c.save("p2-14-strip-yaml.jpg")
    c.page.get_by_role("tab", name="Design").click()
    c.page.wait_for_timeout(1000)
    c.page.locator("[data-preview] dl.strip").click()
    c.page.wait_for_timeout(600)
    c.save("p2-15-strip.jpg")

    # a function key for the pivot, and the screen's own settings
    c.panel("pivot").locator(".pnl-h").click()
    c.page.wait_for_timeout(500)
    rev = c.rev()
    ins.get_by_label("key", exact=True).select_option("F2")
    c.settle(rev)
    c.page.keyboard.press("Escape")
    c.page.locator("[data-preview]").click(position={"x": 4, "y": 4})
    c.page.keyboard.press("Escape")
    c.page.wait_for_timeout(500)
    c.save("p2-16-screen-settings.jpg", ".wb-right")

    # phone width and a light theme
    c.page.locator('[data-width="phone"]').click()
    c.page.locator("[data-theme-pick]").select_option(label="Parchment")
    c.page.wait_for_timeout(1500)
    c.save("p2-17-phone-light.jpg")
    c.page.locator('[data-width="desktop"]').click()
    c.page.locator("[data-theme-pick]").select_option(label="Terminal")
    c.page.wait_for_timeout(800)

    # the check on every sample: the empty cells are the panel that has nothing to show
    c.page.get_by_role("tab", name="Tests").click()
    c.page.locator(".wb-matrix").wait_for(timeout=30000)
    c.page.wait_for_timeout(1200)
    c.save("p2-18-tests.jpg")
    c.panel("refs").hover()
    c.page.get_by_role("button", name=re.compile("^Remove panel Linked entities")).first.click()
    c.page.locator("[data-toast]").wait_for()
    c.page.wait_for_timeout(2500)
    c.save("p2-19-tests-clean.jpg")

    # propose it; the approver publishes it
    c.page.fill("[data-note]", "Desk report: KPI tiles, desk facts, P&L curve, MTM pivot by desk and book, exposure tree")
    c.page.locator("[data-save]").click()
    c.until("document.querySelector('[data-say]').textContent.indexOf('Submitted for review as P-') >= 0", 30)
    pid = re.search(r"P-\d+", c.page.locator("[data-say]").inner_text()).group(0)
    design = c.page.url.rsplit("/", 1)[1]
    c.page.wait_for_timeout(600)
    c.save("p2-20-proposed.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 190})
    login(c, "ravi")
    c.page.goto(f"{c.base}/build/reviews/{pid}")
    c.page.get_by_role("button", name="Approve and publish").click()
    c.page.wait_for_timeout(1500)
    login(c, "asha")
    c.page.goto(f"{c.base}/build/d/{design}")
    c.until("window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    c.page.wait_for_timeout(2500)
    c.save("p2-21-live.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 190})



# ---- project 3: change a live Sutra ----------------------------------------------------------------------------------------------------------

def open_registry(c: Ctx, sutra: str, name: str, samples: list):
    """Build -> New screen -> An existing Sutra from the registry, started as that Sutra, with samples added."""
    c.page.goto(c.base + "/build/new")
    c.page.wait_for_timeout(800)
    value = c.page.evaluate("(s) => [...document.querySelectorAll('#bsRegistry option')].map(o => o.value).find(v => v.indexOf(s + '@') === 0)", sutra)
    c.page.locator("#bsRegistry").select_option(value)
    c.page.locator("input[name=start][value=existing]").check()
    c.page.fill("#bsName", name)
    c.page.fill("#bsDesignKind", "trade")
    c.page.locator("[data-create]").click()
    c.page.wait_for_url("**/build/d/*")
    c.until("window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    c.made.append(c.page.url.rsplit("/", 1)[1])
    if samples:
        c.page.locator("[data-add-files]").set_input_files([str(f) for f in samples])
        c.until("window.drishtiWorkbench.store.state.samples.length >= %d" % len(samples))
        c.page.locator("[data-preview] [data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(2500)


def submit_and_id(c: Ctx, note: str) -> str:
    c.page.fill("[data-note]", note)
    c.page.locator("[data-save]").click()
    c.until("document.querySelector('[data-say]').textContent.indexOf('Submitted for review as P-') >= 0", 30)
    return re.search(r"P-\d+", c.page.locator("[data-say]").inner_text()).group(0)


def approve(c: Ctx, who: str, pid: str, password: str = PASSWORD):
    login(c, who, password)
    c.page.goto(f"{c.base}/build/reviews/{pid}")
    c.page.locator("[data-evidence]").wait_for()
    c.page.get_by_role("button", name="Approve and publish").click()
    c.page.wait_for_timeout(1500)


@shot("p3-01-registry.jpg", also=("p3-02-opened.jpg", "p3-03-changed.jpg", "p3-04-versions-base.jpg", "p3-05-versions-earlier.jpg", "p3-06-base-moved.jpg",
                                  "p3-07-rebase.jpg", "p3-08-rebased.jpg", "p3-09-proposed.jpg", "p3-10-live-v3.jpg"))
def project3(c: Ctx):
    login(c, "asha")
    c.page.goto(c.base + "/build/new")
    c.page.wait_for_timeout(800)
    c.page.locator("#bsRegistry").scroll_into_view_if_needed()
    value = c.page.evaluate("(s) => [...document.querySelectorAll('#bsRegistry option')].map(o => o.value).find(v => v.indexOf(s + '@') === 0)", "rates-trade")
    c.page.locator("#bsRegistry").select_option(value)
    c.page.locator("input[name=start][value=existing]").check()
    c.page.fill("#bsName", "Rates trade, with a headline")
    c.page.fill("#bsDesignKind", "trade")
    c.page.evaluate("window.scrollBy(0, 300)")
    c.page.wait_for_timeout(400)
    c.save("p3-01-registry.jpg")
    c.page.locator("[data-create]").click()
    c.page.wait_for_timeout(3000)
    c.page.wait_for_url("**/build/d/*")
    c.until("window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    c.made.append(c.page.url.rsplit("/", 1)[1])
    mine = c.page.url
    c.page.locator("[data-add-files]").set_input_files([str(f) for f in TRADES[:3]])
    c.until("window.drishtiWorkbench.store.state.samples.length >= 3")
    c.page.locator("[data-preview] [data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(2500)
    c.save("p3-02-opened.jpg")

    # the change: a headline tile at the top
    rev = c.rev()
    c.page.evaluate("window.drishtiWorkbench.store.send([{op: 'addPanel', kind: 'metric', at: {before: 'details', span: 12}, options: {value: '$.mtm', title: 'Mark to market', fmt: 'signed0', tone: 'sign', unit: 'USD'}}])")
    c.settle(rev)
    c.page.wait_for_timeout(1200)
    c.save("p3-03-changed.jpg")
    c.page.locator("#wbTabVer").click()
    c.page.wait_for_timeout(1500)
    c.save("p3-04-versions-base.jpg")
    c.page.locator("#wbCompareWith").select_option(index=1)
    c.page.wait_for_timeout(1500)
    c.save("p3-05-versions-earlier.jpg")

    # a colleague changes the live Sutra first
    login(c, "ravi")
    open_registry(c, "rates-trade", "Rates trade, clearer name", TRADES[:2])
    rev = c.rev()
    c.page.evaluate("window.drishtiWorkbench.store.send([{op: 'setOption', panel: 'details', option: 'title', value: 'Trade details'}])")
    c.settle(rev)
    pid = submit_and_id(c, "Calls the details panel Trade details")
    approve(c, "drishti-dev-admin", pid, "drishti-dev-admin123")

    # back in my design: the base has moved
    login(c, "asha")
    c.page.goto(mine)
    c.until("window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    c.page.locator("[data-preview] [data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(2500)
    c.page.get_by_role("tab", name="Problems").click()
    c.page.wait_for_timeout(800)
    c.save("p3-06-base-moved.jpg")
    c.page.locator("[data-ship-menu]").click()
    c.page.locator(".wb-menu").wait_for()
    c.page.wait_for_timeout(300)
    c.save("p3-07-rebase.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 470})
    c.page.get_by_role("option", name=re.compile("Rebase onto")).click()
    c.until("document.querySelector('[data-say]').textContent.indexOf('Rebased onto') >= 0", 30)
    c.page.wait_for_timeout(2500)
    c.save("p3-08-rebased.jpg")

    # propose with evidence; the approver publishes the next version
    pid = submit_and_id(c, "Adds a headline tile for mark to market; rebased on the Trade details rename")
    c.page.wait_for_timeout(600)
    c.save("p3-09-proposed.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 190})
    approve(c, "ravi", pid)
    login(c, "asha")
    c.page.goto(mine)
    c.until("window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    c.page.wait_for_timeout(2500)
    c.save("p3-10-live-v3.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 190})


# ---- project 4: keyboard only ----------------------------------------------------------------------------------------------------------------

def keys(c: Ctx, *names, wait=250):
    for n in names:
        c.page.keyboard.press(n)
        c.page.wait_for_timeout(wait)


def to_panel(c: Ctx):
    """After an add or a bind the focus may be in the inspector: Esc there puts it back on the selected panel (on the canvas Esc would deselect)."""
    if c.page.evaluate("!!(document.activeElement && document.activeElement.closest('[data-inspector]'))"):
        keys(c, "Escape")


def reach(c: Ctx, kind: str):
    """The keys act on the panel that has the focus; Up and Down move the focus (and the selection) from panel to panel until it is the `kind` one."""
    for _ in range(8):
        if c.page.evaluate("(document.activeElement && document.activeElement.dataset.kind) || ''") == kind:
            return
        keys(c, "ArrowDown")
    raise RuntimeError("never reached the " + kind + " panel by keys")


def typed(c: Ctx, text: str):
    c.page.keyboard.type(text, delay=40)
    c.page.wait_for_timeout(350)


@shot("p4-01-skip-link.jpg", also=("p4-02-palette-add.jpg", "p4-03-first-panel.jpg", "p4-04-chooser.jpg", "p4-04b-arrow-select.jpg", "p4-05-moved.jpg", "p4-06-bind.jpg", "p4-07-inspector.jpg",
                                   "p4-08-removed.jpg", "p4-09-restored.jpg", "p4-10-run-check.jpg", "p4-11-yaml.jpg"))
def project4(c: Ctx):
    login(c, "asha")
    id_ = c.design("Keyboard only", files={"desk-rates.json": DESK_FILES[0].read_text(), "desk-credit.json": DESK_FILES[1].read_text()}, kind="desk-report")
    c.made.append(id_)
    c.page.goto(f"{c.base}/build/d/{id_}")
    c.until("window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    c.page.wait_for_timeout(2500)
    c.page.evaluate("document.activeElement && document.activeElement.blur(); window.scrollTo(0, 0)")
    # the very first Tab stop of the workbench is the skip link
    for _ in range(30):
        c.page.keyboard.press("Tab")
        if c.page.evaluate("document.activeElement && document.activeElement.hasAttribute('data-skip-canvas')"):
            break
    c.page.wait_for_timeout(300)
    c.save("p4-01-skip-link.jpg", clip={"x": 0, "y": 0, "width": 1440, "height": 260})
    keys(c, "Enter")
    # the command palette
    keys(c, "Control+k")
    typed(c, "add panel")
    c.save("p4-02-palette-add.jpg")
    keys(c, "Escape")
    c.page.keyboard.press("Control+k")
    c.page.wait_for_timeout(300)
    typed(c, "add panel: metric")
    rev = c.rev()
    keys(c, "Enter", wait=600)
    c.settle(rev)
    c.save("p4-03-first-panel.jpg")
    to_panel(c)
    # the chooser from the canvas: N
    keys(c, "n")
    typed(c, "kv")
    c.save("p4-04-chooser.jpg")
    rev = c.rev()
    keys(c, "Enter", wait=600)
    c.settle(rev)
    reach(c, "kv")
    c.save("p4-04b-arrow-select.jpg")
    # move and size with the keys
    rev = c.rev()
    keys(c, "Alt+ArrowLeft", wait=600)
    c.settle(rev)
    for _ in range(3):
        rev = c.rev()
        keys(c, "Shift+ArrowLeft", wait=500)
        c.settle(rev)
    c.save("p4-05-moved.jpg")
    # Bind field...: B, type, Enter
    for n, name in enumerate(("headOfDesk", "region")):
        keys(c, "b")
        typed(c, name)
        if n == 0:
            c.save("p4-06-bind.jpg")
        rev = c.rev()
        keys(c, "Enter", wait=600)
        c.settle(rev)
        reach(c, "kv")
    # Enter opens the inspector; Esc returns to the panel
    keys(c, "Enter", wait=600)
    c.save("p4-07-inspector.jpg")
    keys(c, "Escape")
    # Delete removes (Undo is Ctrl+Z)
    keys(c, "Delete", wait=700)
    c.save("p4-08-removed.jpg")
    keys(c, "Control+z", wait=900)
    c.save("p4-09-restored.jpg")
    # more of the palette: run the check, show the YAML
    keys(c, "Control+k")
    typed(c, "run check")
    keys(c, "Enter", wait=1500)
    c.save("p4-10-run-check.jpg")
    keys(c, "Control+k")
    typed(c, "switch to yaml")
    keys(c, "Enter", wait=900)
    c.save("p4-11-yaml.jpg")


# ---- after the projects: share and ship ------------------------------------------------------------------------------------------------------

@shot("p5-01-ship-menu.jpg", also=("p5-02-share-link.jpg",))
def project5(c: Ctx):
    login(c, "asha")
    c.page.goto(f"{c.base}/build/d/{_state['p1']['design']}")
    c.until("window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    c.page.locator("[data-preview] [data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(2000)
    c.page.locator("[data-ship-menu]").click()
    c.page.locator(".wb-menu").wait_for()
    c.page.wait_for_timeout(300)
    c.save("p5-01-ship-menu.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 470})
    c.page.get_by_role("option", name=re.compile("Create a read-only link")).click()
    c.page.locator("[data-share-box]:not([hidden])").wait_for()
    c.until("document.querySelector('[data-share-url]').value.indexOf('?share=') > 0")
    c.page.wait_for_timeout(500)
    c.save("p5-02-share-link.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 200})
