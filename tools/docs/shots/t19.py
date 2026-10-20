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

"""Pictures of tutorial 19, A cluster of three (docs/tutorials/19-a-cluster-of-three.md), written to docs/tutorials/img/t19/.

A real cluster: three built servers (a, b, c on 18783 to 18785) and a console on 17784 given server a alone, made by the console's own test
cluster (drishti-console/tests/net_stack.py: small synthetic trades split three ways, MX-00003 on c only, the finance demo ticking on b).
Run it on its own, with the console's address:

    DRISHTI_SHOTS_WORK=~/drishti drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t19 --base http://127.0.0.1:17784

The pictures, in the tutorial's order: Members with the three alive (the table and the hash ring) and the console's servers, the Files tab with gossip.yaml open, a trade only
c holds opened through a, Explain naming that route, the capability map, Access, Security, and the console's servers after a is stopped (the
last picture stops a, so it runs last). The scratch folder's path is shown as ~/drishti, as the tutorial writes it."""
from __future__ import annotations

import sys

from _tut import OUT, ROOT, elem_shot, go, page_shot  # noqa: F401
from shotlib import Ctx, register

sys.path.insert(0, str(ROOT / "drishti-console" / "tests"))
sys.path.insert(0, str(ROOT / "drishti-console"))

shot = register("t19", OUT / "t19")
STACK: dict = {}
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide t19): it starts its own cluster
WIDE, HIGH = 1280, 860


def start_servers(work):
    """The cluster of three and its console (net_stack.NetStack); the driver stops the processes it returns."""
    from net_stack import NetStack
    stack = NetStack(work).start()
    STACK["stack"], STACK["work"] = stack, str(work)
    return [p for p in stack.procs.values()]


SHOWN_IP = "192.168.1.20"     # the pictures show this example address, never the real one of the machine that took them


def tidy(c: Ctx):
    """The scratch folder as ~/drishti, as the tutorial writes paths; this machine's real address (the members advertise it, never
    127.0.0.1) as the tutorial's example address."""
    from net_stack import HOST
    for real, shown in ((STACK.get("work"), "~/drishti"), (HOST, SHOWN_IP)):
        if real:
            c.page.evaluate("""([w, s]) => { const t = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                for (let n = t.nextNode(); n; n = t.nextNode()) { if (n.nodeValue.includes(w)) { n.nodeValue = n.nodeValue.split(w).join(s); } } }""", [real, shown])


def net(c: Ctx, tab: str, wait: str, settle: int = 1200):
    go(c, "/admin/net" + ("" if tab == "members" else f"?tab={tab}"), width=WIDE, height=HIGH, wait=wait, settle=settle)
    tidy(c)


@shot("01-members.jpg", also=("02-console-servers.jpg",))
def members(c: Ctx):
    net(c, "members", "[data-members]")
    c.until("document.querySelectorAll('[data-member][data-state=alive]').length === 3", 60)
    c.until("document.querySelectorAll('[data-console-servers] tbody tr').length >= 3", 60)
    c.until("document.querySelectorAll('[data-arc][data-state=alive]').length === 3", 30)   # the hash ring beside the table
    c.page.wait_for_timeout(800)                                                             # its arcs have grown in
    tidy(c)
    elem_shot(c, "01-members.jpg", "#netPaneMembers .net-members-ring", pad=12)
    c.page.evaluate("document.querySelector('[data-node-card]').scrollIntoView()")
    elem_shot(c, "02-console-servers.jpg", "[data-console-servers]", pad=12)


@shot("03-files-gossip.jpg")
def files(c: Ctx):
    net(c, "files", "[data-file-list]")
    c.page.locator("[data-file='gossip.yaml']").click()
    c.page.locator("[data-editor]").wait_for(state="visible")
    c.page.wait_for_timeout(600)
    tidy(c)
    elem_shot(c, "03-files-gossip.jpg", "#netPaneFiles", pad=12)


@shot("04-trade-on-c-through-a.jpg")
def trade(c: Ctx):
    go(c, "/v/trade/MX-00003", width=WIDE, height=HIGH, wait="[data-panel]", settle=1500)
    c.save("04-trade-on-c-through-a.jpg", clip={"x": 0, "y": 0, "width": WIDE, "height": 620})


@shot("05-explain.jpg")
def explain(c: Ctx):
    net(c, "routing", "[data-explain-form]")
    c.page.locator("[data-ex-kind]").fill("trade")
    c.page.locator("[data-ex-id]").fill("MX-00003")
    c.page.locator("[data-explain-form] button[type=submit]").click()
    c.page.locator("[data-explained]").wait_for(state="visible")
    c.page.wait_for_timeout(500)
    elem_shot(c, "05-explain.jpg", "#netPaneRouting", pad=12)


@shot("06-capability-map.jpg")
def capability_map(c: Ctx):
    net(c, "map", "[data-map]")
    c.page.locator("[data-map-filter]").fill("trade")
    c.page.wait_for_timeout(600)
    elem_shot(c, "06-capability-map.jpg", "#netPaneMap", pad=12)


@shot("07-access.jpg")
def access(c: Ctx):
    net(c, "access", "[data-access]")
    elem_shot(c, "07-access.jpg", "#netPaneAccess", pad=12)


@shot("08-security.jpg")
def security(c: Ctx):
    net(c, "security", "[data-security]")
    elem_shot(c, "08-security.jpg", "#netPaneSecurity", pad=12)


@shot("09-after-a-stopped.jpg", also=("10-members-phone.jpg",))
def failover(c: Ctx):
    """Stops server a (by its process id, a clean stop) and shows the console on another server; the page needs no new sign-in."""
    stack = STACK.get("stack")
    if stack is not None and "a" in stack.procs:
        stack.stop("a", wait=False)
    for _ in range(60):
        c.page.wait_for_timeout(1000)
        go(c, "/admin/net", width=WIDE, height=HIGH, wait="[data-members]", settle=400)
        if c.page.locator("[data-console-servers]").inner_text().count("set aside") >= 1 and "18783" not in c.page.locator("[data-console-on]").inner_text():
            break
    for _ in range(90):                                                    # a leaves cleanly (up to 30 s with a live stream open)
        go(c, "/admin/net", width=WIDE, height=HIGH, wait="[data-members]", settle=300)
        if c.page.locator("[data-member='drishti-a']").get_attribute("data-state") in ("left", "dead"):
            break
        c.page.wait_for_timeout(1000)
    go(c, "/admin/net", width=WIDE, height=HIGH, wait="[data-members]", settle=800)
    tidy(c)
    elem_shot(c, "09-after-a-stopped.jpg", "#netPaneMembers", pad=12)
    go(c, "/admin/net", width=390, height=844, wait="[data-members]", settle=800)
    tidy(c)
    c.save("10-members-phone.jpg", clip={"x": 0, "y": 0, "width": 390, "height": 844})
