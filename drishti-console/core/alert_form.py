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

"""The alert form as drishti-inputs draws it (docs/architecture/ELEMENTS.md section 12.3): the server sends a schema and its value, and the
page's ``Form``, embedded in the POST form, draws every field with the name the form always submitted (``routes/alert_routes.body_from``
reads the same names). The fields of one source, condition or trigger show only when it is chosen (``when``); a hidden field submits
nothing. The model's measures and the filters' fields arrive from the server after a model is chosen (``Form.setOptions``); the filters
are an array of objects the page turns into the hidden ``model_query`` field."""
from __future__ import annotations

REDUCE = [("", "one cell"), ("first", "the first row"), ("sum", "the sum of all rows"), ("max", "the largest row"), ("min", "the smallest row"),
          ("avg", "the average of the rows"), ("count", "the number of rows"), ("last", "the last row")]
FILTER_OPS = [("eq", "is"), ("ne", "is not"), ("in", "is one of"), ("not-in", "is none of"), ("gt", "is above"), ("ge", "is at least"), ("lt", "is below"),
              ("le", "is at most"), ("contains", "contains")]


def _sel(title: str, name: str, choices, hook=None, **more) -> dict:
    """A select of (value, words) pairs, submitted under ``name``."""
    out = {"type": "string", "title": title, "name": name, "widget": "select", "allowEmpty": False,
           "enum": [c[0] for c in choices], "enumLabels": [c[1] for c in choices]}
    if hook:
        out["hook"] = hook
    out.update(more)
    return out


def _text(title: str, name: str, hook=None, **more) -> dict:
    out = {"type": "string", "title": title, "name": name, "hook": hook or {"class": "mono"}}
    out.update(more)
    return out


def _check(title: str, name: str, hook=None) -> dict:
    out = {"type": "boolean", "title": title, "name": name, "nameValue": "on"}
    if hook:
        out["hook"] = hook
    return out


def _group(kind: str, when: str, field: str, title: str, props: dict, description: str = "") -> dict:
    """The fields of one choice (a source, a condition, a trigger): shown when ``field`` holds ``when``; the old page's data-group hooks kept."""
    out = {"type": "object", "title": title, "properties": props, "when": {"field": field, "equals": when},
           "hook": {"data-group": kind, "data-when": when}}
    if description:
        out["description"] = description
    return out


def _s(v) -> str:
    return "" if v is None else str(v)


def alert_form_schema(f: dict, *, schedules: list[str], opts: dict, editing: bool) -> dict:
    """``{"schema", "value"}`` for the alert form from its fields ``f`` (``routes/alert_routes.form_from`` or the new form's)."""
    reduce_model = [r for r in REDUCE if r[0] != "count"]
    model_ref = f.get("model_ref") or ""
    source = {"type": "object", "title": "Watch", "hook": {"class": "al-src"}, "properties": {
        "source_type": _sel("A", "source_type", [("visual", "visual of a BI report"), ("model", "measure of a model"), ("dataset", "measure of a version 1 dataset"),
                                                 ("field", "field of a Sutra entity")], {"data-pick": "source"}),
        "visual": _group("source", "visual", "source_type", "The visual", {
            "report": _text("Report", "report", placeholder="file:report:daily-risk"),
            "page": _text("Page", "page"),
            "visual": _text("Visual", "visual"),
            "measure": _text("Which column to watch", "measure", {"data-visual-column": ""}, placeholder="blank: the first number", emptyLabel="(the first number)"),
            "reduce": _sel("A visual with several rows has several cells. Watch", "reduce", REDUCE, {"data-reduce": ""}),
        }, "With the filters you had set on the screen (slicers, selections, drill path) when you pressed Alert me." if f.get("state") else ""),
        "model": _group("source", "model", "source_type", "The model", {
            "model_ref": _sel("Model", "model_ref", [(model_ref, model_ref)] if model_ref else [], {"data-al-model": ""}, allowEmpty=True, emptyLabel="(loading the models)"),
            "model_measure": _sel("Measure", "model_measure", [], {"data-al-measure": ""}, allowEmpty=True, emptyLabel="(choose a model first)"),
            "filters": {"type": "array", "title": "Only rows where (optional)", "addLabel": "Add a filter", "addHook": "al-addfilter", "itemHook": "al-filter",
                        "hook": {"data-al-filters-box": ""}, "items": {"type": "object", "title": "Filter", "properties": {
                            "field": {"type": "string", "title": "Field", "widget": "select", "enum": [], "allowEmpty": False},
                            "op": {"type": "string", "title": "Operator", "widget": "select", "allowEmpty": False, "enum": [o[0] for o in FILTER_OPS],
                                   "enumLabels": [o[1] for o in FILTER_OPS]},
                            "value": {"type": "string", "title": "Value (several separated by commas)", "placeholder": "FX, Rates"}}}},
            "model_reduce": _sel("If it answers with several rows, watch", "model_reduce", reduce_model, {"data-model-reduce": ""}),
        }, "The number is the model's measure read as you, with your row rules and masks."),
        "dataset": _group("source", "dataset", "source_type", "The dataset", {
            "dataset": _text("Dataset", "dataset", placeholder="file:dataset:trading"),
            "ds_measure": _text("Measure", "ds_measure"),
            "filters": _text("Only rows where (one per line: field op values, e.g. desk eq FX, Rates)", "filters", format="textarea"),
        }),
        "field": _group("source", "field", "source_type", "The entity", {
            "kind": _text("Kind", "kind", placeholder="trade"),
            "id": _text("Id", "id", placeholder="MX-20000001"),
            "field": _text("Field or expression", "field", placeholder="mtm"),
        }),
    }}
    cond = {"type": "object", "title": "Tell me when", "hook": {"class": "al-cond"}, "properties": {
        "condition_type": _sel("It", "condition_type", [("threshold", "crosses a limit"), ("change", "changes by too much"), ("nodata", "has no data by a time"),
                                                        ("rule", "holds (a rule)")], {"data-pick": "cond"}),
        "rule": _group("cond", "rule", "condition_type", "A rule", {},
                       "For a field source: its Field or expression is a Rachana-EL predicate over the entity, for example $.utilisation > 0.8. The alert is "
                       "raised once when it becomes true, and is armed again when it turns false."),
        "threshold": _group("cond", "threshold", "condition_type", "The limit", {
            "op": _sel("The number is", "op", [("above", "above"), ("below", "below"), ("between", "between"), ("outside", "outside")], {"data-pick": "range"}),
            "value": _text("Limit (the low edge for between and outside)", "value", placeholder="-300000"),
            "high": _text("High edge", "high", {"data-range-high": "", "class": "mono"}, when={"field": "op", "in": ["between", "outside"]}),
        }),
        "change": _group("cond", "change", "condition_type", "The change", {
            "change_op": _sel("It", "change_op", [("moves", "moves"), ("rises", "rises"), ("falls", "falls")]),
            "change_value": _text("By at least", "change_value"),
            "change_mode": _sel("Counted in", "change_mode", [("pct", "percent"), ("abs", "the number itself")]),
            "against": _sel("Against", "against", [("previous-run", "the previous look"), ("previous-day", "the previous business day")]),
        }),
        "nodata": _group("cond", "nodata", "condition_type", "The deadline", {
            "by": _text("Today's data has not arrived by", "by", placeholder="09:00"),
        }, "On a business day, from that time (in the business-date zone) until the source shows data for today's business date."),
        "hysteresis": _text("Hysteresis (how far back inside the limit before it re-arms)", "hysteresis"),
        "cooldown": _text("Cooldown (no second alert within)", "cooldown", placeholder="30m (the server's default)"),
    }}
    trigger = {"type": "object", "title": "Look", "hook": {"class": "al-when"}, "properties": {
        "trigger_mode": _sel("When", "trigger_mode", [("schedule", "on a schedule"), ("after-load", "after a data load"), ("live", "when it changes (a live source)")],
                             {"data-pick": "trigger"}),
        "schedule": _group("trigger", "schedule", "trigger_mode", "The schedule", {
            "schedule": _text("Schedule", "schedule", description="For example: " + ", ".join(schedules[:6])),
        }),
        "after-load": _group("trigger", "after-load", "trigger_mode", "The load", {"load_kind": _text("Only loads of kind (blank: any)", "load_kind", placeholder="TRD")}),
        "live": _group("trigger", "live", "trigger_mode", "Live", {"debounce": _text("Quiet for (before looking)", "debounce", placeholder="10s")}),
    }}
    to = {
        "d_inbox": _check("my inbox and the bell", "d_inbox"),
        "d_email": _check("email (to the address on my account)", "d_email"),
        "d_bridge": _check("the chat bridges that list alerts (a post that names the alert, with no number)", "d_bridge"),
    }
    for w in opts.get("webhooks") or []:
        to["w_" + w] = _check(f"the webhook {w} (a signed post that names the alert, with no number)", "w_" + w, {"data-webhook": ""})
    if opts.get("sms"):
        to["d_sms"] = _check("a text message to my mobile number (the alert's title and a link; no number)", "d_sms")
        to["phone"] = {"type": "string", "title": "My mobile number", "name": "phone", "maxLength": 24, "placeholder": opts.get("phone") or "+44 20 7946 0000",
                       "description": ("On file for you: " + opts["phone"]) if opts.get("phone") else ""}
    to["enabled"] = _check("Active", "enabled")
    deliver = {"type": "object", "title": "Tell me by", "hook": {"class": "al-to"}, "properties": to}
    schema = {"type": "object", "properties": {
        "name": {"type": "string", "title": "Name", "name": "name", "maxLength": 64, "pattern": "[A-Za-z0-9][A-Za-z0-9 ._\\-]*", "readOnly": editing,
                 "patternMessage": "letters, digits, spaces, dots, dashes and underscores, starting with a letter or digit"},
        "title": {"type": "string", "title": "Title (optional)", "name": "title", "maxLength": 200, "placeholder": "defaults to the name; the message starts with it"},
        "severity": _sel("Severity", "severity", [("info", "info"), ("warn", "warn"), ("critical", "critical")]),
        "source": source, "cond": cond, "trigger": trigger, "deliver": deliver}, "required": ["name"]}
    value = {
        "name": _s(f.get("name")), "title": _s(f.get("title")), "severity": f.get("severity") or "warn",
        "source": {"source_type": f.get("source_type") or "visual",
                   "visual": {"report": _s(f.get("report")), "page": _s(f.get("page")), "visual": _s(f.get("visual")), "measure": _s(f.get("measure")), "reduce": _s(f.get("reduce"))},
                   "model": {"model_ref": model_ref, "model_measure": "", "filters": [], "model_reduce": _s(f.get("model_reduce"))},
                   "dataset": {"dataset": _s(f.get("dataset")), "ds_measure": _s(f.get("ds_measure")), "filters": _s(f.get("filters"))},
                   "field": {"kind": _s(f.get("kind")), "id": _s(f.get("id")), "field": _s(f.get("field"))}},
        "cond": {"condition_type": f.get("condition_type") or "threshold", "rule": {},
                 "threshold": {"op": f.get("op") or "above", "value": _s(f.get("value")), "high": _s(f.get("high"))},
                 "change": {"change_op": f.get("change_op") or "moves", "change_value": _s(f.get("change_value")), "change_mode": f.get("change_mode") or "pct",
                            "against": f.get("against") or "previous-run"},
                 "nodata": {"by": _s(f.get("by"))}, "hysteresis": _s(f.get("hysteresis")), "cooldown": _s(f.get("cooldown"))},
        "trigger": {"trigger_mode": f.get("trigger_mode") or "schedule", "schedule": {"schedule": _s(f.get("schedule"))},
                    "after-load": {"load_kind": _s(f.get("load_kind"))}, "live": {"debounce": _s(f.get("debounce"))}},
        "deliver": {"d_inbox": bool(f.get("d_inbox")), "d_email": bool(f.get("d_email")), "d_bridge": bool(f.get("d_bridge")), "d_sms": bool(f.get("d_sms")),
                    "enabled": bool(f.get("enabled")), **{"w_" + w: w in (f.get("webhooks") or []) for w in opts.get("webhooks") or []}},
    }
    return {"schema": schema, "value": value}
