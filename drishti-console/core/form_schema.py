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

"""The fields of a server-built form, as drishti-inputs' ``Form`` reads its schema (docs/architecture/ELEMENTS.md section 8.2): one home
for the alert form (core/alert_form.py) and the Admin forms (core/admin_forms.py). Each helper gives one field's schema; ``name`` is what a
surrounding server form submits (``Form``'s named fields), ``hook`` the data attributes (or classes) on the field's control, kept for the
page's script, the tests and the tutorials."""
from __future__ import annotations

import json


def select(title: str, name: str | None, choices, hook=None, **more) -> dict:
    """A select of ``(value, words)`` pairs (``Form`` draws radios for ``widget: "radio"``)."""
    out = {"type": "string", "title": title, "widget": "select", "allowEmpty": False,
           "enum": [c[0] for c in choices], "enumLabels": [c[1] for c in choices]}
    if name:
        out["name"] = name
    if hook:
        out["hook"] = hook
    out.update(more)
    return out


def text(title: str, name: str | None, hook=None, **more) -> dict:
    """A line of text (``format: "textarea"`` for several lines); the hook defaults to the monospace class."""
    out = {"type": "string", "title": title, "hook": hook or {"class": "mono"}}
    if name:
        out["name"] = name
    out.update(more)
    return out


def check(title: str, name: str | None, hook=None, **more) -> dict:
    """A checkbox; with a name it submits ``on`` when ticked and nothing when not (a missing field is off)."""
    out = {"type": "boolean", "title": title}
    if name:
        out["name"] = name
        out["nameValue"] = "on"
    if hook:
        out["hook"] = hook
    out.update(more)
    return out


def number(title: str, name: str | None, hook=None, *, integer: bool = True, **more) -> dict:
    """A number, read loosely and shown grouped (``minimum``, ``maximum`` checked in words)."""
    out = {"type": "integer" if integer else "number", "title": title}
    if name:
        out["name"] = name
    if hook:
        out["hook"] = hook
    out.update(more)
    return out


def date(title: str, name: str | None, hook=None, **more) -> dict:
    """A date: drishti-inputs' date picker (an ISO date; any day, not only business days, unless ``options.businessOnly``)."""
    out = {"type": "date", "title": title}
    if name:
        out["name"] = name
    if hook:
        out["hook"] = hook
    out.update(more)
    return out


def obj(properties: dict, title: str = "", **more) -> dict:
    """An object: a form's root, or a fieldset inside it."""
    out = {"type": "object", "properties": properties}
    if title:
        out["title"] = title
    out.update(more)
    return out


def form_json(schema: dict, value: dict | None = None, **options) -> str:
    """What a page's ``data-di-form`` attribute carries: the schema, its value and the plain ``FormOptions`` (drishti-inputs'
    ``formFromData`` reads it; the template's autoescape quotes it)."""
    spec = {"schema": schema, **options}
    if value is not None:
        spec["value"] = value
    return json.dumps(spec, separators=(",", ":"))
