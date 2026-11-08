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

"""The Admin screens' forms as drishti-inputs draws them (docs/architecture/ELEMENTS.md section 12.3): each builder answers the host of a
server-built schema (``core/form_kit.py``); a template calls ``admin_form("<name>", ...)``. Every field keeps the old control's name and
data attributes (``hook``), so the routes and the pages' scripts read the same names; what a page posts as JSON its script reads from the
Form. Nothing here decides who may do what: the routes keep every check."""
from __future__ import annotations

from typing import Callable

from markupsafe import Markup

from core import form_kit as K

POWERS = [("raw", "See every field unmasked (raw JSON, views, search)"), ("author", "Author Sutras (save and propose from the workbench)"),
          ("approve", "Approve proposed Sutras"), ("admin", "Administer users, roles and caches"), ("calc", "Use Calc: Python in the browser on what the role opens"),
          ("edit", "Submit edits: change records through forms that save (write-back, when editing is on)"),
          ("layout", "Customise layouts: arrange a view's panels for themselves (Alt+L)")]


def role(**_) -> Markup:
    """Admin › Roles' dialog: a role's name, words, the kinds it opens and its powers (the page posts JSON from the Form)."""
    props = {
        "name": K.text("Name", "name", pattern="[a-z][a-z0-9-]{1,63}", patternMessage="a lower-case name: letters, digits and dashes, starting with a letter",
                       placeholder="credit-analyst", maxLength=64),
        "description": K.text("Description", "description", placeholder="Reads counterparties and credit curves"),
        "all": K.check("Opens every kind", "all"),
        "kinds": K.area("Kinds it opens (one per line, or pick by pack below)", "kinds", rows=4, placeholder="counterparty\ncredit-curve",
                        when={"field": "all", "notEquals": True}),
        "powers": K.group("Powers", {k: K.check(words, k) for k, words in POWERS}),
    }
    value = {"name": "", "description": "", "all": False, "kinds": "", "powers": {k: k == "layout" for k, _ in POWERS}}
    return K.host(K.schema(props, required=["name"]), value, attrs={"data-role-fields": ""}, label="The role")


def user(*, roles=(), packs=None, force_change: bool = False, **_) -> Markup:
    """Admin › Users' dialog: who the person is, their roles and packs, and a first password for a new user."""
    props = {
        "username": K.text("User name", "username", pattern="[a-z0-9][a-z0-9._-]{2,63}", maxLength=64,
                           patternMessage="3 to 64 lower-case letters, digits, dots, dashes or underscores, starting with a letter or digit"),
        "displayName": K.text("Display name", "displayName"),
        "desk": K.text("Desk", "desk"),
        "email": K.text("Email", "email", format="email"),
        "roles": K.checks("Roles", "roles", list(roles)),
    }
    if packs:
        props["packs"] = K.checks("Packs", "packs", list(packs), description=(
            "Tick the packs this person may use. Untick every pack and they see none (every kind answers no access). Packs are saved as a "
            "list; the server default applies only to people never given a list."), hook={"class": "adm-packs-box"})
    props["enabled"] = K.check("Enabled", "enabled")
    props["password"] = K.text("Initial password", "password", format="password", hook={"data-pw": ""}, when={"field": "editing", "notEquals": True})
    props["mustChangePassword"] = K.check("Must change at first sign-in", "mustChangePassword", hook={"data-pw": ""}, when={"field": "editing", "notEquals": True})
    value = {"username": "", "displayName": "", "desk": "", "email": "", "roles": [], "packs": list(packs or []), "enabled": True,
             "password": "", "mustChangePassword": bool(force_change), "editing": False}    # editing: the page's state (no field), hides the password
    return K.host(K.schema(props, required=["username"]), value, attrs={"data-user-fields": ""}, label="The user")


def search(*, name: str, value: str = "", title: str, placeholder: str = "", hook=None, **_) -> Markup:
    """A one-field search in a page's GET form (Enter submits it, as before): Users, Audit, the banking module's Scopes."""
    return K.host(K.schema({name: K.text(title, name, format="search", placeholder=placeholder, hook=hook)}), {name: value or ""},
                  attrs={"data-search-fields": ""}, layout="inline", label=title)


_KINDS = {"text": K.text, "area": K.area, "check": K.check, "date": K.date, "file": K.file}


def field(*, key: str, kind: str = "text", title: str, name: str | None = None, value=None, required: bool = False, attrs: dict | None = None,
          layout: str = "stacked", **more) -> Markup:
    """One field on its own (a reason, a load id, a typed name to confirm, a date, a file, a box to tick): ``kind`` text, area, check, date
    or file; the rest of the field's schema in ``more`` (``hook``, ``placeholder``, ``maxLength``, ``mono``, ``accept``)."""
    fs = _KINDS[kind](title, name, **more)
    empty = False if kind == "check" else ""
    return K.host(K.schema({key: fs}, required=[key] if required else ()), {key: empty if value is None else value},
                  attrs={"data-field-of": key, **(attrs or {})}, layout=layout, label=title)


def access_filter(*, f: dict, **_) -> Markup:
    """Admin › Access log's filter (a GET form): person, what, kind, id, from and until."""
    props = {"user": K.text("Person", "user", mono=True), "action": K.select("What", "action", ["view", "raw", "history", "search", "export"], empty="anything"),
             "kind": K.text("Kind", "kind", mono=True, placeholder="trade, or TRD for searches"), "id": K.text("Id", "id", mono=True),
             "from": K.date("From", "from"), "to": K.date("Until", "to")}
    value = {k: (f.get(k) or "") for k in props}
    for k in ("from", "to"):
        if not value[k]:
            value.pop(k)
    return K.host(K.schema(props), value, attrs={"data-access-fields": ""}, layout="inline", label="Filter the access log")


def config_search(*, q: str = "", area: str = "", areas=(), **_) -> Markup:
    """Admin › Configuration's search (a GET form): words and an area."""
    props = {"q": K.text("Search settings", "q", format="search", hook={"data-cfe-q": ""}, placeholder="A key, an environment variable or words: confidence, DRISHTI_PORT, lens"),
             "area": K.select("Area", "area", list(areas), empty="Every area", hook={"data-cfe-area": ""})}
    return K.host(K.schema(props), {"q": q or "", "area": area or ""}, attrs={"data-cfe-fields": ""}, layout="inline", label="Search settings")


def config_propose(**_) -> Markup:
    """Admin › Configuration's Propose a change: the new value and why (a second administrator approves it; the page posts JSON)."""
    props = {"value": K.text("New value", "value", maxLength=2000, hook={"data-dlg-value": ""}),
             "reason": K.area("Why (the approver reads it)", "reason", rows=3, maxLength=500, hook={"data-dlg-reason": ""})}
    return K.host(K.schema(props, required=["value"]), {"value": "", "reason": ""}, attrs={"data-propose-fields": ""}, label="Propose a change")


CONNECTOR_STATES = [("RUNNING", "Running"), ("DISABLED", "Disabled"), ("OFFLINE", "Off (offline mode)"), ("FAILED", "Failed"), ("problem", "With a problem")]
CONNECTOR_ORIGINS = [("file", "File"), ("pack", "Pack"), ("application", "application.yaml")]
ARTIFACT_KINDS = [("pack", "Packs"), ("connector", "Connectors"), ("table", "BI tables"), ("model", "BI models"), ("report", "BI reports"), ("board", "BI boards"),
                  ("notebook", "BI notebooks"), ("python", "BI Python items")]
ARTIFACT_STATES = [("on", "On"), ("off", "Disabled"), ("problem", "With a problem"), ("not loaded", "Not loaded")]
ARTIFACT_ORIGINS = [("shipped", "Shipped"), ("archive", "Archive"), ("file", "File"), ("ui", "Console"), ("template", "Pack template"), ("application", "application.yaml")]


def connector_filters(**_) -> Markup:
    """Admin › Connectors' list filters (the page's script filters the rows as they change)."""
    props = {"q": K.text("Filter the connectors", None, format="search", hook={"data-filter": ""}, placeholder="Filter by name, plugin, kind or pack"),
             "state": K.select("Show connectors in state", None, CONNECTOR_STATES, empty="Any status", hook={"data-state-filter": ""}),
             "origin": K.select("Show connectors from", None, CONNECTOR_ORIGINS, empty="Any origin", hook={"data-origin-filter": ""})}
    return K.host(K.schema(props), {"q": "", "state": "", "origin": ""}, attrs={"data-filter-fields": ""}, layout="inline", label="Filter the connectors")


def artifact_filters(**_) -> Markup:
    """Admin › Artifacts' list filters."""
    props = {"q": K.text("Filter the artifacts", None, format="search", hook={"data-filter": ""}, placeholder="Filter by name, title or problem"),
             "kind": K.select("Show", None, ARTIFACT_KINDS, empty="All kinds", hook={"data-kind-filter": ""}),
             "state": K.select("Status", None, ARTIFACT_STATES, empty="Any status", hook={"data-state-filter": ""}),
             "origin": K.select("Origin", None, ARTIFACT_ORIGINS, empty="Any origin", hook={"data-origin-filter": ""})}
    return K.host(K.schema(props), {"q": "", "kind": "", "state": "", "origin": ""}, attrs={"data-filter-fields": ""}, layout="inline", label="Filter the artifacts")


def artifact_bi_file(**_) -> Markup:
    """Admin › Artifacts' Install a BI file: the file, and Replace (shown when a file of that name is there)."""
    props = {"file": K.file("Choose a file", None, accept=".yaml,.yml", hook={"data-bi-file": ""}),
             "replace": K.check("Replace the file that is there (it is archived first)", None, hook={"data-bi-replace": ""}, when={"field": "exists", "equals": True})}
    return K.host(K.schema(props), {"replace": False, "exists": False}, attrs={"data-bi-fields": ""}, label="A BI file")


def busy_override(*, max_minutes: int = 240, **_) -> Markup:
    """Admin › Health's busy protection override: the mode, a reason and how long (the page's script posts them)."""
    minutes = [m for m in (15, 30, 60, 120, 240) if m <= max_minutes] or [15]
    props = {"mode": K.radios("Override", "busy-mode", [("automatic", "Automatic"), ("open", "Let everything through"), ("hold", "Hold all new heavy work")],
                              hook={"class": "busy-modes"}),
             "reason": K.text("Reason", None, maxLength=300, hook={"data-busy-reason": ""}, placeholder='required, e.g. "month-end sign-off, CRO approved"'),
             "minutes": K.select("Ends after", None, [(str(m), f"{m} minutes") for m in minutes], hook={"data-busy-minutes": ""})}
    return K.host(K.schema(props), {"mode": "automatic", "reason": "", "minutes": "30" if 30 in minutes else str(minutes[0])},
                  attrs={"data-busy-fields": ""}, layout="inline", label="Operator override")


BUILDERS: dict[str, Callable[..., Markup]] = {
    "role": role, "user": user, "search": search, "field": field, "access_filter": access_filter, "config_search": config_search,
    "config_propose": config_propose, "connector_filters": connector_filters, "artifact_filters": artifact_filters, "artifact_bi_file": artifact_bi_file,
    "busy_override": busy_override}


def admin_form(form: str, /, **kw) -> Markup:
    """The host of an Admin form by name (a Jinja global)."""
    return BUILDERS[form](**kw)
