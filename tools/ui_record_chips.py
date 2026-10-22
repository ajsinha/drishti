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
"""The record chips of the Chip Store (UI_BOARD.md 12.4, the P5 follow-up): one generic chip per shape of panel the packs draw over an entity
page's record (list-table, series-line, kv-summary, ...), written by tools/ui_seed_chips.py next to the seed chips.

A record chip reads the page's record through its document pin 'doc' and the rows of one path of it through a rowset pin ('rows', or 'nodes'
and 'edges' for a graph). It names no field: the board's pin mapping carries the names (rows: POINTS, rows.x: TENOR), and a structured
setting in the panel's own form (a list of columns, series or markers, a pivot) is a 'spec' control the board sets. The same chip serves the
curve of a rates pack and the price history of an equity pack; only the mapping differs.
"""

import yaml

from ui_seed_chips import A11Y_CHART, A11Y_RECORD, A11Y_TABLE, chip, col, rowset

D, M = "dimension", "measure"
DOC_PIN = {"role": "data", "type": "document", "required": True, "shape": {"id": {"type": "id", "required": True}}}
DOC_TEXT = "The page's record as the viewer may see it (masks applied). Required; settings may read its fields as @doc.FIELD."

# the frame every record chip shares: the panel id on the page, its function key, its terminal code and its column
FRAME = {
    "panel-id": ({"role": "control", "type": "text"},
                 "The panel's id on the page when it is not the instance name (an old page's camelCase id). Empty: the instance name."),
    "key": ({"role": "control", "type": "text"}, "The function key that jumps to the panel (F2 to F12). Empty: no key."),
    "code": ({"role": "control", "type": "text"}, "The terminal mnemonic that opens the panel (REFS, CRV). Empty: none."),
    "area": ({"role": "control", "type": "text"}, "The page column: main (the default) or right."),
    "span": ({"role": "control", "type": "number"}, "How many of the page's 12 columns the panel takes. Empty: the kind's default."),
    "height": ({"role": "control", "type": "number"}, "The panel's height in rows. Empty: the kind's default."),
    "page": ({"role": "control", "type": "spec"},
             "The page controls, traced from the page header's 'page' pin: the settings read them as ctl.<id> (a row filter, a title). "
             "Empty: the page has none."),
}


def ctl(t, text, required=False):
    p = {"role": "control", "type": t}
    if required:
        p["required"] = True
    return p, text


SPEC = "spec"
# a pin name is lower case with hyphens; the panel option it fills keeps the grammar's own spelling
BODY_KEYS = {"x-fmt": "xFmt", "x-label": "xLabel", "y-label": "yLabel", "delta-fmt": "deltaFmt"}
FMT = ctl("text", "The number format of the values (price2, pct4, compact, signed0, ...). Empty: the kind's default.")
TONE = ctl("text", "How values are coloured: sign (red below zero), status, or empty for none.")

# name: (title, category, kind, purpose, rows pin {name: shape} or None, column slots {option: pin.column}, controls {name: (decl, text)},
#        example {connector, map, controls}, not-for, accessibility)
SPECS = {
    "list-table": ("List table", "table", "table", "The rows of one list in the record, as columns the board names, with totals, an optional "
                     "pivot view and an optional row filter.",
                     {"rows": {"id": col("id")}}, {},
                     {"columns": ctl(SPEC, "The columns, each {label, bind: '@.FIELD', fmt, tone, total, link}. Required.", True),
                      "pivot": ctl(SPEC, "The pivot view the person may switch to: {fields, rows, columns, values, filters, heat}. Empty: none."),
                      "where": ctl("text", "A row filter over the row (@.FIELD) and the page controls. Empty: every row."),
                      "children": ctl("text", "A tree: the row field that holds each row's child rows (@.CHILDREN). Empty: a flat list."),
                      "expand": ctl("number", "A tree: how many levels open at first. Empty: the kind's default.")},
                     {"connector": "core/ccp", "map": {"rows": "ACCOUNTS"},
                      "controls": {"columns": [{"label": "Account", "bind": "@.ID"}, {"label": "IM", "bind": "@.IM", "fmt": "compact", "total": True}]}},
                     "Rows from a search or a model (use panel-table or filterable-table); one record's fields (use kv-summary).", A11Y_TABLE),
    "dated-steps-ladder": ("Dated steps ladder", "ladder", "ladder", "The rows of one list in the record as a ladder (dated steps, newest first).",
                      {"rows": {"id": col("id")}}, {},
                      {"columns": ctl(SPEC, "The columns, each {label, bind: '@.FIELD', fmt}. Required.", True),
                       "empty": ctl("text", "What the ladder says when the list is empty. Empty: the kind's own words."),
                       "highlight": ctl("text", "The step to highlight, a condition over the row (#index == @doc.NEXT_INDEX). Empty: none.")},
                      {"connector": "counterparty-risk/margin-call", "map": {"rows": "EVENTS"},
                       "controls": {"columns": [{"label": "Time", "bind": "@.AT"}, {"label": "Event", "bind": "@.EVENT"}]}},
                      "Rows without an order that matters (use list-table).", A11Y_TABLE),
    "value-grid-surface": ("Value grid surface", "chart", "surface", "A grid of values (maturity by strike, expiry by tenor) from one list in the "
                       "record: a row per value of the row field, a column per setting.",
                       {"rows": {"y": col(D, True)}}, {"y": "rows.y"},
                       {"columns": ctl(SPEC, "The grid's columns, each {label, bind: '@.FIELD', fmt}. Required.", True),
                        "view": ctl("text", "How the grid opens: grid or 3d. Empty: grid.")},
                       {"connector": "market-data/cap-vol-surface", "map": {"rows": "GRID", "rows.y": "MATURITY"},
                        "controls": {"columns": [{"label": "ATM", "bind": "@.ATM", "fmt": "price2"}]}},
                       "A curve (use series-line).", A11Y_CHART),
    "series-line": ("Series line", "chart", "line", "A line over one list in the record: the x column in order, the y column as the value.",
                    {"rows": {"x": col(D, True), "y": col(M, True)}}, {"x": "rows.x", "y": "rows.y"}, {"fmt": FMT},
                    {"connector": "market-data/ir-curve", "map": {"rows": "POINTS", "rows.x": "TENOR", "rows.y": "ZERO_RATE"},
                     "controls": {"fmt": "price2"}},
                    "Several series (use area-series); amounts by label (use ranked-bar-list).", A11Y_CHART),
    "ranked-bar-list": ("Ranked bar list", "chart", "hbar", "Horizontal bars over one list in the record: a bar per label, as long as the value.",
                    {"rows": {"label": col(D), "value": col(M)}}, {"label": "rows.label", "value": "rows.value"},
                    {"fmt": FMT, "tone": TONE, "shape": ctl("text", "The shape: bars (the default) or donut."),
                     "on-select": ctl(SPEC, "What a click does: {by, field, filter: [panel ids]} filters other panels of the page. Empty: nothing.")},
                    {"connector": "counterparty-risk/simm", "map": {"rows": "RISK_CLASSES", "rows.label": "RISK_CLASS", "rows.value": "IM"},
                     "controls": {"fmt": "compact"}},
                    "A series in order (use series-line); steps to a total (use step-waterfall).", A11Y_CHART),
    "area-series": ("Area series", "chart", "area", "Filled series over one list in the record along the x column, with an optional limit line.",
                    {"rows": {"x": col(D, True)}}, {"x": "rows.x"},
                    {"series": ctl(SPEC, "The series, each {label, value: FIELD, tone}. Required.", True),
                     "limit": ctl("text", "A limit line, usually a record field (@doc.LIMIT). Empty: none.")},
                    {"connector": "counterparty-risk/exposure-profile", "map": {"rows": "BUCKETS", "rows.x": "TENOR"},
                     "controls": {"series": [{"label": "EE", "value": "EE", "tone": "link"}]}},
                    "One series (use series-line).", A11Y_CHART),
    "cross-tab-grid": ("Cross-tab grid", "table", "pivot", "A grid aggregated from one list in the record: a row per value of 'by', a column per "
                     "value of 'across', the value column added up (or the rows counted).",
                     {"rows": {"by": col(D, True), "across": col(D, True), "value": col(M)}},
                     {"by": "rows.by", "across": "rows.across", "value": "rows.value"},
                     {"agg": ctl("text", "How the cell is made: sum, count, avg, min or max. Empty: sum."), "fmt": FMT, "tone": TONE,
                      "heat": ctl("bool", "True shades the cells by value. Empty: no shading.")},
                     {"connector": "core/desk", "map": {"rows": "POSITIONS", "rows.by": "BOOK", "rows.across": "CURRENCY", "rows.value": "MTM"},
                      "controls": {"agg": "sum"}},
                     "A list of rows (use list-table).", A11Y_TABLE),
    "price-candles": ("Price candles", "chart", "candlestick", "Daily open, high, low and close bars from one list in the record, with "
                           "the volume beneath.",
                           {"rows": {"x": col("date", True), "volume": col(M)}}, {"x": "rows.x", "volume": "rows.volume"}, {"fmt": FMT},
                           {"connector": "market-data/commodity", "map": {"rows": "OHLC", "rows.x": "DATE", "rows.volume": "VOLUME"},
                            "controls": {"fmt": "price2"}},
                           "The close alone (use series-line).", A11Y_CHART),
    "point-scatter": ("Point scatter", "chart", "scatter", "A point per row of one list in the record: x against y, sized and grouped by "
                       "optional columns.",
                       {"rows": {"x": col(M, True), "y": col(M, True), "label": col(D), "size": col(M), "group": col(D)}},
                       {"x": "rows.x", "y": "rows.y", "label": "rows.label", "size": "rows.size", "group": "rows.group"},
                       {"fmt": FMT, "x-fmt": ctl("text", "The x axis format (the panel's xFmt). Empty: as fmt."),
                        "x-label": ctl("text", "The x axis label (xLabel). Empty: none."), "y-label": ctl("text", "The y axis label (yLabel). Empty: none.")},
                       {"connector": "core/legal-entity", "map": {"rows": "BOOKS", "rows.x": "VAR", "rows.y": "PNL", "rows.label": "BOOK"},
                        "controls": {"fmt": "signed0"}},
                       "One measure (use ranked-bar-list).", A11Y_CHART),
    "step-waterfall": ("Step waterfall", "chart", "waterfall", "Steps from one list in the record adding up to a total.",
                         {"rows": {"label": col(D, True), "value": col(M, True)}}, {"label": "rows.label", "value": "rows.value"},
                         {"fmt": FMT, "sum": ctl("text", "The label of the total bar. Empty: Total."),
                          "unit": ctl("text", "The unit shown with the values (USD). Empty: none.")},
                         {"connector": "market-risk/pnl-explain", "map": {"rows": "EXPLAIN_STEPS", "rows.label": "STEP", "rows.value": "PNL"},
                          "controls": {"sum": "Actual"}},
                         "Amounts that do not add up (use ranked-bar-list).", A11Y_CHART),
    "distribution-histogram": ("Distribution histogram", "chart", "histogram", "How many values of one list in the record fall in each band, with "
                         "markers at chosen values.",
                         {"rows": {"value": col(M)}}, {},
                         {"fmt": FMT, "unit": ctl("text", "The unit of the values (USD). Empty: none."),
                          "markers": ctl(SPEC, "Lines at values, each {label, value (a number or @doc.FIELD), tone}. Empty: none.")},
                         {"connector": "market-risk/var", "map": {"rows": "SCENARIO_PNL"},
                          "controls": {"markers": [{"label": "VaR 99%", "value": "-@doc.VAR_99", "tone": "neg"}]}},
                         "Values in order (use series-line).", A11Y_CHART),
    "relation-graph": ("Relation graph", "chart", "graph", "Nodes and edges from two lists in the record, laid out as a tree or a network.",
                     {"nodes": {"label": col(D, True), "group": col(D)}, "edges": {"from": col("id")}},
                     {"label": "nodes.label", "group": "nodes.group"},
                     {"layout": ctl("text", "tree or network. Empty: network.")},
                     {"connector": "core/counterparty", "map": {"nodes": "HIERARCHY.NODES", "edges": "HIERARCHY.EDGES", "nodes.label": "LABEL"},
                      "controls": {"layout": "tree"}},
                     "References without structure (use linked-entities).", A11Y_CHART),
    "kv-list": ("Label and value list", "kv", "kv", "Label and value pairs from one list in the record (each row a label and a value).",
                  {"rows": {"label": col(D), "value": col(D)}}, {}, {},
                  {"connector": "counterparty-risk/agreement", "map": {"rows": "ELECTIONS"}, "controls": {}},
                  "Named fields of the record itself (use kv-summary).", A11Y_RECORD),
    "kv-summary": ("Key fields summary", "kv", "kv", "Named fields of the page's record as label and value pairs, each with its format "
                      "and tone; a field may link to its own page.",
                      None, {},
                      {"columns": ctl(SPEC, "The fields, each {label, bind: '@doc.FIELD' or link(@doc.FIELD, 'kind'), fmt, tone}. Required.", True),
                       "when": ctl("text", "A condition on the record (@doc.TYPE == 'X'): the panel shows only when it holds. Empty: always.")},
                      {"connector": "core/counterparty", "map": {},
                       "controls": {"columns": [{"label": "Status", "bind": "@doc.KYC.STATUS", "tone": "status"}]}},
                      "A list of rows (use list-table).", A11Y_RECORD),
    "headline-figure": ("Headline figure", "kv", "metric", "One figure of the record, large, with its format, tone and a caption that may "
                      "quote other fields.",
                      None, {},
                      {"fmt": FMT, "tone": TONE, "caption": ctl("text", "A line under the figure; ${@doc.FIELD} quotes a field. Empty: none."),
                       "label": ctl("text", "The label over the figure. Empty: the title."),
                       "unit": ctl("text", "The unit shown with the figure (USD). Empty: none."),
                       "delta": ctl("text", "A change shown beside the figure, usually a record field (@doc.PNL1D). Empty: none."),
                       "delta-fmt": ctl("text", "The number format of the change (the panel's deltaFmt). Empty: as fmt.")},
                      {"connector": "trading/counterparty-trades", "map": {"value": "PAY_7D"}, "controls": {"fmt": "signed0", "tone": "sign"}},
                      "A figure against a maximum (use panel-gauge); several figures (use kv-summary).", A11Y_RECORD),
    "field-note": ("Note with fields", "text", "markdown", "A note in Markdown that may quote the record's fields, shown only when a "
                        "condition on the record holds.",
                        None, {},
                        {"text": ctl("text", "The note; ${@doc.FIELD} quotes a field. Required.", True),
                         "when": ctl("text", "A condition on the record (nonblank(@doc.FIELD)). Empty: always shown.")},
                        {"connector": "news-and-markets/news", "map": {}, "controls": {"text": "Served from ${@doc.SNAPSHOT}."}},
                        "A static note without fields (use panel-markdown).", "Headings and lists keep their structure for screen readers."),
    "map-highlight": ("Map with a highlighted place", "map", "map", "A world map with the record's place highlighted, places linking to their pages and an "
                   "optional shading by a field of the kind.",
                   None, {},
                   {"highlight": ctl("text", "The place to highlight, usually @doc.ISO3. Required.", True),
                    "link": ctl(SPEC, "Where a click on a place goes: {kind, id: 'PREFIX-{iso3}'}. Empty: nowhere."),
                    "shade": ctl(SPEC, "Shading by a field of every record of a kind: {kind, field, by, fmt, label}. Empty: none."),
                    "height": ctl("number", "The map's height in rows. Empty: the kind's default.")},
                   {"connector": "world-economy/jurisdiction", "map": {},
                    "controls": {"highlight": "@doc.ISO3", "link": {"kind": "jurisdiction", "id": "JUR-{iso3}"}}},
                   "Values by place from a model (use bi-map).", A11Y_CHART),
    "status-fields": ("Status fields", "kv", "status", "Named state fields of the page's record (confirmed, cleared, settled), each "
                      "coloured by its meaning.",
                      None, {},
                      {"fields": ctl(SPEC, "The fields, each {label, bind: '@doc.FIELD', tone}. Required.", True)},
                      {"connector": "trading/trade", "map": {},
                       "controls": {"fields": [{"label": "Confirmation", "bind": "@doc.CONFIRMATION.STATUS", "tone": "status"}]}},
                      "Fields without a meaning per value (use kv-summary).", A11Y_RECORD),
    "figure-gauge": ("Figure gauge", "chart", "gauge", "One figure of the record against a maximum, as a dial.",
                     None, {},
                     {"max": ctl("text", "The maximum of the dial: a number or a record field (@doc.LIMIT). Empty: 100.")},
                     {"connector": "trading/trade", "map": {"value": "RISK.DV01"}, "controls": {"max": "250000"}},
                     "A figure without a maximum (use headline-figure).", A11Y_RECORD),
    "event-timeline": ("Event timeline", "timeline", "timeline", "The rows of one list in the record as events in order: a label and a "
                       "detail per event.",
                       {"rows": {"label": col(D, True), "detail": col(D)}}, {"label": "rows.label", "detail": "rows.detail"}, {},
                       {"connector": "trading/trade", "map": {"rows": "LIFECYCLE.TIMELINE", "rows.label": "EVENT", "rows.detail": "DESCRIPTION"},
                        "controls": {}},
                       "Rows with many columns (use list-table); dated steps (use dated-steps-ladder).", A11Y_TABLE),
    "linked-entities": ("Linked entities", "feed", "links", "The entities the record refers to, each opening its own page.",
                     None, {}, {},
                     {"connector": "market-data/ir-curve", "map": {}, "controls": {"code": "REFS", "area": "right"}},
                     "Structure between entities (use relation-graph).", A11Y_RECORD),
}


def record_chips():
    out = []
    for name, (title, cat, kind, purpose, rows, slots, controls, ex, not_for, a11y) in SPECS.items():
        pins_in = {"doc": DOC_PIN}
        texts = {"doc": DOC_TEXT}
        panel = {"id": "%{panel-id}", "kind": kind, "title": f"%{{title|{title}}}"}
        if kind == "links":
            panel["of"] = "@doc"
        if kind in ("metric", "gauge"):                                         # one field of the record: a scalar data pin, mapped value: <field>
            pins_in["value"] = {"role": "data", "type": "measure", "required": True}
            texts["value"] = "The figure: map value: <the record's field>. Required."
            panel["value"] = "@value"
        for pin, shape in (rows or {}).items():
            pins_in[pin] = rowset(shape)
            cols = ", ".join(f"{c} ({d['type']}{', required' if d.get('required') else ''})" for c, d in shape.items())
            texts[pin] = (f"The rows of one list in the record: map {pin}: <the list's field>, and its columns {cols} to the row fields. "
                          "Required.")
            panel[pin] = f"@{pin}"
        for option, slot in slots.items():
            panel[option] = f"%{{{slot}}}"
        for c, (decl, text) in {**controls, **FRAME}.items():
            pins_in[c] = decl
            texts[c] = text
            if c not in ("panel-id", "page"):
                panel[BODY_KEYS.get(c, c)] = f"%{{{c}}}"
        inst = {"use": f"{name}@1", "supply": {"connector": ex["connector"]}, "map": {"doc.id": "id", **ex["map"]}}
        if ex["controls"]:
            inst["controls"] = ex["controls"]
        example = {"title": f"The {title.lower()} on the {ex['connector'].split('/')[1]} page",
                   "board": yaml.safe_dump({"params": {"id": {"type": "id", "of": ex["connector"], "required": True}},
                                            "chips": {"c": {**inst, "supply": {**inst["supply"], "query": {"id": "%{board.params.id}"}}}}},
                                           sort_keys=False, default_flow_style=None, width=140)}
        tags, fits = FIND[name]
        doc = chip(name, title, cat, [*tags, "record", "generic"], purpose, pins_in, {}, texts,
                   f"The grammar's '{kind}' panel over the page's record (PANELS.md); the board names every field. Read-only unless a "
                   "setting says otherwise.",
                   "Every empty setting is left out of the panel, so the kind's own default applies.",
                   "Paints at most 10,000 rows of the list; the record is read once per page.", not_for, a11y, panel, example)
        doc["manual"]["fits"] = fits
        out.append(doc)
    return out


# how a chip is found in the Chip Store: domain-neutral tags and a one-line hint of the data it fits
FIND = {
    "list-table": (["table", "rows", "columns", "totals", "pivot"], "a list of rows inside one record, with the columns to show"),
    "dated-steps-ladder": (["ladder", "timeline", "workflow", "events"], "rows with a time and what happened"),
    "value-grid-surface": (["surface", "grid", "matrix", "two-axis"], "rows with a row label and a value per column"),
    "series-line": (["time-series", "curve", "trend", "line"], "rows with an ordered x (a date or a tenor) and a measure"),
    "ranked-bar-list": (["ranking", "comparison", "breakdown", "bars"], "rows with a label and a measure"),
    "area-series": (["time-series", "profile", "limit", "area"], "rows with an ordered x and several measures"),
    "cross-tab-grid": (["pivot", "aggregation", "heatmap", "cross-tab"], "rows with two labels and a measure"),
    "price-candles": (["time-series", "ohlc", "prices", "volume"], "rows with a date, open, high, low, close and volume"),
    "point-scatter": (["comparison", "correlation", "bubbles", "scatter"], "rows with two measures, an optional size and group"),
    "step-waterfall": (["attribution", "bridge", "breakdown", "waterfall"], "rows with a label and an amount that add up to a total"),
    "distribution-histogram": (["distribution", "bands", "markers", "histogram"], "a list of numbers"),
    "relation-graph": (["hierarchy", "network", "tree", "graph"], "a list of nodes and a list of edges"),
    "kv-list": (["summary", "attributes", "key-value"], "rows with a label and a value"),
    "kv-summary": (["summary", "fields", "attributes", "key-value"], "named fields of one record"),
    "headline-figure": (["figure", "kpi", "summary"], "one number of a record, with an optional caption"),
    "field-note": (["note", "narrative", "text"], "text that quotes fields of a record"),
    "map-highlight": (["geo", "choropleth", "places", "map"], "a record with a place code (ISO3)"),
    "status-fields": (["status", "state", "workflow", "fields"], "state fields of one record with a meaning per value"),
    "figure-gauge": (["gauge", "limit", "utilisation", "figure"], "one number of a record against a maximum"),
    "event-timeline": (["timeline", "events", "lifecycle", "history"], "rows with an event and its detail"),
    "linked-entities": (["navigation", "references", "relations"], "a record that refers to other records"),
}
