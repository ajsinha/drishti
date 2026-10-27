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

"""The product owner's scenario, all in the browser against a real scratch server: ONE large file of commodity deals of five product types
(the synthetic book of tools/packgen/comswap_synth.py: 5,000 deals, a JSON array, deal -> LEGS -> CASH_FLOWS -> RESETS, EVENTS sometimes
absent, one rare type) goes through Build -> New pack (every row counted, the type column found, one Sutra per type, the key, readable
labels, a snapshot date, anonymised samples), is deployed in Admin -> Packs, and loaded whole into the pack's File connector there. Then a
swap opens with its legs side by side and its cash flows drilling into resets three levels deep (breadcrumb, address, Esc), its events (or
"No events"), its counterparty's page and back, type-ahead by counterparty, the BI model over every level, and a phone that never scrolls
sideways. Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import io
import json
import pathlib
import re
import sys
import tarfile

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from wb_live import BROWSER_WAIT_MS, TOKEN_SECRET, _stack  # noqa: E402

sys.path.insert(0, str(CONSOLE.parent / "tools" / "packgen"))
import comswap_synth  # noqa: E402

DEALS = 5000
PACK = "book"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET, "DRISHTI_PACKS": "finance"}, auth=True)


@pytest.fixture(scope="module")
def book(tmp_path_factory):
    """The large file, as an export writes it (a pretty-printed JSON array), and the deals in it."""
    d = tmp_path_factory.mktemp("comswap") / "deals.json"
    comswap_synth.write(d, DEALS, 7, "mixed", jsonl=False, indent=2, mix=True)          # --array: the export's own shape
    return d, json.loads(d.read_text())


def sign_in(page, base):
    page.goto(base + "/login")
    page.fill("input[name=user]", "drishti-dev-admin")
    page.fill("input[name=password]", "drishti-dev-admin123")
    page.click("form button[type=submit], form input[type=submit]")
    page.wait_for_url(lambda u: "/login" not in u)


def pick(deals):
    """A swap with a fixed and a floating leg and events, a swap without events, and a deal of each other type."""
    swaps = [d for d in deals if d["PRODUCT_TYPE"] == "ComSwap" and len(d["LEGS"]) == 2 and d["LEGS"][1]["LEG_TYPE"] == "Float"]
    with_events = next(d for d in swaps if d.get("EVENTS"))
    without = next(d for d in swaps if "EVENTS" not in d)
    return with_events, without


def test_one_large_book_from_file_to_deep_deal_pages_all_in_the_browser(browser, stack, book, tmp_path):
    base = str(stack)
    path, deals = book
    types = sorted({d["PRODUCT_TYPE"] for d in deals})
    assert types == ["CallNot", "Cash", "ComFut", "ComSwap", "Commodity"]
    ctx = browser.new_context(accept_downloads=True, viewport={"width": 1440, "height": 1000})
    page = ctx.new_page()
    page.set_default_timeout(BROWSER_WAIT_MS)
    page.on("dialog", lambda d: d.accept())
    errors = []
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" and "favicon" not in m.text else None)
    sign_in(page, base)

    # ---- Build -> New pack: the file is read in the browser, every row counted, each type profiled ----------------------------
    page.goto(base + "/build/pack/new")
    page.evaluate("sessionStorage.clear()")
    page.goto(base + "/build/pack/new")
    assert page.locator("[data-anonymise]").is_checked()
    assert "Every row is counted" in page.locator("[data-reading-note]").inner_text()
    page.set_input_files("[data-files]", str(path))
    page.locator("[data-total]:has-text('1 data file')").wait_for()
    page.wait_for_function("() => document.querySelector('[data-cancel-read]').hidden")
    sources = page.locator("[data-sources]").inner_text()
    assert "5,000 rows (every row counted)" in sources and "type column PRODUCT_TYPE: 5 types" in sources, sources

    page.click("[data-next]")
    card = page.locator(".pk-card").first
    card.wait_for()
    page.wait_for_function("() => !document.querySelector('[data-msg]').innerText.includes('Planning')")
    text = card.inner_text()
    assert "Chosen because it is named like the id of a trade" in text                      # TRADE_ID, an integer, UPPER_SNAKE
    assert page.locator("[data-focus^='key-']").first.input_value() == "TRADE_ID"
    summary = card.locator("[data-types-summary]").inner_text()
    assert summary.startswith("5 types → 5 Sutras"), summary                                # one per type, no catch-all: no row lacks a type
    rows = {r.split("\t")[0]: r for r in card.locator(".pk-types tbody tr").all_inner_texts()}
    assert set(rows) == set(types) and "callnot" in rows["CallNot"]                            # the rare type too, with its own Sutra
    assert page.locator("[data-focus^='date-']").first.input_value() == "@snapshot"           # TRADE_DATE differs per deal: a snapshot
    assert "snapshot" in text.lower()
    card.locator("details.pk-fields summary").click()
    labels = card.locator("details.pk-fields tbody").inner_text()
    assert "Trade ID" in labels and "Leg economic type" in labels and "Buy sell code" in labels      # readable, not UPPER_SNAKE
    assert "blank everywhere: not shown" in labels                                             # "" in every row is no data
    related = card.locator("[data-related]").inner_text()
    assert "Counterparty" in related and "Portfolio" in related and "Index" in related

    page.click("[data-next]")
    page.locator("#pkp-name").wait_for()
    page.fill("#pkp-name", PACK)
    page.locator("#pkp-name").dispatch_event("change")
    page.wait_for_function("() => !document.querySelector('[data-msg]').innerText.includes('Planning')")
    page.click("[data-next]")
    page.locator(".pk-item").first.wait_for(timeout=4 * BROWSER_WAIT_MS)
    page.wait_for_function("() => !document.querySelector('[data-next]').disabled", timeout=4 * BROWSER_WAIT_MS)
    page.click("[data-next]")
    page.locator("[data-download]").wait_for(timeout=4 * BROWSER_WAIT_MS)
    assert "Load data" in page.locator("[data-load-hint]").inner_text()
    with page.expect_download() as dl:
        page.click("[data-download]")
    bundle = tmp_path / dl.value.suggested_filename
    dl.value.save_as(bundle)
    real = {d["COUNTERPARTY"] for d in deals} | {d["PORTFOLIO"] for d in deals} | {d["TRANSACTION_ID"] for d in deals}
    with tarfile.open(bundle) as t:
        names = t.getnames()
        assert sum(1 for n in names if re.search(r"/sutras/deals/(comswap|comfut|cash|callnot|commodity)\.v1\.sutra\.yaml$", n)) == 5
        assert not any(n.endswith("deals-default.v1.sutra.yaml") for n in names)
        assert any(n.endswith("config/bi/models/book-deals.model.yaml") for n in names)
        kept = "".join(t.extractfile(m).read().decode("utf-8") for m in t.getmembers() if m.isfile() and ("/tests/" in m.name or "/samples/" in m.name))
    assert not [v for v in real if v in kept]                                                  # anonymised: no real value kept

    # ---- Admin -> Packs: checked, deployed --------------------------------------------------------------------------------------
    page.click("[data-deploy-link]")
    page.wait_for_url(lambda u: "/admin/packs" in u)
    page.locator("[data-result]:not([hidden])").wait_for(timeout=4 * BROWSER_WAIT_MS)
    result = page.locator("[data-result]").inner_text()
    assert "FAIL" not in result and "sutra test" in result, result
    page.click("[data-deploy-go]")
    page.click("[data-ask-yes]")
    page.wait_for_function(f"() => document.querySelector('[data-history]') && document.querySelector('[data-history]').innerText.includes('{PACK}')",
                           timeout=4 * BROWSER_WAIT_MS)

    # ---- Load data: the whole file into the File connector, every row checked against the Sutras --------------------------------
    page.goto(f"{base}/admin/packs/{PACK}#load-data")
    page.locator("[data-data-form]:not([hidden])").wait_for()
    assert "book-store" in page.locator("[data-data-targets]").inner_text()
    page.set_input_files("[data-data-file]", str(path))
    page.click("[data-data-load]")
    page.locator("[data-data-result]:not([hidden])").wait_for(timeout=4 * BROWSER_WAIT_MS)
    loaded = page.locator("[data-data-summary]").inner_text()
    assert f"{DEALS:,} deals rows loaded into book-store" in loaded, loaded
    assert "no Sutra of their own" not in page.locator("[data-data-result]").inner_text()      # every type has its Sutra

    # ---- a swap: legs side by side, each leg's cash flows drilling into resets (three levels), events ---------------------------
    swap, quiet = pick(deals)
    tid = swap["TRADE_ID"]
    page.goto(f"{base}/v/deals/{tid}")
    page.locator("#p-legs").wait_for()
    assert page.locator("#p-legs .cols .col-box").count() == 2                               # side by side
    heads = page.locator("#p-legs .col-box > h4").all_inner_texts()
    assert heads[0].startswith("Leg 1 · Fixed") and heads[1].startswith("Leg 2 · Float"), heads
    flows_panel = page.locator("#p-legs-cash-flows")
    flows_panel.locator(".tab-head").nth(1).click()
    drill = flows_panel.locator(".tab-pane:not([hidden]) [data-drill]")
    crumbs = drill.locator("[data-dr-crumbs]")
    assert crumbs.inner_text().replace("\n", " ").startswith(f"Deals {tid}")
    leg2 = swap["LEGS"][1]
    first = drill.locator(".dr-lv.on table caption").inner_text()
    assert first.startswith("CASH FLOWS") and str(len(leg2["CASH_FLOWS"])) in first             # the count of the level
    row = 1
    drill.locator(".dr-lv.on a[data-dr-open]").nth(row).click()
    trail = crumbs.inner_text().split("\n")
    flow = leg2["CASH_FLOWS"][row]
    assert trail == [f"Deals {tid} › Leg 2 · Float", f"Cash flow {flow['FLOW_ID']}", "Resets"], trail
    assert "dr.legs-cash-flows-rows~2=1" in page.url.replace("%7E", "~")
    level = drill.locator(".dr-lv.on")
    assert level.locator(".dr-parent").inner_text().count(flow["PAYMENT_DATE"]) >= 1          # the parent row stays on screen
    assert level.locator("tbody tr").count() == len(flow["RESETS"])
    shared = page.url
    page.keyboard.press("Escape")                                                            # up one level
    assert crumbs.inner_text().split("\n") == [f"Deals {tid} › Leg 2 · Float"]
    page.goto(shared)                                                                        # the address opens the same level, in the right tab
    page.locator("#p-legs-cash-flows .tab-pane:not([hidden]) [data-drill] .dr-lv.on .dr-parent").wait_for()
    assert "Resets" in page.locator("#p-legs-cash-flows .tab-pane:not([hidden]) [data-dr-crumbs]").inner_text()
    events = page.locator("#p-events")
    assert events.locator("tbody tr").count() == min(len(swap["EVENTS"]), 25)
    page.goto(f"{base}/v/deals/{quiet['TRADE_ID']}")
    assert "No events" in page.locator("#p-events").inner_text()                              # the key is missing: no rows, said in words

    # ---- the counterparty's page, and back ----------------------------------------------------------------------------------------
    page.goto(f"{base}/v/deals/{tid}")
    page.locator(".with-cp a").first.click()
    page.wait_for_url(lambda u: "/v/deals-counterparty/" in u)
    assert page.locator("#p-summary").count() == 1, page.locator("body").inner_text()[:600]
    members = page.locator("#p-members")
    count = sum(1 for d in deals if d["COUNTERPARTY"] == swap["COUNTERPARTY"])
    assert f"{count:,}" in page.locator("#p-summary").inner_text()
    members.locator(f"a:has-text('{tid}')").first.click()
    page.wait_for_url(lambda u: f"/v/deals/{tid}" in u)

    # ---- type-ahead finds a deal by its counterparty ----------------------------------------------------------------------------
    hits = page.request.get(base + "/api/suggest", params={"q": swap["COUNTERPARTY"]}).json()
    assert any(h.get("kind") == "deals" and swap["COUNTERPARTY"] in (h.get("title") or "") for h in hits), hits[:3]

    # ---- BI over every level of the full data ----------------------------------------------------------------------------------
    mq = {"mq": 1, "model": "book-deals@1", "select": {"fields": ["deals.PRODUCT_TYPE"], "measures": ["Deals documents", "Resets"]}}
    ans = page.request.post(base + "/bi/api/mq", data=json.dumps(mq), headers={"Content-Type": "application/json"}).json()
    by_type = {r[0]: r for r in ans["rows"]}
    assert sum(r[1] for r in ans["rows"]) == DEALS and set(by_type) == set(types)
    resets = sum(len(c["RESETS"]) for d in deals if d["PRODUCT_TYPE"] == "ComSwap" for leg in d["LEGS"] for c in leg["CASH_FLOWS"])
    assert by_type["ComSwap"][2] == resets
    assert not errors, errors
    ctx.close()

    # ---- a phone: no sideways scroll ------------------------------------------------------------------------------------------
    phone = browser.new_context(viewport={"width": 390, "height": 844})
    pp = phone.new_page()
    pp.set_default_timeout(BROWSER_WAIT_MS)
    sign_in(pp, base)
    for url in (f"/v/deals/{tid}", f"/v/deals-counterparty/{swap['COUNTERPARTY']}", f"/admin/packs/{PACK}"):
        pp.goto(base + url)
        pp.wait_for_timeout(500)
        assert pp.evaluate("document.documentElement.scrollWidth") <= 390, url
    phone.close()
