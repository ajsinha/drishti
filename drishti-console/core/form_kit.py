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

"""The server's side of drishti-inputs' ``Form`` (docs/architecture/ELEMENTS.md sections 8.2 and 12.3): the fields of a schema in a few
words, and the host element a page puts where its form goes. The page draws no control of its own: ``drishti_form-host.js`` makes the
host a ``Form`` (embedded by default, so a field with a ``name`` is submitted by the page's own ``<form>`` exactly as the old control
was; a page's script reads and sets the rest through the Form). A field's ``hook`` puts the old control's data attributes on the new one,
so scripts, tests and tutorials find it as before.

Checkboxes: a ticked box submits its ``name`` with ``nameValue``; an unticked one submits nothing, as a native checkbox did, so a server
that reads a missing name as false keeps doing so. A script that posts JSON reads ``bool(form.getField(...))``."""
from __future__ import annotations

import json
from typing import Any, Iterable

from markupsafe import Markup, escape


def _choices(choices: Iterable) -> tuple[list, list]:
    vals, words = [], []
    for c in choices:
        v, w = (c[0], c[1]) if isinstance(c, (tuple, list)) else (c, c)
        vals.append(v)
        words.append(str(w))
    return vals, words


def _put(out: dict, hook=None, **more) -> dict:
    if hook:
        out["hook"] = hook
    out.update({k: v for k, v in more.items() if v is not None})
    return out


def text(title: str, name: str | None = None, *, hook=None, mono: bool = False, **more) -> dict:
    """A line of text (``format``: email, password or search; ``suggest``: values offered as one types; ``pattern``, ``maxLength``)."""
    return _put({"type": "string", "title": title, "name": name, "mono": mono or None}, hook, **more)


def area(title: str, name: str | None = None, *, rows: int = 3, hook=None, mono: bool = False, **more) -> dict:
    """Several lines of text."""
    return _put({"type": "string", "title": title, "name": name, "format": "textarea", "options": {"rows": rows}, "mono": mono or None}, hook, **more)


def number(title: str, name: str | None = None, *, hook=None, **more) -> dict:
    return _put({"type": "number", "title": title, "name": name}, hook, **more)


def select(title: str, name: str | None, choices: Iterable, *, hook=None, empty: str | None = None, **more) -> dict:
    """One of a list (value, words) or values; ``empty``: the words of a first, empty choice (none: a value is always chosen)."""
    vals, words = _choices(choices)
    out = {"type": "string", "title": title, "name": name, "widget": "select", "enum": vals, "enumLabels": words, "allowEmpty": empty is not None}
    if empty is not None:
        out["emptyLabel"] = empty
    return _put(out, hook, **more)


def radios(title: str, name: str | None, choices: Iterable, *, hook=None, option_hook: str | None = None, **more) -> dict:
    vals, words = _choices(choices)
    return _put({"type": "string", "title": title, "name": name, "widget": "radio", "enum": vals, "enumLabels": words, "optionHook": option_hook}, hook, **more)


def check(title: str, name: str | None = None, *, hook=None, value: str = "on", **more) -> dict:
    """A checkbox; ticked, it submits ``name=value`` (``on``, as a native checkbox with no value); unticked, nothing."""
    return _put({"type": "boolean", "title": title, "name": name, "nameValue": value if name else None}, hook, **more)


def checks(title: str, name: str | None, choices: Iterable, *, hook=None, option_hook: str | None = None, **more) -> dict:
    """A row of checkboxes, one per choice, each submitting ``name=value`` when ticked."""
    vals, words = _choices(choices)
    return _put({"type": "array", "title": title, "name": name, "widget": "checkboxes", "items": {"type": "string", "enum": vals, "enumLabels": words},
                 "optionHook": option_hook}, hook, **more)


def date(title: str, name: str | None = None, *, hook=None, **more) -> dict:
    return _put({"type": "date", "title": title, "name": name}, hook, **more)


def file(title: str, name: str | None = None, *, accept: str | None = None, hook=None, **more) -> dict:
    """A file to send; the page's script reads the input's files (the field's value is the file's name)."""
    return _put({"type": "string", "title": title, "name": name, "widget": "file", "accept": accept}, hook, **more)


def slider(title: str, minimum: float, maximum: float, *, increment: float = 1, hook=None, unit: str = "", **more) -> dict:
    return _put({"type": "number", "title": title, "widget": "range", "minimum": minimum, "maximum": maximum, "increment": increment, "unit": unit or None}, hook, **more)


def group(title: str, props: dict, *, when: dict | None = None, hook=None, description: str = "", required: Iterable[str] = ()) -> dict:
    """A fieldset of fields; ``when`` shows it only while another field holds a value (``{"field": f, "equals": v}``)."""
    out = {"type": "object", "title": title, "properties": props}
    if when:
        out["when"] = when
    if description:
        out["description"] = description
    if required:
        out["required"] = list(required)
    return _put(out, hook)


def schema(props: dict, *, required: Iterable[str] = (), title: str | None = None) -> dict:
    out = {"type": "object", "properties": props}
    if required:
        out["required"] = list(required)
    if title:
        out["title"] = title
    return out


def host(form_schema: dict, value: dict | None = None, *, attrs: dict | None = None, **options: Any) -> Markup:
    """The element a page puts where the form goes: ``<div data-di-form data-schema="…">``, the page's own data attributes in ``attrs``
    (``{"data-role-fields": ""}``), the Form's options (``layout``, ``label``, ``pathAttr``, ``embedded``) beside the schema."""
    spec = {"schema": form_schema, "value": value or {}}
    if options:
        spec["options"] = options
    extra = "".join(f" {escape(k)}" + (f'="{escape(v)}"' if v not in ("", None, True) else "") for k, v in (attrs or {}).items())
    return Markup(f'<div data-di-form{extra} data-schema="{escape(json.dumps(spec, separators=(",", ":")))}"></div>')
