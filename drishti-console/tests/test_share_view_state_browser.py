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

"""A sent share keeps the sender's view state (RUPAKA.md 8.1, COLLABORATION.md) in a real browser, against a real server with sign-in on:
the sender filters a table of the IRS view by a column, presses Share, and the recipient who opens the share from the inbox lands on the same
view with the same filter on (the funnel is lit and the same rows are left). A share sent from a view with no filters opens unfiltered.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py). Every wait is wb_live.BROWSER_WAIT_MS."""
import json
import urllib.parse

import pytest

from test_collab_polish_browser import PASSWORD, TRADE, api, share_to, sign_in  # noqa: F401 - helpers
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import TOKEN_SECRET, _stack

DISCOVER = """() => {
  for (const t of document.querySelectorAll('.pnl table')) {
    const panel = t.closest('.pnl'), id = panel && panel.id.replace(/^p-/, ''), heads = [...t.tHead.rows[0].cells];
    const rows = [...t.tBodies[0].rows].filter(r => !r.hidden);
    if (!id || rows.length < 3 || !t.querySelector('.cf-btn')) { continue; }
    for (let c = 0; c < heads.length; c++) {
      const name = (heads[c].dataset.cfName || heads[c].textContent).replace(/\\s+/g, ' ').trim();
      const texts = rows.map(r => r.cells[c] && r.cells[c].textContent.replace(/\\s+/g, ' ').trim());
      const distinct = [...new Set(texts)].filter(x => x && !x.includes('•'));
      if (name && distinct.length >= 2 && distinct.length < texts.length) {
        const keep = distinct[0];
        return { panel: id, column: name, value: keep, total: rows.length, left: texts.filter(x => x === keep).length };
      }
    }
  }
  return null;
}"""


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET}, auth=True)


@pytest.fixture(scope="module")
def people(browser, stack):
    base = str(stack)
    admin = sign_in(browser, base, "drishti-dev-admin", "drishti-dev-admin123")
    for name, display, roles in (("ann", "Ann Author", ["risk"]), ("ravi", "Ravi Kumar", ["risk"])):
        status, out = api(admin, base, "/admin/api/users", {"username": name, "displayName": display, "roles": roles, "packs": ["finance"], "enabled": True,
                                                            "password": PASSWORD, "mustChangePassword": False})
        assert status == 201, out
    yield admin
    admin.context.close()


def shown(pg):
    return pg.evaluate("(p) => [...document.querySelectorAll('#p-' + p + ' table tbody tr')].filter(r => !r.hidden).length", pg.cf_panel)


def test_the_recipient_opens_the_view_with_the_senders_column_filter(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    ann.goto(f"{base}/v/trade/{TRADE}")
    ann.locator(".vtitle .vid").wait_for()
    ann.wait_for_selector(".pnl table .cf-btn", state="attached")
    found = ann.evaluate(DISCOVER)
    assert found, "the IRS view has a table with a column that has repeated values"
    ann.cf_panel = found["panel"]
    spec = "in:" + urllib.parse.quote(found["value"], safe="")
    key = f"f.{found['panel']}.{found['column']}"
    ann.goto(f"{base}/v/trade/{TRADE}?{urllib.parse.quote(key, safe='.')}={urllib.parse.quote(spec, safe='')}")
    ann.wait_for_selector(f"#p-{found['panel']} .cf-btn.cf-on", state="attached")
    assert shown(ann) == found["left"] < found["total"]
    share_to(ann, "rav", "These rows only, please.")

    ravi = sign_in(browser, base, "ravi", PASSWORD)
    ravi.cf_panel = found["panel"]
    ravi.goto(base + "/inbox")
    ravi.locator(".inbox-row.unread a.inbox-open").first.click()
    ravi.locator("[data-share-banner]").wait_for()
    ravi.wait_for_selector(f"#p-{found['panel']} .cf-btn.cf-on", state="attached")
    assert shown(ravi) == found["left"]                                   # the same rows are left
    assert key in urllib.parse.unquote_plus(ravi.url)                          # and the address says why
    ann.context.close()
    ravi.context.close()


def test_a_share_from_an_unfiltered_view_opens_unfiltered(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    ann.goto(f"{base}/v/trade/{TRADE}")
    ann.locator(".vtitle .vid").wait_for()
    share_to(ann, "rav", "Everything, as it is.")
    ravi = sign_in(browser, base, "ravi", PASSWORD)
    ravi.goto(base + "/inbox")
    ravi.locator(".inbox-row.unread a.inbox-open").first.click()
    ravi.locator("[data-share-banner]").wait_for()
    ravi.wait_for_selector(".pnl table .cf-btn", state="attached")
    assert ravi.locator(".cf-btn.cf-on").count() == 0
    assert "f." not in urllib.parse.unquote_plus(urllib.parse.urlparse(ravi.url).query)
    ann.context.close()
    ravi.context.close()
