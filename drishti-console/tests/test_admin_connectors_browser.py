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

"""Admin → Connectors in a real browser: a connector is created from a form generated from the plugin's settings (a literal credential is refused, an
environment reference accepted), tested, saved, edited through the YAML tab and back, switched off, and deleted; a connector packs use asks before it
is switched off; two editors cannot overwrite each other. Keyboard and phone width are covered. Runs Chromium through Playwright against a console with a
stand-in server that keeps the files in memory; skipped when Playwright or its Chromium is missing."""
import re
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.backend import BackendError  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _spec(name, type="string", required=False, default=None, description="", secret=False, group="connection"):
    return {"name": name, "type": type, "required": required, "default": default, "description": description, "secret": secret, "group": group}


PLUGINS = [
    {"name": "file", "tls": False, "declared": True, "settings": [_spec("root", "path", True, None, "Folder of documents"), _spec("lookback-days", "int", False, None, "Days of history", group="tuning")]},
    {"name": "jdbc", "tls": True, "declared": True, "settings": [
        _spec("url", "string", True, None, "JDBC URL"), _spec("user", description="Database user"), _spec("password", secret=True, description="Database password"),
        _spec("pool-size", "int", False, "4", "Connections", group="tuning"),
        _spec("tls.enabled", "boolean", False, "false", "Use TLS", group="tls"), _spec("tls.truststore.path", "path", False, None, "Truststore", group="tls"),
        _spec("tls.truststore.password", secret=True, description="Truststore password", group="tls")]},
]


def _flat(text):
    """The few YAML shapes the stand-in understands: top-level scalars, kinds: [a, b], and a settings: mapping one level deep."""
    out = {"plugin": None, "enabled": True, "kinds": [], "description": "", "settings": {}}
    section = None
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.startswith("settings:"):
            section = "settings"
            continue
        m = re.match(r"^(\s*)([\w.\-]+):\s*(.*)$", line)
        if not m:
            raise ValueError("cannot read: " + line)
        indent, key, val = m.groups()
        if indent and section == "settings":
            out["settings"][key] = val.strip("'\"")
        else:
            section = None
            if key == "kinds":
                out["kinds"] = [x.strip() for x in val.strip("[]").split(",") if x.strip()]
            elif key == "enabled":
                out["enabled"] = val.strip().lower() != "false"
            else:
                out[key] = val
    if not out["plugin"]:
        raise ValueError("'plugin' is required")
    return out


def _text(d):
    lines = [f"plugin: {d['plugin']}"]
    if d.get("enabled") is False:
        lines.append("enabled: false")
    if d.get("description"):
        lines.append(f"description: {d['description']}")
    if d.get("kinds"):
        lines.append("kinds: [" + ", ".join(d["kinds"]) + "]")
    if d.get("settings"):
        lines.append("settings:")
        lines += [f"  {k}: {v}" for k, v in d["settings"].items()]
    return "\n".join(lines) + "\n"


class ConnectorBackend(FakeBackend):
    """A stand-in server for Admin → Connectors that keeps files in memory, versions them, and enforces If-Match and the confirmation."""

    def __init__(self):
        super().__init__()
        self.files = {"item-store": {"text": "plugin: file\nkinds: [item]\nsettings:\n  root: /data/items\n", "v": 1, "history": [], "template": "lakepack"}}
        self.used = {"item-store": [{"pack": "lakepack", "kinds": ["item"]}]}
        self.calls = []
        self.tests = []

    def row(self, name):
        f = self.files[name]
        d = _flat(f["text"])
        return {"name": name, "origin": "file", "plugin": d["plugin"], "kinds": d["kinds"], "description": d["description"], "enabled": d["enabled"],
                "state": "RUNNING" if d["enabled"] else "DISABLED", "health": "UP" if d["enabled"] else None, "updated": "2026-10-06T09:00:00Z", "etag": f"v{f['v']}",
                "problems": [], "usedBy": self.used.get(name, []), "template": f.get("template")}

    def detail(self, name):
        f = self.files[name]
        d = _flat(f["text"])
        return {**self.row(name), "settings": d["settings"], "text": f["text"], "file": f"/srv/config/connectors/{name}.yaml",
                "history": [{"id": h[0], "at": "2026-10-06T08:00:00Z", "bytes": len(h[1])} for h in reversed(f["history"])]}

    def draft_text(self, body):
        if "text" in body:
            _flat(body["text"])
            return body["text"]
        return _text(body)

    async def admin(self, method, path, ident, body=None, timeout=None, headers=None, **params):
        self.calls.append((method, path, body, headers, params))
        if path == "/connectors" and method == "GET":
            return {"connectors": [self.row(n) for n in sorted(self.files)], "directory": "/srv/config/connectors", "watch": "WATCHING", "misnamed": [],
                    "fileProblems": {}, "deprecated": [], "knownKinds": ["item", "thing"]}
        if path == "/connectors/plugins":
            return {"plugins": PLUGINS}
        m = re.match(r"^/connectors/([^/]+)(?:/(\w+)(?:/(.+))?)?$", path)
        if not m:
            return await super().admin(method, path, ident, body, **params)
        name, action, extra = m.groups()
        if action == "suggestion":
            raise BackendError(404, "DRS-5033", "no loaded pack suggests a connector")
        if action == "parse":
            try:
                d = _flat(body["text"])
            except ValueError as e:
                return {"problems": [{"level": "error", "field": "yaml", "message": str(e)}]}
            return {**d, "problems": []}
        if action == "render":
            return {"text": self.draft_text(body)}
        if action == "validate":
            return {"ok": True, "problems": []}
        if action == "test":
            text = self.draft_text(body) if body else self.files[name]["text"]
            d = _flat(text)
            self.tests.append(d)
            if "unreachable" in d["settings"].get("url", ""):
                return {"connector": name, "ok": False, "health": "DOWN", "ms": 3, "error": "PKIX path building failed", "kinds": [], "warnings": [],
                        "hint": "The server's certificate is not trusted. Put the certificate authority that signed it into the truststore."}
            return {"connector": name, "ok": True, "health": "UP", "ms": 4, "error": None, "hint": None, "warnings": [],
                    "kinds": [{"kind": k, "exact": True, "note": "", "dates": [{"date": "2026-10-05", "rows": 7}]} for k in d["kinds"]]}
        if action == "history" and extra:
            return {"name": name, "id": extra, "text": dict(self.files[name]["history"])[extra]}
        if method == "GET" and not action:
            if name not in self.files:
                raise BackendError(404, "DRS-5033", f"no connector named '{name}'")
            return self.detail(name)
        etag = (headers or {}).get("If-Match")
        if method == "PUT":
            text = self.draft_text(body)
            d = _flat(text)
            if re.search(r"password: (?!\$\{)", text):
                raise BackendError(422, "DRS-5031", "the connector is not valid: settings.password: a credential is never written into a connector file")
            f = self.files.get(name)
            if f and etag != f"v{f['v']}":
                raise BackendError(409, "DRS-5032", f"connector '{name}' changed since you read it (now v{f['v']}, you have {etag}); reload it and apply your edit again")
            if f:
                f["history"].append((f"2026100{f['v']}T000000000", f["text"]))
                f["text"], f["v"] = text, f["v"] + 1
            else:
                self.files[name] = {"text": text, "v": 1, "history": []}
            return self.detail(name) | {"changed": list(d["settings"]), "warnings": []}
        if action == "enabled":
            f = self.files[name]
            if not body["enabled"] and self.used.get(name) and params.get("confirm") != "true":
                raise BackendError(409, "DRS-5032", "disabling connector would stop serving pack lakepack (item); repeat with confirm=true to go ahead")
            d = _flat(f["text"])
            d["enabled"] = body["enabled"]
            f["history"].append((f"2026100{f['v']}T000000000", f["text"]))
            f["text"], f["v"] = _text(d), f["v"] + 1
            return self.detail(name)
        if method == "DELETE":
            if self.files[name].get("template"):
                raise BackendError(409, "DRS-5032", f"connector '{name}' is named by a pack's template")
            del self.files[name]
            return {"name": name, "deleted": True}
        return {}


@pytest.fixture(scope="module")
def conn_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = ConnectorBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}", app.state.backend
    server.should_exit = True
    thread.join(10)


def test_create_test_save_edit_disable_delete(browser, conn_console):
    url, backend = conn_console
    page = browser.new_page()
    page.on("dialog", lambda d: d.accept())
    page.goto(url + "/admin/connectors")
    assert "item-store" in page.locator("[data-list]").inner_text()

    # --- create: the form is generated from the plugin's settings
    page.click("[data-new]")
    dlg = page.locator("[data-edit-dialog]")
    dlg.locator("[data-f-name]").wait_for()
    assert dlg.locator("[data-f-plugin] option").all_inner_texts() == ["file", "jdbc"]
    assert dlg.locator("[data-setting='root']").count() == 1                                      # the default plugin's own fields
    dlg.locator("[data-f-plugin]").select_option("jdbc")
    for s in ("url", "user", "password", "pool-size", "tls.enabled", "tls.truststore.path", "tls.truststore.password"):
        assert dlg.locator(f"[data-setting='{s}']").count() == 1, s
    assert dlg.locator("[data-group='tls'] legend").inner_text() == "TLS"                         # a TLS section because the plugin supports TLS
    assert dlg.locator("[data-setting='root']").count() == 0                                      # and the file plugin's fields are gone
    assert dlg.locator("[data-setting='url']").get_attribute("aria-required") == "true"
    assert "(required)" in dlg.locator("label[for$='url']").inner_text()
    assert dlg.locator("[data-setting='pool-size']").get_attribute("placeholder") == "4"          # defaults show as placeholders
    assert dlg.locator("[data-setting='password']").get_attribute("placeholder") == "${ENV_VAR} or file:/run/secrets/name"
    dlg.locator("[data-f-name]").fill("orders-db")
    assert dlg.locator("[data-file-name]").inner_text() == "orders-db.yaml"
    dlg.locator("[data-kind-in]").fill("item")
    dlg.locator("[data-kind-add]").click()
    assert dlg.locator("[data-chips]").inner_text().startswith("item")
    dlg.locator("[data-setting='url']").fill("jdbc:postgresql://db.example.com:5432/orders")
    dlg.locator("[data-setting='user']").fill("drishti")

    # a literal credential is refused with a clear message, and nothing is sent
    sent = len(backend.calls)
    dlg.locator("[data-setting='password']").fill("hunter2")
    dlg.locator("[data-save]").click()
    err = dlg.locator("div.conn-field:has([data-setting='password']) [data-field-err]").inner_text()
    assert "never written into a connector file" in err and "${PASSWORD}" in err
    assert dlg.locator("[data-setting='password']").get_attribute("aria-invalid") == "true"
    assert not any(c[0] == "PUT" for c in backend.calls[sent:])
    dlg.locator("[data-setting='password']").fill("${ORDERS_DB_PASSWORD}")

    # test: the Test tab shows what the throwaway instance found
    dlg.locator("[data-test]").click()
    dlg.locator("[data-test-out] table").wait_for()
    out = dlg.locator("[data-test-out]").inner_text()
    assert "Reachable" in out and "2026-10-05" in out and "item" in out
    assert backend.tests[-1]["settings"]["url"].startswith("jdbc:postgresql://db.example")
    dlg.locator("[data-setting='url']").evaluate("n => n.value")                                   # (form tab is hidden now; the draft is read from it)
    dlg.locator("[data-tab='form']").click()
    dlg.locator("[data-setting='url']").fill("jdbc:postgresql://unreachable.example.com/orders")
    dlg.locator("[data-test]").click()
    dlg.locator("[data-test-out] .conn-hint-box").wait_for()
    assert "certificate is not trusted" in dlg.locator("[data-test-out]").inner_text()           # TLS errors in words
    dlg.locator("[data-tab='form']").click()
    dlg.locator("[data-setting='url']").fill("jdbc:postgresql://db.example.com:5432/orders")

    # save: the file appears, the page reloads with the connector in the list
    dlg.locator("[data-save]").click()
    page.wait_for_function("() => document.querySelector('tr[data-conn=\"orders-db\"]') !== null", timeout=15000)
    assert "orders-db" in backend.files and "${ORDERS_DB_PASSWORD}" in backend.files["orders-db"]["text"]
    assert "hunter2" not in backend.files["orders-db"]["text"]
    row = page.locator("tr[data-conn='orders-db']")
    assert "running" in row.inner_text() and "jdbc" in row.inner_text()

    # --- edit: the YAML tab and the form edit one draft
    row.get_by_role("button", name="Edit orders-db").click()
    dlg = page.locator("[data-edit-dialog]")
    dlg.locator("[data-f-description]").wait_for()
    assert dlg.locator("[data-f-name]").get_attribute("readonly") is not None                     # the name is the file name: it does not change
    assert dlg.locator("[data-f-plugin]").is_disabled()
    assert dlg.locator("[data-setting='user']").input_value() == "drishti"
    dlg.locator("[data-tab='yaml']").click()
    yaml = dlg.locator("[data-yaml]")
    assert "url: jdbc:postgresql://db.example.com:5432/orders" in yaml.input_value()
    yaml.fill(yaml.input_value().rstrip("\n") + "\n  pool-size: 12\n")
    dlg.locator("[data-tab='form']").click()
    dlg.locator("[data-setting='pool-size']").wait_for()
    page.wait_for_function("() => document.querySelector('[data-setting=\"pool-size\"]').value === '12'")   # the YAML edit reached the form
    dlg.locator("[data-f-description]").fill("Orders database")
    dlg.locator("[data-save]").click()
    page.wait_for_function("() => document.querySelector('tr[data-conn=\"orders-db\"] .text-muted-d') !== null && document.body.innerText.includes('Orders database')", timeout=15000)
    assert "pool-size: 12" in backend.files["orders-db"]["text"] and "description: Orders database" in backend.files["orders-db"]["text"]
    assert backend.files["orders-db"]["v"] == 2

    # history: the earlier text is kept; the diff shows the change
    page.locator("tr[data-conn='orders-db']").get_by_role("button", name="Edit orders-db").click()
    dlg = page.locator("[data-edit-dialog]")
    dlg.locator("[data-tab='history']").click()
    dlg.get_by_role("button", name=re.compile("Show what changed")).first.click()
    dlg.locator("[data-diff]:not([hidden])").wait_for()
    diff = dlg.locator("[data-diff]").inner_text()
    assert "--- 2026" in diff and "+++ current file" in diff and "+ description: Orders database" in diff and "+   pool-size: 12" in diff
    dlg.locator("[data-close]").click()

    # --- disable and enable from the list (nothing uses it: no question)
    page.locator("tr[data-conn='orders-db']").get_by_role("button", name="Disable orders-db").click()
    page.wait_for_function("() => document.querySelector('tr[data-conn=\"orders-db\"]').innerText.includes('disabled')", timeout=15000)
    assert _flat(backend.files["orders-db"]["text"])["enabled"] is False

    # --- delete, with a confirmation
    page.locator("tr[data-conn='orders-db']").get_by_role("button", name="Delete orders-db").click()
    conf = page.locator("[data-confirm-dialog]")
    conf.wait_for()
    assert "Delete orders-db?" in conf.inner_text()
    conf.locator("[data-confirm-no]").click()
    assert "orders-db" in backend.files                                                            # "keep it" keeps it
    page.locator("tr[data-conn='orders-db']").get_by_role("button", name="Delete orders-db").click()
    conf.locator("[data-confirm-yes]").click()
    page.wait_for_function("() => document.querySelector('tr[data-conn=\"orders-db\"]') === null", timeout=15000)
    assert "orders-db" not in backend.files
    page.close()


def test_a_connector_packs_use_asks_before_it_is_switched_off_and_is_never_deleted(browser, conn_console):
    url, backend = conn_console
    page = browser.new_page()
    page.goto(url + "/admin/connectors")
    row = page.locator("tr[data-conn='item-store']")
    assert row.get_by_role("button", name="Delete item-store").count() == 0                       # a pack's connector: reset or disable
    assert row.get_by_role("button", name=re.compile("Reset item-store to the default of pack lakepack")).count() == 1
    assert "lakepack" in row.inner_text()                                                          # who uses it, on the row
    row.get_by_role("button", name="Disable item-store").click()
    conf = page.locator("[data-confirm-dialog]")
    conf.wait_for()
    assert "pack lakepack (item)" in conf.inner_text()
    conf.locator("[data-confirm-no]").click()
    assert _flat(backend.files["item-store"]["text"])["enabled"] is True
    row.get_by_role("button", name="Disable item-store").click()
    conf.locator("[data-confirm-yes]").click()
    page.wait_for_function("() => document.querySelector('tr[data-conn=\"item-store\"]').innerText.includes('disabled')", timeout=15000)
    assert any(c[4].get("confirm") == "true" for c in backend.calls if c[1] == "/connectors/item-store/enabled")
    page.locator("tr[data-conn='item-store']").get_by_role("button", name="Enable item-store").click()
    page.wait_for_function("() => document.querySelector('tr[data-conn=\"item-store\"]').innerText.includes('running')", timeout=15000)
    page.close()


def test_a_stale_edit_is_a_conflict_and_offers_the_current_version(browser, conn_console):
    url, backend = conn_console
    page = browser.new_page()
    page.goto(url + "/admin/connectors")
    page.locator("tr[data-conn='item-store']").get_by_role("button", name="Edit item-store").click()
    dlg = page.locator("[data-edit-dialog]")
    dlg.locator("[data-f-description]").wait_for()
    dlg.locator("[data-f-description]").fill("my edit")
    backend.files["item-store"]["v"] += 1                                                          # somebody else saved in between
    dlg.locator("[data-save]").click()
    msg = dlg.locator("[data-edit-msg]")
    page.wait_for_function("() => document.querySelector('[data-edit-msg]').innerText.includes('changed since you read it')")
    assert msg.get_by_role("button", name="Load the current version").is_visible()
    page.close()


def test_the_editor_works_from_the_keyboard(browser, conn_console):
    url, backend = conn_console
    page = browser.new_page()
    page.goto(url + "/admin/connectors")
    page.keyboard.press("/")
    assert page.evaluate("() => document.activeElement.hasAttribute('data-filter')")
    page.locator("[data-filter]").fill("item")
    assert page.locator("tr[data-conn='item-store']").is_visible()
    page.locator("[data-filter]").fill("zzz")
    assert page.locator("tr[data-conn='item-store']").is_hidden() and page.locator("[data-filter-none]").is_visible()
    page.locator("[data-filter]").fill("")
    page.locator("h1").click()
    page.keyboard.press("n")                                                                       # n opens New connector
    dlg = page.locator("[data-edit-dialog]")
    dlg.locator("[data-f-name]").wait_for()
    assert dlg.get_attribute("aria-labelledby") == "connTitle"
    tab = dlg.locator("[data-tab='form']")
    tab.focus()
    page.keyboard.press("ArrowRight")                                                              # arrow keys move between the tabs
    page.wait_for_function("() => document.querySelector('[data-tab=\"yaml\"]').getAttribute('aria-selected') === 'true'")
    assert dlg.locator("[data-yaml]").is_visible() and dlg.locator("[data-pane='form']").is_hidden()
    page.keyboard.press("ArrowLeft")
    page.wait_for_function("() => document.querySelector('[data-tab=\"form\"]').getAttribute('aria-selected') === 'true'")
    page.keyboard.press("Escape")
    page.wait_for_function("() => !document.querySelector('[data-edit-dialog]').open")
    page.wait_for_function("() => document.activeElement && document.activeElement.hasAttribute('data-new')", timeout=3000)     # back on what opened it
    page.close()


def test_the_page_fits_a_phone_and_the_dark_theme_with_big_targets(browser, conn_console):
    url, backend = conn_console
    ctx = browser.new_context(viewport={"width": 390, "height": 800}, color_scheme="dark", has_touch=True)
    page = ctx.new_page()
    page.goto(url + "/admin/connectors")
    assert page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1")
    page.click("[data-new]")
    dlg = page.locator("[data-edit-dialog]")
    dlg.locator("[data-f-name]").wait_for()
    box = dlg.bounding_box()
    assert box["x"] >= 0 and box["x"] + box["width"] <= 391
    assert page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1")
    for sel in ("[data-save]", "[data-test]", "[data-tab='yaml']", "[data-close]", "[data-kind-add]", "[data-f-name]", "[data-f-plugin]"):
        b = dlg.locator(sel).first.bounding_box()
        assert b["height"] >= 44 - 1, (sel, b)                                                     # 44px touch targets
    colour = page.evaluate("() => getComputedStyle(document.querySelector('[data-edit-dialog]')).color")
    background = page.evaluate("() => getComputedStyle(document.querySelector('[data-edit-dialog]')).backgroundColor")
    assert colour != background
    ctx.close()
