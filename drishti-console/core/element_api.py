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

"""The element API of Drishti Elements as data: the ONE source the TypeScript declarations (``types/index.d.ts``, ``types/react.d.ts``), the npm package's
README tables and the developer guide's reference tables are generated from (``tools/elements_package.py``), and ``tests/test_element_package.py`` checks
against the real elements. The first contract of CLIENT_RENDERER.md › Drishti Elements: the design, section 13: a removal or a change of meaning here is a major version.

Types are TypeScript source text. Attribute names are the HTML attributes; ``prop`` is the property of the same attribute (camelCase)."""
from __future__ import annotations

TOKEN = "string | { token: string; expiresAt?: number } | Promise<string | { token: string; expiresAt?: number }>"
STATES_VIEW = "'idle' | 'loading' | 'live' | 'static' | 'reconnecting' | 'paused' | 'deleted' | 'error'"
STATES_REPORT = "'idle' | 'loading' | 'live' | 'static' | 'stale' | 'failed' | 'reconnecting' | 'paused' | 'error'"
ERROR = "{ code: string; status: number; detail: string; fatal?: boolean; retryInMs?: number }"

# event name (without the drishti: prefix) -> (TypeScript detail type, when)
SHARED_EVENTS = {
    "state": ("{ state: string; previous: string }", "every state change (drives a host's own live indicator)"),
    "error": (ERROR, "a DRS code; `fatal: false` while the element retries by itself or a single visual failed"),
    "token-needed": ("{ reason: 'missing' | 'refused' | 'provider-failed' }", "no token provider, the token was refused twice, or the provider threw: sign the user in again and set `tokenProvider`"),
    "masked": ("{ count?: number; panels?: string[]; columns?: string[] }", "what the user is shown has masked fields (a view: count and panels; a report: the columns)"),
    "tick": ("{ generation: number; seq?: number; latencyMs?: number; changed?: { strip: number[]; panels: string[] }; at?: string }", "live data was applied"),
}

VIEW_ATTRS = [
    ("server", "string", "the console's base URL; default: the origin the module was loaded from. Elements sharing a `server` share one connection"),
    ("kind", "string", "an entity kind (`trade`, `counterparty`, ...); required"),
    ("entity", "string", "the entity id; change it to show another entity (reloads in place); required"),
    ("as-of", "string", "`live` (default) or a business date `YYYY-MM-DD` (a past date is a static snapshot)"),
    ("known-at", "string", "an ISO instant: bitemporal \"as known at\""),
    ("theme", "string", "`inherit`, or a Drishti theme: `terminal`, `light` (default), `blue`, `green`, `wallstreet`, `crimson`, `crimson-dark`, `terminal-green`, `terminal-crimson`, `terminal-blue`"),
    ("density", "'compact' | 'comfortable'", "row padding of tables"),
    ("header", "'full' | 'strip' | 'none'", "title and strip, strip only, or panels only (default `full`; `none` for `<drishti-panel>`)"),
    ("panels", "string", "comma-separated panel ids to show (default all)"),
    ("live", "'auto' | 'off'", "`off` loads once and never subscribes"),
    ("label", "string", "the element's accessible name (`role=\"region\"`); default the view's title"),
    ("controls", "string", "the Sutra's controls as `legs=2&when=2026-10-01..2026-12-31` (the `ctl.` prefix is optional)"),
]
VIEW_PROPS = [
    ("tokenProvider", "() => " + TOKEN, "set on any element: one provider per page (also `DrishtiElements.configure({tokenProvider})`)", False),
    ("state", STATES_VIEW, "the state machine", True),
    ("view", "{ ref: { kind: string; id: string }; title: unknown; generation: number; provenance: unknown; panels: { id: string; kind: string; title: string }[] } | null", "once loaded", True),
    ("lastError", "{ code: string; status: number; detail: string } | null", "the last error", True),
    ("controls", "string", "the controls' values now", False),
]
VIEW_METHODS = [
    ("refresh", "(): Promise<void>", "fetch the view again (same entity), keep the subscription, swap what changed"),
    ("reload", "(): Promise<void>", "full reload: cancel in-flight requests, drop the subscription, fetch, subscribe"),
    ("pause", "(): void", "give the subscription back (the element also does this itself when hidden)"),
    ("resume", "(): void", "take it again"),
]
VIEW_EVENTS = {
    "loaded": ("{ ref: { kind: string; id: string }; generation: number; title: unknown; live: boolean }", "a view was loaded and painted"),
    "paint": ("{ ref: { kind: string; id: string }; generation: number }", "the shadow root was rebuilt"),
    "navigate": ("{ kind: string; id: string; href: string; panel?: string; source: 'link' | 'chart' | 'graph' }", "**cancellable**: the user activated a linked entity; not cancelled, the element shows it in place"),
    "controls": ("{ query: string; changed: string[] }", "a reader changed a control"),
}

REPORT_ATTRS = [
    ("server", "string", "the console's base URL; default: the origin the module was loaded from"),
    ("report", "string", "the id of a **published** report, e.g. `file:report:trades-overview`; required. The host application's `kinds` must list `report:<id>` or `report:*`"),
    ("page", "string", "the page to show first (default: the first page that is not a drill-through target)"),
    ("visual", "string", "show only this visual (its page is found for you): a card, a chart or a table in a card of the host's own"),
    ("filters", "string", "initial filters as JSON: `{\"slicers\": {\"<slicer id>\": [\"FX\"]}, \"dates\": \"2026-10-09\", \"selections\": [{\"visual\": \"by-desk\", \"field\": \"trade.DESK\", \"values\": [\"FX\"]}], \"mode\": \"highlight\"}`"),
    ("chrome", "'full' | 'bare'", "`full` (default): title, page tabs, business date, bookmarks, filter chips, live bar. `bare`: the visuals only (the default when `visual` is set)"),
    ("theme", "string", "as `<drishti-view>`"),
    ("live", "'auto' | 'off'", "`off` draws once and never follows the report's live tables"),
    ("label", "string", "the element's accessible name"),
]
REPORT_PROPS = [
    ("tokenProvider", "() => " + TOKEN, "as `<drishti-view>`", False),
    ("state", STATES_REPORT, "the state machine", True),
    ("page", "string", "the page shown (assign to change it)", False),
    ("filters", "{ page?: string; slicers?: Record<string, unknown>; dates?: string | { from?: string; to?: string } | null; selections?: { visual: string; field: string; values: unknown[] }[]; carry?: unknown[]; mode?: 'filter' | 'highlight' }",
     "the filters in force; assign the same shape to set them (anything left out is cleared)", False),
    ("lastError", "{ code: string; status: number; detail: string } | null", "the last error", True),
]
REPORT_METHODS = [
    ("reload", "(): Promise<void>", "full reload: fetch the report again and redraw"),
    ("refresh", "(): Promise<unknown>", "ask every visual again, quietly"),
    ("selectPage", "(id: string): boolean", "show another page (its filters are cleared, as when a reader clicks the tab)"),
    ("clearFilters", "(): void", "clear slicers, selections and the date (the Reset button)"),
    ("pause", "(): void", "give the live stream back"),
    ("resume", "(): void", "take it again"),
]
REPORT_EVENTS = {
    "loaded": ("{ report: string; title: string; page: string; visuals: string[]; live: boolean }", "the report was drawn (every visual of the first page has answered or failed)"),
    "page-changed": ("{ page: string; title: string; visuals: string[]; phone: boolean }", "a page was drawn (first draw, a tab, `selectPage`, or a swap to or from the phone layout)"),
    "filter-changed": ("{ page: string; slicers: Record<string, unknown>; dates: unknown; selections: { visual: string; field: string; values: unknown[] }[]; carry: unknown[]; mode: string; source: 'user' | 'api' }",
                       "a reader (or the host, `source: 'api'`) changed a slicer, a selection, the date or the page"),
}

ABOUT_ATTRS = [
    ("server", "string", "the console's base URL; default: the origin the module was loaded from"),
    ("kind", "string", "an entity kind (`trade`, `counterparty`, ...); required"),
    ("entity", "string", "the entity id; change it to show another entity's About; required"),
    ("theme", "string", "as `<drishti-view>`"),
    ("label", "string", "the element's accessible name (default `About this page`)"),
]
ABOUT_PROPS = [
    ("tokenProvider", "() => " + TOKEN, "as `<drishti-view>` (the application's scopes must include `embed:about`)", False),
    ("state", "'idle' | 'loading' | 'loaded' | 'error'", "the state machine", True),
    ("lastError", "{ code: string; status: number; detail: string } | null", "the last error", True),
]
ABOUT_EVENTS = {
    "loaded": ("{ kind: string; entity: string }", "the About body was drawn"),
    "state": SHARED_EVENTS["state"],
    "error": SHARED_EVENTS["error"],
    "token-needed": SHARED_EVENTS["token-needed"],
}

SEARCH_ATTRS = [
    ("server", "string", "the console's base URL; default: the origin the module was loaded from"),
    ("theme", "string", "as `<drishti-view>`"),
    ("placeholder", "string", "the box's placeholder (default `Search`)"),
    ("label", "string", "the box's accessible name (default the placeholder)"),
    ("limit", "number", "most results asked for (1 to 50, default 10; the server caps it at `drishti.embed.search.max-results`)"),
    ("min-chars", "number", "shortest query searched (default 2)"),
    ("delay", "number", "milliseconds of quiet after the last key before a search is made (default 250)"),
    ("target", "string", "a CSS selector of a `<drishti-view>` or `<drishti-panel>`: picking a result points it at that entity (unless `drishti:select` is cancelled)"),
]
SEARCH_PROPS = [
    ("tokenProvider", "() => " + TOKEN, "as `<drishti-view>` (the application's scopes must include `embed:search`)", False),
    ("value", "string", "the text in the box (assign to search for it)", False),
    ("results", "{ type: string; kind: string; id: string; mnemonic: string; title: string; subtitle: string }[]", "the results now shown", True),
    ("state", "'idle' | 'loading' | 'error'", "the state machine", True),
    ("lastError", "{ code: string; status: number; detail: string } | null", "the last error", True),
]
SEARCH_METHODS = [
    ("search", "(q?: string): Promise<unknown[]>", "search now (the box shows `q`); resolves with the results"),
    ("clear", "(box?: boolean): void", "empty the list, and the box unless `box` is false"),
]
SEARCH_EVENTS = {
    "results": ("{ query: string; results: { kind: string; id: string; mnemonic: string; title: string; subtitle: string }[] }", "a search answered"),
    "select": ("{ kind: string; id: string; mnemonic: string; title: string; type: string }", "**cancellable**: the user picked a result; not cancelled, the `target` view (if any) is pointed at it"),
    "state": SHARED_EVENTS["state"],
    "error": SHARED_EVENTS["error"],
    "token-needed": SHARED_EVENTS["token-needed"],
}

ELEMENTS = {
    "drishti-view": {"cls": "DrishtiView", "summary": "A live Drishti view of one entity.", "attributes": VIEW_ATTRS, "properties": VIEW_PROPS, "methods": VIEW_METHODS,
                     "events": {**VIEW_EVENTS, **SHARED_EVENTS}},
    "drishti-panel": {"cls": "DrishtiPanel", "extends": "DrishtiView", "summary": "One panel of a view: `<drishti-view>` with the `panel` attribute and the header off.",
                      "attributes": [("panel", "string", "the panel id to show (the same as `panels=\"<id>\"`); required")], "properties": [("panel", "string", "the attribute of the same name", False)],
                      "methods": [], "events": {}},
    "drishti-report": {"cls": "DrishtiReport", "summary": "A published BI report, or one page or visual of it, with its slicers, cross-filter and live updates.",
                       "attributes": REPORT_ATTRS, "properties": REPORT_PROPS, "methods": REPORT_METHODS, "events": {**REPORT_EVENTS, **SHARED_EVENTS}},
    "drishti-about": {"cls": "DrishtiAbout", "summary": "The \"About this page\" layers of one view (sources, lineage, the masks and rules that applied to this reader) in a panel of the host's own.",
                      "attributes": ABOUT_ATTRS, "properties": ABOUT_PROPS, "methods": [("reload", "(): Promise<void>", "ask the console for the About body again")], "events": ABOUT_EVENTS},
    "drishti-search": {"cls": "DrishtiSearch", "summary": "Search across Drishti from a host page: a box and the entities that match, masked for the signed-in user.",
                       "attributes": SEARCH_ATTRS, "properties": SEARCH_PROPS, "methods": SEARCH_METHODS, "events": SEARCH_EVENTS},
}


def camel(name: str) -> str:
    head, *rest = name.split("-")
    return head + "".join(p.capitalize() for p in rest)


def event_names(element: str) -> list[str]:
    return sorted(ELEMENTS[element]["events"])


def detail_type(element: str, event: str) -> str:
    """The name of an event's detail type: ``ViewLoadedDetail``, ``ReportFilterChangedDetail``."""
    return "".join(p.capitalize() for p in element.split("-")[1:] + event.split("-")) + "Detail"


def typescript() -> str:
    """``types/index.d.ts``: classes, detail types, the tag and event maps."""
    out = ["// Generated by tools/elements_package.py from drishti-console/core/element_api.py: do not edit.",
           "export type TokenResult = string | { token: string; expiresAt?: number };",
           "export type TokenProvider = () => TokenResult | Promise<TokenResult>;", ""]
    by_event: dict[str, list[str]] = {}
    for el in ("drishti-view", "drishti-report", "drishti-about", "drishti-search"):
        for ev, (detail, _when) in ELEMENTS[el]["events"].items():
            out.append(f"export type {detail_type(el, ev)} = {detail};")
            by_event.setdefault(ev, []).append(detail_type(el, ev))
    out.append("")
    for el, spec in ELEMENTS.items():
        out.append(f"/** {spec['summary']} */")
        out.append(f"export class {spec['cls']} extends {spec.get('extends', 'HTMLElement')} {{")
        props = {p[0] for p in spec["properties"]}
        for name, typ, doc in spec["attributes"]:
            if camel(name) not in props:                          # an attribute that is also a declared property is listed once, below
                out.append(f"  /** {doc} */\n  {camel(name)}: {typ};")
        for name, typ, doc, readonly in spec["properties"]:
            out.append(f"  /** {doc} */\n  {'readonly ' if readonly else ''}{name}: {typ};")
        for name, sig, doc in spec["methods"]:
            out.append(f"  /** {doc} */\n  {name}{sig};")
        out.append("}")
        out.append("")
    out.append("export interface DrishtiElementsGlobal {\n  readonly version: string;\n  configure(options: { server?: string; tokenProvider?: TokenProvider }): void;\n"
               "  resolve(text: string, options?: { server?: string; signal?: AbortSignal }): Promise<{ ref: { kind: string; id: string } | null; candidates?: unknown[] }>;\n"
               "  invalidateToken(): void;\n  connections(): number;\n  trustedTypes(): boolean;\n}")
    out.append("")
    out.append("declare global {\n  interface Window { DrishtiElements: DrishtiElementsGlobal }")
    out.append("  interface HTMLElementTagNameMap {")
    out += [f"    '{el}': {spec['cls']};" for el, spec in ELEMENTS.items()]
    out.append("  }\n  interface HTMLElementEventMap {")
    out += [f"    'drishti:{ev}': CustomEvent<{' | '.join(names)}>;" for ev, names in by_event.items()]
    out.append("  }\n}")
    out.append("")
    return "\n".join(out)


def react_types() -> str:
    """``types/react.d.ts``: the JSX.IntrinsicElements entries for React 19 (attributes as strings; events are bound with addEventListener or a ref)."""
    lines = ["// Generated by tools/elements_package.py from drishti-console/core/element_api.py: do not edit.",
             "import type * as React from 'react';", "import type { " + ", ".join(s["cls"] for s in ELEMENTS.values()) + " } from './index';", "",
             "type Attrs<T, A> = React.DetailedHTMLProps<React.HTMLAttributes<T>, T> & A;", "", "declare module 'react' {", "  namespace JSX {", "    interface IntrinsicElements {"]
    for el, spec in ELEMENTS.items():
        attrs = "; ".join(f"'{n}'?: string" for n, _t, _d in spec["attributes"])
        if el == "drishti-panel":
            attrs += "; " + "; ".join(f"'{n}'?: string" for n, _t, _d in ELEMENTS["drishti-view"]["attributes"])
        lines.append(f"      '{el}': Attrs<{spec['cls']}, {{ {attrs} }}>;")
    lines += ["    }", "  }", "}", ""]
    return "\n".join(lines)


def markdown_tables(element: str) -> str:
    """The reference tables of one element, for the guide and the package README."""
    spec = ELEMENTS[element]
    out = [f"#### `<{element}>`", "", spec["summary"], "", "| Attribute | Type | Meaning |", "|---|---|---|"]
    out += [f"| `{n}` | `{t}` | {d} |" for n, t, d in spec["attributes"]]
    if spec["properties"]:
        out += ["", "| Property | Type | Meaning |", "|---|---|---|"]
        out += [f"| `{n}`{' (read-only)' if ro else ''} | `{t}` | {d} |" for n, t, d, ro in spec["properties"]]
    if spec["methods"]:
        out += ["", "| Method | Effect |", "|---|---|"]
        out += [f"| `{n}{sig.split(':')[0]}` | {d} |" for n, sig, d in spec["methods"]]
    if spec["events"]:
        out += ["", "| Event (`drishti:`) | `detail` | When |", "|---|---|---|"]
        out += [f"| `{n}` | `{t}` | {d} |" for n, (t, d) in sorted(spec["events"].items())]
    return "\n".join(out) + "\n"
