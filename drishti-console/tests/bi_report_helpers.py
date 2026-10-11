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

"""Helpers the Rupaka phase 3 browser tests share (tests/test_bi_reports_browser.py, tests/test_bi_reports_cross_browser.py, the documentation pictures): signing in,
reading what a chart really drew (ECharts instances, not pixels), clicking a mark by its coordinates, and the policy-violation listener."""
from bi_report_stack import CONSOLE_URL, PASSWORD

VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"

CHART = """(vis) => {
  const el = document.querySelector('[data-visual="' + vis + '"] .bi-chart');
  if (!el) return null;
  const c = echarts.getInstanceByDom(el), o = c.getOption();
  const cat = (o.xAxis && o.xAxis[0] && o.xAxis[0].type === 'category') ? o.xAxis[0].data : (o.yAxis && o.yAxis[0] && o.yAxis[0].type === 'category') ? o.yAxis[0].data : null;
  return { categories: cat, series: o.series.map(s => ({ name: s.name, type: s.type, values: (s.data || []).map(d => d && typeof d === 'object' && 'value' in d ? d.value : d), opacity: (s.data || []).map(d => d && d.itemStyle ? d.itemStyle.opacity : null) })) };
}"""

MARK = """([vis, category]) => {
  const el = document.querySelector('[data-visual="' + vis + '"] .bi-chart');
  const c = echarts.getInstanceByDom(el), o = c.getOption();
  const horizontal = o.yAxis[0].type === 'category';
  const cats = horizontal ? o.yAxis[0].data : o.xAxis[0].data;
  const i = cats.indexOf(category);
  if (i < 0) return null;
  const lay = c.getModel().getSeriesByIndex(0).getData().getItemLayout(i);          // the first series' bar, in the chart's own pixels
  const r = el.getBoundingClientRect();
  return { x: r.left + lay.x + lay.width / 2, y: r.top + lay.y + lay.height / 2 };
}"""


def sign_in(browser, user, next_path="/bi", **ctx):
    context = browser.new_context(**{"viewport": {"width": 1600, "height": 900}, **ctx})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(CONSOLE_URL + "/login?next=" + next_path)
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**" + next_path.split("?")[0])
    page.errors = errors
    return page


def open_report(page, report_id, path=""):
    page.goto(f"{CONSOLE_URL}/bi/r/{report_id}{path}")
    page.wait_for_selector("[data-ready=yes]", timeout=30000)


def settled(page, timeout=30000):
    """Every visual has answered (no frame is busy) and has its line of rows and milliseconds, or its error."""
    page.wait_for_function("() => !document.querySelector('.bi-vis-busy') && document.querySelectorAll('.bi-vis').length > 0 && "
                           "[...document.querySelectorAll('.bi-vis:not(.bi-vis-text):not(.bi-vis-button):not(.bi-vis-image)')].every(v => v.querySelector('.bi-vis-ms') || v.classList.contains('bi-vis-failed'))",
                           timeout=timeout)


def chart(page, vis):
    return page.evaluate(CHART, vis)


def click_mark(page, vis, category):
    page.locator(f'[data-visual="{vis}"] .bi-chart').scroll_into_view_if_needed()
    p = page.evaluate(MARK, [vis, category])
    assert p, f"no mark {category} in {vis}"
    page.mouse.click(p["x"], p["y"])


def clean(page):
    assert page.evaluate("window.__csp") == [], "a policy violation"
    assert page.errors == [], page.errors


def card_value(page, vis):
    return int(float(page.locator(f'[data-visual="{vis}"] .bi-card-value').get_attribute("data-value")))
