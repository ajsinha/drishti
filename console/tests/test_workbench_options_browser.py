"""UX-03: every option of every panel kind in the Rachana schema has an editor in the inspector that works: numbers take numbers,
fixed sets are selects, and markers, the table's pivot object and the pivot's `by` list are structured. Fixtures: test_workbench_browser.py's."""
import yaml

from test_workbench_browser import browser, open_design, page, settle, state, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console, showcase_json, showcase_sutra  # noqa: F401 - the fixture

NAMES = r"""() => {
  const box = document.querySelector('[data-inspector]'), out = new Set(), unnamed = [];
  box.querySelectorAll('label, legend, .wb-cap').forEach(l => out.add(l.textContent.replace(/\s*\*$/, '').trim()));
  box.querySelectorAll('[role=group][aria-label]').forEach(g => out.add(g.getAttribute('aria-label')));
  box.querySelectorAll('input, select, textarea').forEach(c => {
    const named = c.getAttribute('aria-label') || c.title || (c.id && box.querySelector('label[for="' + c.id + '"]'));
    if (!named) unnamed.push(c.outerHTML.slice(0, 80));
  });
  return {names: [...out], unnamed, yamlOnly: /Set in the YAML tab/.test(box.textContent.replace(/Also set in the YAML tab[^.]*\./, ''))};
}"""


def kinds_of(page, base):
    schema = page.request.get(base + "/studio/schema").json()
    return {a["if"]["properties"]["kind"]["const"]: a["then"] for a in schema["$defs"]["panel"]["allOf"]}


def pick(page, panel):
    page.locator(f'[data-preview] [data-panel="{panel}"] .pnl-h').click()
    page.locator("[data-inspector] .bs-role").first.wait_for()


def option_value(page, panel, option):
    return page.evaluate("([p, o]) => window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).panels.filter(x => x.id === p)[0].values[o]", [panel, option])


def test_every_option_of_every_kind_in_the_schema_has_a_labelled_editor(live_console, page):
    open_design(page, live_console, "every option", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    kinds = kinds_of(page, live_console)
    ids = {p["kind"]: p["id"] for p in yaml.safe_load(showcase_sutra())["panels"]}
    assert set(ids) == set(kinds) and len(kinds) == 20
    missing = []
    for kind, then in kinds.items():
        pick(page, ids[kind])
        wait(page, f"document.querySelector('[data-inspector] .bs-role').textContent === '{kind}'")
        got = page.evaluate(NAMES)
        assert not got["yamlOnly"], f"{kind}: an option is left to the YAML tab"
        assert not got["unnamed"], f"{kind}: controls without a name {got['unnamed']}"
        missing += [f"{kind}.{o}" for o in then["x-rachana-options"] if o not in ("id", "kind") and o not in got["names"]]
    assert not missing, missing


def test_numeric_options_take_numbers_and_enumerated_options_are_selects(live_console, page):
    open_design(page, live_console, "numbers", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    pick(page, "versions")
    rev = state(page, "rev")
    limit = page.get_by_label("limit", exact=True)
    assert limit.get_attribute("type") == "number"
    limit.fill("5")
    limit.blur()
    settle(page, rev)
    assert option_value(page, "versions", "limit") == 5                   # a number, not the text "5" (DRS-5023)
    pick(page, "tree")
    rev = state(page, "rev")
    page.get_by_label("expand", exact=True).fill("2")
    page.get_by_label("expand", exact=True).blur()
    settle(page, rev)
    assert option_value(page, "tree", "expand") == 2
    rev = state(page, "rev")
    page.get_by_label("all", exact=True).check()
    settle(page, rev)
    assert option_value(page, "tree", "expand") == "all"
    pick(page, "group")
    layout = page.get_by_label("layout", exact=True)
    assert layout.evaluate("e => e.tagName") == "SELECT" and sorted(layout.locator("option").all_inner_texts()) == ["(none)", "force", "tree"]
    rev = state(page, "rev")
    layout.select_option("force")
    settle(page, rev)
    assert option_value(page, "group", "layout") == "force"
    pick(page, "sets")
    assert page.get_by_label("layout", exact=True).evaluate("e => e.tagName") == "SELECT"


def test_markers_the_pivot_object_and_the_pivot_by_list_have_structured_editors(live_console, page):
    open_design(page, live_console, "structured", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    pick(page, "scenarios")
    rev = state(page, "rev")
    page.get_by_role("button", name="Add marker").click()
    page.get_by_label("Label 2", exact=True).fill("ES 97.5")
    page.get_by_label("Value 2", exact=True).fill("-$.var975")
    page.get_by_label("Value 2", exact=True).blur()
    settle(page, rev)
    assert [m.get("label") for m in option_value(page, "scenarios", "markers")] == ["VaR 99%", "ES 97.5"]
    pick(page, "grid")
    rev = state(page, "rev")
    page.get_by_role("button", name="Add by").click()
    page.get_by_role("textbox", name="by 3").fill("currency")
    page.get_by_role("textbox", name="by 3").blur()
    settle(page, rev)
    assert option_value(page, "grid", "by") == ["book", "family", "currency"]
    pick(page, "versions")
    rev = state(page, "rev")
    page.get_by_role("switch", name="pivot on").check()
    settle(page, rev)
    assert option_value(page, "versions", "pivot") is True
    rev = state(page, "rev")
    page.get_by_label("pivot.heat", exact=True).check()
    settle(page, rev)
    assert option_value(page, "versions", "pivot") == {"heat": True}
    pick(page, "sets")
    assert page.get_by_label("kind of the tab body", exact=True).input_value() == "kv"
    assert page.get_by_role("group", name="columns 1").count() == 1
    assert not page.errors
