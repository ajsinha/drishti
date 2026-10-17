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

"""Pictures of tutorial 15, AI in Drishti (docs/tutorials/15-ai-in-drishti.md), written to docs/tutorials/img/t15/.

The stack must be started with the AI layer on and the MOCK provider (tutorials/data/ai/mock.yaml in the AI folder of the server and of the console): the pictures show
Admin, AI with a Test connection for each provider, a report drafted from words, a question asked of the data, a number explained, and what the AI policy says to a
person it does not list."""
from __future__ import annotations

from _bi import bi, clean_items, open_report, quiet_inbox, start_design, submit_design
from _tut import OUT, ROOT, SERVER_ENV, elem_shot, go, login  # noqa: F401
from shotlib import Ctx, register
from t13 import as_, people

shot = register("t15", OUT / "t15")
AI_POLICY = ('{"drishti.ai.policy.features.[bi-ask].roles[0]":"admin","drishti.ai.policy.features.[bi-ask].roles[1]":"bi-writer",'
             '"drishti.ai.policy.per-user-per-minute":30}')


def start_servers(work):
    """A scratch stack with the AI layer on and the MOCK provider: the tutorial's provider files in <work>/ai (the server and the console read the same folder), a switched-off
    vendor provider, and the policy of the tutorial (the ask feature open to admin and bi-writer only)."""
    import shutil

    import shotlib
    ai = work / "ai"
    ai.mkdir(exist_ok=True)
    for name in ("mock.yaml", "local-model.yaml"):
        shutil.copy(ROOT / "docs" / "tutorials" / "data" / "ai" / name, ai / name)
    (ai / "fast.yaml").write_text((ROOT / "config" / "ai" / "groq.yaml.example").read_text().replace("enabled: true", "enabled: false"))
    return shotlib.start_servers(work, {**SERVER_ENV, "DRISHTI_AI_ENABLED": "true", "DRISHTI_AI_DIR": str(ai), "DRISHTI_AI_DEFAULT_MODEL": "mock/m", "SPRING_APPLICATION_JSON": AI_POLICY})
WORLD_REPORT = "file:report:world-economics"
WORLD_MODEL = "mq:world-economics@1"                     # the designer's choice for the model world-economics
MY_WORLD = "My world economics"


def my_world(c: Ctx) -> str:
    """A model of the person's own over the World economics datasets (the shipped one is read-only), made afresh; returns its id."""
    clean_items(c, MY_WORLD)
    body = (ROOT / "config" / "bi" / "models" / "world-economics.model.yaml").read_text().replace("model: world-economics", "model: my-world-economics")
    body = body.replace('title: "World economics"', 'title: "' + MY_WORLD + '"')
    return bi(c, "POST", "/workspaces/me/items", {"kind": "model", "title": MY_WORLD, "body": body})["id"]


@shot("01-admin-ai.jpg", also=("02-test-connection.jpg",))
def admin_ai(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    go(c, "/admin/ai", wait="[data-ai-layer=console]", settle=1500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("01-admin-ai.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 744})
    for name in ("mock", "local-model", "fast"):
        c.page.locator(f'[data-ai-layer="console"] [data-ai-test="{name}"]').click()
        c.page.wait_for_function("(n) => document.querySelector('[data-ai-test-result=\"' + n + '\"]').textContent.length > 12 && !/Testing/.test(document.querySelector('[data-ai-test-result=\"' + n + '\"]').textContent)", arg=name, timeout=30000)
    c.page.wait_for_timeout(500)
    elem_shot(c, "02-test-connection.jpg", '[data-ai-layer="console"] table', pad=8)


@shot("03-describe.jpg", also=("04-draft-used.jpg",))
def describe(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    clean_items(c, "Drafted from words")
    start_design(c, WORLD_MODEL, "Drafted from words")
    submit_design(c)
    c.page.click("[data-ai-draft]")
    c.page.fill("[data-ai-request]", "GDP by region as a bar chart and the total as a card")
    c.page.click("[data-ai-go]")
    c.page.wait_for_selector("[data-ai-yaml]", state="visible", timeout=30000)
    c.page.wait_for_timeout(800)
    c.save("03-describe.jpg")
    c.page.click("[data-ai-use]")
    c.page.wait_for_selector('[data-view="yaml"]:not([hidden])')
    c.page.wait_for_timeout(1000)
    c.page.click('[data-tab="canvas"]')
    c.page.wait_for_timeout(3500)
    c.save("04-draft-used.jpg")


@shot("05-ask.jpg", also=("06-ask-query.jpg", "07-explain.jpg"))
def ask_and_explain(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    open_report(c, WORLD_REPORT, wait_map=True)
    c.page.locator('[data-visual="gdp"] [data-act="explain"]').click()
    c.page.wait_for_selector("[data-ai-explain]", state="visible", timeout=30000)
    c.page.wait_for_timeout(1200)
    c.save("07-explain.jpg")
    c.page.keyboard.press("Escape")
    c.page.wait_for_timeout(500)
    c.page.locator("[data-ai-ask]").scroll_into_view_if_needed()
    c.page.fill("[data-ai-q]", "GDP by region")
    c.page.click("[data-ai-ask] button[type=submit]")
    c.page.wait_for_selector("[data-ai-answer] .bi-ai-table tbody tr", timeout=30000)
    c.page.wait_for_timeout(1200)
    elem_shot(c, "05-ask.jpg", "[data-ai-ask]", pad=10, extra=40)
    c.page.locator(".bi-ai-query summary").click()
    c.page.wait_for_timeout(400)
    elem_shot(c, "06-ask-query.jpg", "[data-ai-ask]", pad=10, extra=40)


@shot("09-suggest.jpg", also=("10-check-explained.jpg",))
def suggest_and_check(c: Ctx):
    """Suggest visuals in the designer (nothing is added until Add), then Check on a broken draft, explained in plain words."""
    login(c, height=900)
    quiet_inbox(c)
    clean_items(c, "Suggested visuals")
    start_design(c, WORLD_MODEL, "Suggested visuals")
    submit_design(c)
    c.page.click("[data-ai-suggest]")
    c.page.fill("[data-ai-goal]", "where the GDP sits, and a way to filter by income group")
    c.page.click("[data-ai-suggest-go]")
    c.page.wait_for_selector("[data-ai-suggestion]", timeout=30000)
    c.page.wait_for_timeout(900)
    c.save("09-suggest.jpg")
    c.page.reload()                                              # a fresh page, so the YAML tab holds what is typed
    c.page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    c.page.wait_for_selector("[data-ai-suggest]:not([hidden])", timeout=15000)
    broken = ("rachana: 2\nreport: broken\nversion: 1\ntitle: Broken\nmodel: world-economics@1\npages:\n  - id: p\n    title: P\n    visuals:\n"
              "      - { id: v, type: bar, title: V, at: { x: 0, y: 0, w: 4, h: 3 }, wells: { axis: [economies.nope], values: [Nothing] } }\n")
    c.page.evaluate("(t) => window.drishtiBi.design.loadYaml(t)", broken)
    c.page.wait_for_timeout(900)
    c.page.click("[data-check]")
    c.page.wait_for_selector("[data-check-box]:not([hidden])")
    c.page.click("[data-ai-explain-checks]")
    c.page.wait_for_selector("[data-ai-checks] li", timeout=30000)
    c.page.wait_for_timeout(600)
    c.save("10-check-explained.jpg")


@shot("11-dataset-helper.jpg")
def dataset_helper(c: Ctx):
    """Ask the AI helper in the model designer, on a model of one's own over the World economics datasets: a measure and two field descriptions, each with Accept."""
    login(c, height=900)
    quiet_inbox(c)
    made = my_world(c)
    c.page.set_viewport_size({"width": 1280, "height": 1180})
    c.page.goto(c.base + "/bi/md/" + made)
    c.page.wait_for_selector("[data-md-root][data-ready=yes]", timeout=30000)
    c.page.click("[data-ai-helper]")
    c.page.wait_for_selector("[data-ai-item]", timeout=30000)
    c.page.wait_for_timeout(800)
    c.save("11-dataset-helper.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 1100})


@shot("08-policy-refusal.jpg")
def policy_refusal(c: Ctx):
    people(c)
    as_(c, "sue")
    open_report(c, WORLD_REPORT, wait_map=True)
    c.page.locator("[data-ai-ask]").scroll_into_view_if_needed()
    c.page.fill("[data-ai-q]", "GDP by region")
    c.page.click("[data-ai-ask] button[type=submit]")
    c.page.wait_for_selector("[data-ai-msg]:not(:empty)", timeout=30000)
    c.page.wait_for_timeout(1000)
    elem_shot(c, "08-policy-refusal.jpg", "[data-ai-ask]", pad=10, extra=20)
