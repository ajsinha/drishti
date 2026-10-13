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

"""Pictures of tutorial 17, Run it in production (docs/tutorials/17-run-it-in-production.md), written to docs/tutorials/img/t17/.

The administration pages an operator lives in: the users (with the development administrator's warning), making an administrator of your own and switching the
development one off, roles, API tokens, the health page (heap, connectors), the caches and the audit log. Run last of the tutorials' pictures: it disables the development
administrator for a moment and enables it again."""
from __future__ import annotations

from _bi import bi, clean_items, quiet_inbox
from _tut import ADMIN, ADMIN_PASSWORD, OUT, SERVER_ENV, call, elem_shot, go, login  # noqa: F401
from shotlib import Ctx, register

shot = register("t17", OUT / "t17")
OPS, OPS_PASSWORD = "ops", "ops-pass-for-the-tutorial-2026"


@shot("01-users.jpg", also=("02-new-user.jpg",))
def users(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    call(c, "POST", f"/admin/api/users/{OPS}/delete", {})
    go(c, "/admin/users", wait="[data-new]", settle=1200)
    c.save("01-users.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 780})
    c.page.click("[data-new]")
    d = c.page.locator("[data-dialog]")
    d.wait_for(state="visible")
    d.locator("input[name=username]").fill(OPS)
    d.locator("input[name=displayName]").fill("Operations")
    d.locator("input[name=email]").fill("ops@desk.example")
    d.locator('input[name=roles][value="admin"]').check()
    d.locator("input[name=password]").fill(OPS_PASSWORD)
    d.locator("input[name=mustChangePassword]").uncheck()
    for box in d.locator("input[name=packs]").all():                       # no pack list on the row: an administrator sees every pack anyway
        box.uncheck()
    c.page.wait_for_timeout(500)
    c.save("02-new-user.jpg", locator="[data-dialog]")
    d.locator("[data-submit]").click()
    c.page.wait_for_timeout(1500)


@shot("03-dev-admin-disabled.jpg")
def switch_off_dev_admin(c: Ctx):
    login(c, OPS, OPS_PASSWORD, height=900)
    go(c, "/admin/users", wait="[data-new]", settle=1200)
    row = c.page.locator(f'tr[data-user="{ADMIN}"]')
    row.locator("[data-toggle]").click()
    c.page.wait_for_timeout(1500)
    c.page.reload()
    c.page.wait_for_selector("[data-new]")
    c.page.wait_for_timeout(800)
    c.save("03-dev-admin-disabled.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 780})
    c.page.locator(f'tr[data-user="{ADMIN}"] [data-toggle]').click()                 # and on again: the other pictures sign in as it
    c.page.wait_for_timeout(1500)


@shot("05-roles.jpg", also=("06-tokens.jpg", "07-account.jpg", "07b-password.jpg"))
def roles_tokens(c: Ctx):
    login(c, height=900)
    go(c, "/admin/roles", wait="table", settle=1500)
    c.save("05-roles.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 760})
    go(c, "/admin/tokens", wait="table", settle=1500)
    c.save("06-tokens.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 460})
    go(c, "/account", wait=None, settle=1500, height=1200)
    elem_shot(c, "07-account.jpg", "#tokens", pad=14)
    elem_shot(c, "07b-password.jpg", "form.adm-pw:has(#pc)", pad=14)


@shot("08-health.jpg", also=("09-caches.jpg", "10-audit.jpg"))
def health(c: Ctx):
    login(c, height=900)
    go(c, "/admin/health", wait="table", settle=2500)
    c.save("08-health.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 380})
    go(c, "/admin/caches", wait="table", settle=1500)
    c.save("09-caches.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})
    go(c, "/admin/audit?subject=drishti-dev-admin", wait="table", settle=1500)
    c.save("10-audit.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 640})


@shot("11-index-reload.jpg")
def index_reload(c: Ctx):
    """Needs a stack that reads the demo lake (DRISHTI_DELTA_ROOT) with `index.days: 3` on the trading-store connector and a DRISHTI_INDEX_DIR of its own;
    on the plain scratch stack the connector is down, so the picture is skipped and the one in the repository stays."""
    import os
    if not os.environ.get("DRISHTI_SHOTS_INDEX"):
        print("  skipped: set DRISHTI_SHOTS_INDEX=1 on a stack with the lake and index.days: 3 on trading-store")
        return
    login(c, height=900)
    go(c, "/admin/connectors/trading-store", wait="[data-index-panel] tbody tr", settle=1000)
    c.page.locator("[data-index-panel] tbody tr").nth(1).locator("[data-index-reload]").click()
    c.page.wait_for_timeout(2500)
    elem_shot(c, "11-index-reload.jpg", "[data-index-panel]", pad=0)


@shot("12-migrated.jpg")
def migrated(c: Ctx):
    """Upgrading to 2.2: a stored version 1 dataset (tutorial 15's World economics in its 2.1 form) converted by the server's migration (what runs once at
    start, here asked for by a bi-admin as drishti.py bi migrate --models does), then the item's History: the migration is a saved state of its own."""
    import json
    import urllib.request

    import shotlib
    from _tut import ROOT
    import sys
    sys.path.insert(0, str(ROOT / "drishti-console"))
    from core.auth import mint_token
    login(c, height=1000)
    quiet_inbox(c)
    title = "World economics (2.1)"
    clean_items(c, title, "world-economics-21-economies")      # a run before left the model and the dataset it made
    made = bi(c, "POST", "/workspaces/me/items", {"kind": "dataset", "title": title,
                                                  "body": (ROOT / "docs" / "tutorials" / "data" / "world-economics-21.dataset.yaml").read_text()})
    token = mint_token(ADMIN, ["admin"], SERVER_ENV["DRISHTI_TOKEN_SECRET"], 600)
    req = urllib.request.Request(f"http://127.0.0.1:{shotlib.SERVER_PORT}/api/v1/bi/migrate/models", data=json.dumps({"dryRun": False}).encode(), method="POST",
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    print("  migrate:", urllib.request.urlopen(req, timeout=120).read().decode()[:300])
    go(c, "/bi/i/" + made["id"], width=1600, height=1000, wait="[data-sec-history]:not([hidden]) tbody tr", settle=1200)
    c.page.locator("[data-sec-history]").scroll_into_view_if_needed()
    c.page.wait_for_timeout(500)
    elem_shot(c, "12-migrated.jpg", "[data-sec-history]", pad=8)
