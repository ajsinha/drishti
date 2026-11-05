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

"""The rules for writing browser tests (docs/architecture/TEST_HARNESS.md, "Rules for browser tests"), checked on the source.

1. No fixed pause over 100 ms (``wait_for_timeout``, ``time.sleep``, ``asyncio.sleep``) in a browser test or helper without its reason on
   the line: ``# wait-ok: <why>`` (a pause that is the behaviour under test, such as a debounce or a countdown). Wait on the page's
   settled signal (browser_harness.settled, Drishti.settled) or on the condition itself.
2. No server started per test: a fixture that starts a real server (``_stack``, a stack's ``.start()``, ``java_cmd``) is module- or
   session-scoped, and goes through wb_live's pool (``_stack``) or a ``*_stack`` module; nothing calls the pool's ``_start_stack``.
3. A test marked ``spread`` asks only for per-worker (session) fixtures and its own function fixtures: a module fixture would start
   again on every worker the spread tests land on.
4. A test marked ``static`` (a quick file check, which the drill's gate runs) starts nothing: it asks only for pytest's own fixtures
   (``tmp_path``, ``monkeypatch``, ...) and fixtures of its file that do the same, calls no server, console or browser
   (``TestClient``, ``create_app``, ``_stack``, Playwright, ``subprocess``) and its file imports no browser or stack
   helper. The gate runs before the server jar exists and beside the Java build.

The files written before these rules carry their count of old pauses and starts in ``BASELINE`` below: a file may only go down
(lower its number when you remove one); a new file starts at zero."""
from __future__ import annotations

import ast
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

TESTS = Path(__file__).resolve().parent
LIMIT_MS = 100
REASON = re.compile(r"#\s*wait-ok:\s*\S")

# file -> (pauses over 100 ms without a reason, per-test server starts) when the rules were written; a file may only go down
BASELINE: dict[str, tuple[int, int]] = {
    "bi_stack.py": (1, 0), "net_stack.py": (1, 0), "test_about_browser.py": (1, 0), "test_about_hints_browser.py": (1, 0),
    "test_admin_connectors_browser.py": (2, 0), "test_admin_net_stack_browser.py": (8, 0), "test_alerts_browser.py": (2, 0),
    "test_bi_browser.py": (5, 0), "test_bi_gov_browser.py": (1, 0), "test_bi_languages_browser.py": (2, 0),
    "test_bi_live_browser.py": (3, 0), "test_bi_reports_browser.py": (2, 0), "test_bi_runs_again_browser.py": (1, 0),
    "test_bi_wave_a_browser.py": (1, 0), "test_bi_wave_b_browser.py": (2, 0), "test_bi_wave_c_browser.py": (1, 0),
    "test_block_sutras_browser.py": (1, 0), "test_busy_browser.py": (1, 0), "test_colfilter_browser.py": (2, 0),
    "test_comswap_ui_end_to_end_browser.py": (1, 0), "test_controls_browser.py": (1, 0), "test_designer_browser.py": (3, 0),
    "test_edition_trading_browser.py": (1, 0), "test_embed_elements_browser.py": (15, 0), "test_embed_frameworks_browser.py": (1, 0),
    "test_embed_report_browser.py": (1, 0), "test_engines_menu_browser.py": (7, 0), "test_exposure_explorer_browser.py": (1, 0),
    "test_help_configuration_browser.py": (2, 0), "test_help_hub_browser.py": (1, 0), "test_history_browser.py": (1, 0),
    "test_lens_menus_browser.py": (3, 0), "test_live_tabs_browser.py": (3, 0), "test_live_toggle_browser.py": (1, 0),
    "test_maps_browser.py": (9, 0), "test_news_browser.py": (0, 4), "test_pack_new_browser.py": (2, 0),
    "test_page_widths_browser.py": (2, 0), "test_panel_polish_browser.py": (1, 0), "test_phone_browser.py": (3, 0),
    "test_play_browser.py": (1, 0), "test_refresh_browser.py": (4, 0), "test_render_behaviours_browser.py": (2, 0),
    "test_render_library_browser.py": (1, 0), "test_render_sign_in_browser.py": (3, 0), "test_screen_grid_browser.py": (1, 0),
    "test_server_watch_browser.py": (2, 0), "test_slider_browser.py": (1, 0), "test_smat_browser.py": (2, 0),
    "test_table_chart_browser.py": (1, 0), "test_topbar_menus_phone_browser.py": (5, 0), "test_topbar_widths_browser.py": (2, 0),
    "test_var_explorer_browser.py": (5, 0), "test_var_lens_browser.py": (2, 0), "test_var_regulatory_browser.py": (2, 0),
    "test_wave_a_qa_console_browser.py": (4, 0), "test_wave_b_browser.py": (1, 0), "test_wave_d_browser.py": (6, 0),
    "test_workbench_about_browser.py": (1, 0), "test_workbench_browser.py": (2, 0), "test_workbench_form_browser.py": (2, 0),
    "test_workbench_ux2_browser.py": (4, 0), "test_workbench_ux2b_browser.py": (3, 0), "test_workbench_ux2c_browser.py": (3, 0),
    "test_workspace_keys_browser.py": (3, 0), "test_zoom_browser.py": (1, 0)
}


def browser_files() -> list[Path]:
    """The browser tests and their helpers: *_browser.py, the stack and live-server modules, the harness."""
    names = set(TESTS.glob("*_browser.py")) | set(TESTS.glob("*_stack.py")) | {TESTS / n for n in ("wb_live.py", "browser_harness.py", "render_harness.py")}
    return sorted(p for p in names if p.exists())


def _ms(call: ast.Call, name: str) -> float | None:
    """The pause in milliseconds of a wait_for_timeout / sleep call with a constant argument, else None."""
    if not call.args or not isinstance(call.args[0], ast.Constant) or not isinstance(call.args[0].value, (int, float)):
        return None
    v = float(call.args[0].value)
    return v if name == "wait_for_timeout" else v * 1000.0


def pauses(path: Path) -> list[tuple[int, float]]:
    """(line, milliseconds) of every fixed pause over the limit without a reason on its line or the line above."""
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    out = []
    for node in ast.walk(ast.parse(text, str(path))):
        if not isinstance(node, ast.Call):
            continue
        f = node.func
        name = f.attr if isinstance(f, ast.Attribute) else f.id if isinstance(f, ast.Name) else ""
        if name not in ("wait_for_timeout", "sleep"):
            continue
        ms = _ms(node, name)
        if ms is None or ms <= LIMIT_MS:
            continue
        here = lines[node.lineno - 1]
        above = lines[node.lineno - 2] if node.lineno > 1 else ""
        if REASON.search(here) or REASON.search(above):
            continue
        out.append((node.lineno, ms))
    return out


def _starts_server(fn: ast.FunctionDef) -> bool:
    for n in ast.walk(fn):
        if isinstance(n, ast.Call):
            f = n.func
            name = f.attr if isinstance(f, ast.Attribute) else f.id if isinstance(f, ast.Name) else ""
            if name in ("_stack", "_start_stack", "java_cmd"):
                return True
            if name == "start" and isinstance(f, ast.Attribute) and isinstance(f.value, ast.Call):
                inner = f.value.func
                cls = inner.attr if isinstance(inner, ast.Attribute) else inner.id if isinstance(inner, ast.Name) else ""
                if cls.endswith("Stack"):
                    return True
    return False


def _fixture_scope(fn: ast.FunctionDef) -> str | None:
    """The scope of a pytest fixture ("function" when none is given), or None when the function is not a fixture."""
    for d in fn.decorator_list:
        target = d.func if isinstance(d, ast.Call) else d
        text = ast.unparse(target)
        if text.endswith("fixture"):
            if isinstance(d, ast.Call):
                for k in d.keywords:
                    if k.arg == "scope" and isinstance(k.value, ast.Constant):
                        return str(k.value.value)
            return "function"
    return None


def per_test_starts(path: Path) -> list[int]:
    """Lines of function-scoped fixtures and tests that start a real server, and of any call of the pool's _start_stack outside wb_live."""
    tree = ast.parse(path.read_text(encoding="utf-8"), str(path))
    out = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            scope = _fixture_scope(node)
            is_test = node.name.startswith("test_")
            if (scope == "function" or is_test) and _starts_server(node):
                out.append(node.lineno)
        if path.name != "wb_live.py" and isinstance(node, ast.Call):
            f = node.func
            if (f.attr if isinstance(f, ast.Attribute) else f.id if isinstance(f, ast.Name) else "") == "_start_stack":
                out.append(node.lineno)
    return sorted(set(out))


def module_fixtures_of_spread_tests(path: Path) -> list[str]:
    """Spread tests that ask for a fixture this file defines with module scope."""
    tree = ast.parse(path.read_text(encoding="utf-8"), str(path))
    module_fx = {n.name for n in tree.body if isinstance(n, ast.FunctionDef) and _fixture_scope(n) in ("module", "package", "class")}
    file_spread = any(isinstance(n, ast.Assign) and "spread" in ast.unparse(n.value) and any(getattr(t, "id", "") == "pytestmark" for t in n.targets)
                      for n in tree.body)
    out = []
    for n in tree.body:
        if isinstance(n, ast.FunctionDef) and n.name.startswith("test_"):
            spread = file_spread or any("spread" in ast.unparse(d) for d in n.decorator_list)
            used = {a.arg for a in n.args.args} & module_fx
            if spread and used:
                out.append(f"{path.name}:{n.lineno} {n.name} asks for {sorted(used)}")
    return out


# ---- rule 4: the quick file checks start nothing --------------------------------------------------------------------------------
STATIC_FIXTURES = {"tmp_path", "tmp_path_factory", "monkeypatch", "capsys", "capfd", "request", "subtests"}
STATIC_NO_CALLS = {"TestClient", "create_app", "_stack", "sync_playwright", "async_playwright", "Popen", "run_server", "serve"}
STATIC_NO_IMPORTS = {"playwright", "wb_live", "browser_harness", "worker_fixtures", "subprocess", "uvicorn", "table_chart_backend"}


def _marked_static(node, file_static: bool) -> bool:
    return file_static or any("mark.static" in ast.unparse(d) for d in node.decorator_list)


def static_violations(path: Path) -> list[str]:
    """What a file's static tests do that a quick file check may not (rule 4)."""
    tree = ast.parse(path.read_text(encoding="utf-8"), str(path))
    file_static = any(isinstance(n, ast.Assign) and "mark.static" in ast.unparse(n.value) and any(getattr(t, "id", "") == "pytestmark" for t in n.targets)
                      for n in tree.body)
    funcs = {n.name: n for n in ast.walk(tree) if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))}
    tests = [n for n in ast.walk(tree) if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name.startswith("test")
             and _marked_static(n, file_static)]
    if not tests:
        return []
    out = []
    for n in tree.body:                                       # the file's imports: a static file has no browser or stack helper
        names = [a.name for a in n.names] if isinstance(n, ast.Import) else [n.module or ""] if isinstance(n, ast.ImportFrom) else []
        bad = [m for m in names if m.split(".")[0] in STATIC_NO_IMPORTS or m.split(".")[0].endswith("_stack")]
        if bad:
            out.append(f"{path.name}:{n.lineno} imports {bad}")

    def calls(fn, seen):                                      # the names a function calls, through the file's own helpers
        found = set()
        for c in ast.walk(fn):
            if isinstance(c, ast.Call):
                name = c.func.id if isinstance(c.func, ast.Name) else c.func.attr if isinstance(c.func, ast.Attribute) else ""
                found.add(name)
                if name in funcs and name not in seen:
                    seen.add(name)
                    found |= calls(funcs[name], seen)
        return found

    def fixtures_ok(fn, seen) -> list[str]:
        bad = []
        params = {a.strip() for d in fn.decorator_list if "parametrize" in ast.unparse(d) and isinstance(d, ast.Call) and d.args
                  and isinstance(d.args[0], ast.Constant) for a in str(d.args[0].value).split(",")}
        for a in (x.arg for x in fn.args.args):
            if a in STATIC_FIXTURES or a in params or a == "self" or a in seen:
                continue
            if a in funcs:                                    # a fixture of this file: the same rule for it
                seen.add(a)
                bad += fixtures_ok(funcs[a], seen)
                bad += [f"{a} calls {sorted(calls(funcs[a], set()) & STATIC_NO_CALLS)}"] if calls(funcs[a], set()) & STATIC_NO_CALLS else []
            else:
                bad.append(f"asks for {a}")
        return bad

    for t in tests:
        problems = fixtures_ok(t, set())
        called = calls(t, {t.name}) & STATIC_NO_CALLS
        if called:
            problems.append(f"calls {sorted(called)}")
        if problems:
            out.append(f"{path.name}:{t.lineno} {t.name}: " + "; ".join(problems))
    return out


def static_files() -> list[Path]:
    root = TESTS.parents[1]
    tools = [p for p in (root / "tools").rglob("test_*.py") if not {".venv", "cache", "node_modules"} & set(p.parts)]
    return sorted(TESTS.glob("test_*.py")) + sorted(tools)


def test_static_tests_start_nothing():
    found = [x for p in static_files() for x in static_violations(p)]
    assert not found, ("a test marked static runs in the drill's gate, before the server jar exists: it reads files and starts no "
                       "server, console or browser (unmark it, or move what it starts into a test of the lanes; TEST_HARNESS.md, "
                       "rule 11):\n" + "\n".join(found))


def test_no_fixed_pause_over_100_ms_without_its_reason():
    over = []
    for p in browser_files():
        found = pauses(p)
        allowed = BASELINE.get(p.name, (0, 0))[0]
        if len(found) > allowed:
            over.append(f"{p.name}: {len(found)} pauses over {LIMIT_MS} ms without '# wait-ok: <why>' (allowed {allowed}): "
                        + ", ".join(f"line {ln} ({ms:.0f} ms)" for ln, ms in found[:8]))
    assert not over, ("wait on the page's settled signal (browser_harness.settled / Drishti.settled) or the condition itself, "
                      "or give the pause its reason (TEST_HARNESS.md, rule 3):\n" + "\n".join(over))


def test_no_server_started_per_test():
    over = []
    for p in browser_files():
        found = per_test_starts(p)
        allowed = BASELINE.get(p.name, (0, 0))[1]
        if len(found) > allowed:
            over.append(f"{p.name}: lines {found} (allowed {allowed})")
    assert not over, ("a real server is started in a module or session fixture, through wb_live._stack (the pool) or a *_stack "
                      "module, never per test (TEST_HARNESS.md, rule 4):\n" + "\n".join(over))


def test_spread_tests_ask_only_for_per_worker_fixtures():
    found = [x for p in browser_files() for x in module_fixtures_of_spread_tests(p)]
    assert not found, ("a spread test runs on any worker: a module fixture would start again on each (use a session fixture, "
                       "TEST_HARNESS.md, rule 2):\n" + "\n".join(found))


def test_the_baseline_only_goes_down():
    """A baseline entry larger than the file's count today is stale: lower it, so a pause cannot come back unseen."""
    stale = []
    for name, (p_allowed, s_allowed) in BASELINE.items():
        path = TESTS / name
        if not path.exists():
            stale.append(f"{name}: no such file (remove it from BASELINE)")
            continue
        p_now, s_now = len(pauses(path)), len(per_test_starts(path))
        if p_now < p_allowed or s_now < s_allowed:
            stale.append(f"{name}: BASELINE says ({p_allowed}, {s_allowed}), the file has ({p_now}, {s_now}): lower it")
    assert not stale, "\n".join(stale)


def test_the_rules_catch_what_they_name(tmp_path):
    f = tmp_path / "x_browser.py"
    f.write_text("import pytest\n"
                 "def a(page):\n    page.wait_for_timeout(400)\n"
                 "def b(page):\n    page.wait_for_timeout(400)  # wait-ok: the debounce under test\n"
                 "def c(page):\n    page.wait_for_timeout(50)\n"
                 "@pytest.fixture\ndef d(tmp_path_factory):\n    yield from _stack(tmp_path_factory, {})\n"
                 "@pytest.fixture(scope='module')\ndef e(tmp_path_factory):\n    yield from _stack(tmp_path_factory, {})\n"
                 "@pytest.fixture(scope='module')\ndef m():\n    return 1\n"
                 "@pytest.mark.spread\ndef test_s(m):\n    pass\n")
    assert [ln for ln, _ in pauses(f)] == [3]
    assert per_test_starts(f) == [9]
    assert len(module_fixtures_of_spread_tests(f)) == 1
    g = tmp_path / "test_y.py"
    g.write_text("import pytest\nfrom fastapi.testclient import TestClient\n"
                 "@pytest.fixture\ndef app():\n    return create_app(1)\n"
                 "@pytest.mark.static\ndef test_a(tmp_path):\n    pass\n"
                 "@pytest.mark.static\ndef test_b(client):\n    pass\n"
                 "@pytest.mark.static\ndef test_c(app):\n    pass\n"
                 "def _helper():\n    return TestClient(1)\n"
                 "@pytest.mark.static\ndef test_d():\n    _helper()\n"
                 "def test_e(client):\n    pass\n")
    assert [v.split(":")[1].split()[1] for v in static_violations(g)] == ["test_b", "test_c", "test_d"], static_violations(g)
    h = tmp_path / "test_z.py"
    h.write_text("import pytest\nimport wb_live\npytestmark = pytest.mark.static\ndef test_a():\n    pass\n")
    assert len(static_violations(h)) == 1 and "imports" in static_violations(h)[0]
