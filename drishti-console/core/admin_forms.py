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


BUILDERS: dict[str, Callable[..., Markup]] = {"role": role, "user": user, "search": search}


def admin_form(form: str, /, **kw) -> Markup:
    """The host of an Admin form by name (a Jinja global)."""
    return BUILDERS[form](**kw)
