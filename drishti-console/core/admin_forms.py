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
#: the module permissions a role may hold (ACCESS_MODEL.md section 8): the server's RoleDefinition.PERMISSIONS, each with its words
PERMISSIONS = [("var:system-baskets", "VaR system baskets: create, edit, retire and import them (VAR_GUIDE.md section 5.7)")]


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
        "permissions": K.group("Permissions", {k: K.check(words, k) for k, words in PERMISSIONS}),
    }
    value = {"name": "", "description": "", "all": False, "kinds": "", "powers": {k: k == "layout" for k, _ in POWERS},
             "permissions": {k: False for k, _ in PERMISSIONS}}
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


def pack_signature(**_) -> Markup:
    """Admin › Packs' Signed archive?: the publisher and the signature sent with the archive."""
    props = {"publisher": K.text("Publisher", None, hook={"data-publisher": ""}, placeholder="acme",
                                 description="The publisher's key must be listed in drishti.packs.registry.trusted-keys. Unsigned archives are accepted unless the server requires signatures."),
             "signature": K.text("Signature (base64 Ed25519 of the archive)", None, mono=True, hook={"data-signature": ""})}
    return K.host(K.schema(props), {"publisher": "", "signature": ""}, attrs={"data-signature-fields": ""}, label="Signed archive")


def pack_data(**_) -> Markup:
    """A pack's page, Load a data file: the file, the kind (when the pack loads several; the page's script fills the list) and the business
    date of a snapshot kind."""
    props = {"file": K.file("Data file", None, accept=".json,.jsonl,.ndjson", hook={"data-data-file": ""}),
             "kind": K.select("Kind", None, [], hook={"data-data-kind": ""}, when={"field": "many", "equals": True}),
             "date": K.date("Business date (snapshot)", None, hook={"data-data-date": ""}, when={"field": "snapshot", "equals": True})}
    return K.host(K.schema(props), {"many": False, "snapshot": False}, attrs={"data-data-fields": ""}, label="Load a data file")


def capacity_growth(**_) -> Markup:
    """Admin › Capacity's Plan for growth: three sliders (the page's script seeds them from the plan and recomputes as they move)."""
    props = {"intraday": K.slider("Intraday updated a day", 0, 100, unit="%", hook={"data-cap-grow": "intraday"}),
             "HotDates": K.slider("Dates in memory", 1, 60, hook={"data-cap-grow": "HotDates"}),
             "Users": K.slider("Concurrent users", 1, 1000, hook={"data-cap-grow": "Users"})}
    return K.host(K.schema(props), {"intraday": 0, "HotDates": 10, "Users": 1}, attrs={"data-cap-growth-fields": ""}, layout="inline", label="Plan for growth")


def capacity_download(**_) -> Markup:
    """Admin › Capacity's Download the working: which inputs the workbook carries (submitted with the dialog as ``inputs``)."""
    props = {"inputs": K.radios("Which inputs?", "inputs", [("plan", "What is on the screen"), ("live", "This server's live figures"), ("both", "Both, side by side")],
                                description="What is on the screen: the inputs as set here. This server's live figures: the measured positions, trades, dates in "
                                            "memory, users, requests a second and Java in the Inputs (the plan beside them), with the measured heap, RSS, disk and "
                                            "load under them. Both: the plan's inputs drive Results; a Live column beside them holds the measured figures, "
                                            "stamped with their time.")}
    return K.host(K.schema(props), {"inputs": "plan"}, attrs={"data-cap-download-fields": ""}, label="Which inputs?")


LAKE_MODES = [("overwrite-dates", "Replace those dates (re-runs are safe)"), ("overwrite-partitions", "Replace only those dates and classifier values"),
              ("append", "Add the rows")]


def lake_file(*, max_mb: int = 0, **_) -> Markup:
    """Admin › Lakes, step 1: the file (the page's script uploads it at once)."""
    return field(key="file", kind="file", title=f"A .json (array), .jsonl, .ndjson or .gz file, up to {max_mb:,} MB",
                 accept=".json,.jsonl,.ndjson,.gz,application/json", hook={"data-file": ""})


def lake_fields(**_) -> Markup:
    """Admin › Lakes, step 2: the id field (the file's fields, filled by the page's script) and another business date."""
    props = {"key": K.select("Id field (one row per id and date)", None, [], hook={"data-key": ""}),
             "asOf": K.date("Another business date for the whole file (optional)", None, hook={"data-lake-asof": ""})}
    return K.host(K.schema(props), {"key": ""}, attrs={"data-lake-fields": ""}, layout="columns", label="Id and business date")


def lake_target(**_) -> Markup:
    """Admin › Lakes, step 3: where it goes (the lakes, roots and packs filled by the page's script), how a date already there is
    treated, and whether to bind and announce."""
    props = {"table": K.select("Lake", None, [("", "A new lake (table)")], hook={"data-table": ""}),
             "root": K.select("Lake root", None, [], hook={"data-root": ""}),
             "domain": K.text("Domain", None, mono=True, hook={"data-domain": ""}, placeholder="trading"),
             "kind": K.text("Kind (the table)", None, mono=True, hook={"data-kind": ""}, placeholder="deal"),
             "mode": K.select("When a date is already there", None, LAKE_MODES, hook={"data-mode": ""}),
             "pack": K.select("Pack (its Delta connector reads the lake)", None, [], hook={"data-pack": ""}),
             "bind": K.check("Bind the lake to the pack's Delta connector (a new connector file, or the kind added to it; kept in its history)", None, hook={"data-bind": ""}),
             "signal": K.check("Tell the server the data landed (Data loads, alerts, notices)", None, hook={"data-signal": ""})}
    return K.host(K.schema(props), {"table": "", "root": "", "domain": "", "kind": "", "mode": "overwrite-dates", "pack": "", "bind": True, "signal": True},
                  attrs={"data-lake-target": ""}, layout="columns", label="Where it goes")


def net_explain(*, roles=(), **_) -> Markup:
    """Admin › Drishti*net, Explain a request: a kind and id, or a search, as a role, on a past date (the page's script asks the server)."""
    props = {"kind": K.text("Kind", None, mono=True, hook={"data-ex-kind": ""}, placeholder="trade"),
             "id": K.text("Id (optional)", None, mono=True, hook={"data-ex-id": ""}, placeholder="IRS-48213"),
             "q": K.text("or a search", None, mono=True, hook={"data-ex-q": ""}, placeholder="TRD where mtm > 1m"),
             "role": K.select("As the role", None, list(roles), empty="mine", hook={"data-ex-role": ""}),
             "dated": K.check("A past business date (a peer with another calendar is left out)", None, hook={"data-ex-dated": ""})}
    return K.host(K.schema(props), {"kind": "", "id": "", "q": "", "role": "", "dated": False}, attrs={"data-explain-fields": ""}, layout="columns",
                  label="Explain a request")


def net_node(*, tags=(), peer_only=(), **_) -> Markup:
    """Admin › Drishti*net, Access: this node's tags and the packs it serves to peers only."""
    props = {"tags": K.text("This node's tags (comma-separated)", None, mono=True, hook={"data-node-tags": ""}, placeholder="region:emea, tier:risk"),
             "peerOnly": K.text("Packs served to peers only (comma-separated)", None, mono=True, hook={"data-node-peer-only": ""}, placeholder="market-risk")}
    return K.host(K.schema(props), {"tags": ", ".join(tags or []), "peerOnly": ", ".join(peer_only or [])}, attrs={"data-node-fields": ""}, layout="columns",
                  label="This node")


def connector_head(**_) -> Markup:
    """Admin › Connectors' editor, the Form tab's head: the name, the plugin (the page's script lists the plugins), on or off, and the
    words. The plugin's own settings are a Form the script builds from the plugin's
    description (``/admin/connectors/api/plugins``)."""
    props = {"name": K.text("Name", None, mono=True, maxLength=64, pattern="[a-z0-9][a-z0-9\\-]*", hook={"data-f-name": ""}, placeholder="trading-lake",
                            patternMessage="lower case letters, digits and hyphens", description="The file is name.yaml; packs refer to the connector by this name."),
             "plugin": K.select("Plugin", None, [], hook={"data-f-plugin": ""}),
             "enabled": K.check("Connector is on", None, hook={"data-f-enabled": ""}),
             "description": K.text("Description", None, maxLength=200, hook={"data-f-description": ""},
                                   description="One line saying what this connector reads; shown in the list.")}
    return K.host(K.schema(props), {"name": "", "plugin": "", "enabled": True, "description": ""}, attrs={"data-conn-head": ""},
                  layout="columns", label="The connector")


# ---- Admin › Collaboration, Data loads, Embedding and Lakes › Maintain. A Form with a Save of its own is not embedded (the Form is its own
# <form>; the page's script gives it onSubmit through formOf); a dialog's Form has no buttons (the dialog's read it); the rest are read live.
OWN = {"embedded": False, "resetButton": False, "doneText": "", "layout": "columns"}
ALONE = {"embedded": False, "actions": False}
DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


def collab_search(**_) -> Markup:
    """Admin › Collaboration: find threads by who wrote in them, the entity or the dates."""
    props = {"user": K.text("User", "user"), "kind": K.text("Kind", "kind"), "id": K.text("Id", "id"), "from": K.date("From", "from"), "to": K.date("To", "to")}
    return K.host(K.schema(props), {}, attrs={"class": "acol-form", "data-acol-search": ""}, label="Find threads", submitLabel="Search", **OWN)


def collab_toggle(*, title: str, hook: str, **_) -> Markup:
    """One checkbox that filters a list at once (Only hidden comments, Active holds only)."""
    return K.host(K.schema({"on": K.check(title, None, hook={hook: ""})}), {"on": False}, attrs={"class": "acol-check"}, label=title)


def collab_hold(**_) -> Markup:
    """Admin › Collaboration, Place a legal hold: the scope's own fields follow the scope; the reason is required."""
    props = {
        "scope": K.select("Scope", "scope", [("entity", "one entity"), ("kind", "a kind"), ("user", "a user"), ("thread", "a thread"), ("all", "everything")],
                          hook={"data-acol-scope": ""}),
        "kind": K.text("Kind", "kind", when={"field": "scope", "in": ["entity", "kind"]}),
        "id": K.text("Id", "id", when={"field": "scope", "equals": "entity"}),
        "user": K.text("User", "user", when={"field": "scope", "equals": "user"}),
        "thread": K.text("Thread", "thread", when={"field": "scope", "equals": "thread"}),
        "from": K.date("From", "from"), "to": K.date("To", "to"),
        "reason": K.text("Reason", "reason", maxLength=400, description="Required: why the hold is placed (kept in the record)."),
    }
    return K.host(K.schema(props, required=["reason"]), {"scope": "entity"}, attrs={"class": "acol-form", "data-acol-hold-form": ""},
                  label="Place a legal hold", submitLabel="Place hold", **OWN)


def collab_export(**_) -> Markup:
    """Admin › Collaboration, Compliance export: the dates, the entity or user, and what it carries."""
    props = {"from": K.date("From", "from"), "to": K.date("To", "to"), "kind": K.text("Kind", "kind"), "id": K.text("Id", "id"), "user": K.text("User", "user"),
             "includeShares": K.check("Shares", "includeShares"), "includeThreads": K.check("Threads", "includeThreads")}
    return K.host(K.schema(props), {"includeShares": True, "includeThreads": True}, attrs={"class": "acol-form", "data-acol-export-form": ""},
                  label="Compliance export", submitLabel="Start export", **OWN)


def collab_verify(**_) -> Markup:
    """Admin › Collaboration, Verify the chains: one thread, or everything."""
    return K.host(K.schema({"thread": K.text("Thread", "thread", mono=True, placeholder="th_...")}), {}, attrs={"class": "acol-form", "data-acol-verify-form": ""},
                  label="Verify the chains", submitLabel="Verify", **OWN)


def loads_filter(*, kinds=(), kind: str = "", status: str = "", day: str = "", **_) -> Markup:
    """Admin › Packs › Data loads, the history's filter: embedded in the page's GET form (kind, status and date as before)."""
    props = {"kind": K.select("Kind", "kind", [(k, k) for k in kinds], empty="all", hook={"data-filter-kind": ""}),
             "status": K.select("Status", "status", [("ready", "ready"), ("failed", "failed")], empty="all"),
             "date": K.date("Date", "date")}
    value = {"kind": kind or "", "status": status or "", **({"date": day} if day else {})}
    return K.host(K.schema(props), value, attrs={"data-loads-filter": ""}, layout="columns", label="Filter the loads")


def loads_announce(*, kinds=(), **_) -> Markup:
    """Data loads, Announce a load: what an ETL says with ``drishti.py data landed`` (the dialog's Announce sends it)."""
    kinds = list(kinds)
    props = {"kind": K.select("Kind", "kind", [(k, k) for k in kinds]), "businessDate": K.date("Business date", "businessDate"),
             "status": K.select("Status", "status", [("ready", "ready"), ("failed", "failed")]),
             "rows": K.number("Rows (optional)", "rows", integer=True, minimum=0),
             "batchId": K.text("Batch id (optional)", "batchId", maxLength=100), "note": K.text("Note (optional)", "note", maxLength=500)}
    return K.host(K.schema(props, required=["kind", "businessDate"]), {"kind": kinds[0] if kinds else "", "status": "ready"}, attrs={"data-announce-form": ""},
                  label="Announce a load", **ALONE)


def loads_config(*, kinds=(), config: dict | None = None, **_) -> Markup:
    """Data loads, Expectations settings: who is told, the smoke test, and each expected kind as a repeatable row."""
    config = config or {}
    notify = config.get("notify") or {}
    expect = [{"kind": k, "by": e.get("by") or "19:00", "zone": e.get("zone") or "", "calendar": e.get("calendar") or ""}
              for k, e in (config.get("expect") or {}).items()]
    row = K.group("Expected kind", {
        "kind": K.select("Kind", "x-kind", [(k, k) for k in kinds], hook={"data-x-kind": ""}),
        "by": K.text("Due by (HH:mm)", "x-by", mono=True, hook={"data-x-by": ""}, pattern="[0-2][0-9]:[0-5][0-9]", patternMessage="a time such as 19:00",
                     default="19:00"),
        "zone": K.text("Time zone", "x-zone", hook={"data-x-zone": ""}, placeholder="server zone"),
        "calendar": K.text("Calendar", "x-cal", hook={"data-x-cal": ""}, placeholder="server calendar")}, required=["kind", "by"])
    props = {"roles": K.text("Tell these roles (comma separated)", "roles"), "users": K.text("and these users", "users"),
             "email": K.check("also send an email (needs the email channel)", "email"),
             "smoke": K.number("Smoke test: open this many sample entities after each ready load (0 = off)", "smoke", integer=True, minimum=0),
             "expect": {"type": "array", "title": "Expected each business day", "items": row, "hook": {"data-expect-rows": ""},
                        "itemHook": {"data-expect-row": ""}, "addHook": {"data-expect-add": ""}, "addLabel": "Add a kind"}}
    value = {"roles": ", ".join(notify.get("roles") or []), "users": ", ".join(notify.get("users") or []), "email": bool(notify.get("email")),
             "smoke": config.get("smoke") or 0, "expect": expect}
    return K.host(K.schema(props), value, attrs={"data-config-form": ""}, label="Expectations settings", **ALONE)


def embedding_filter(**_) -> Markup:
    """Admin › Embedding: the applications' filter (the page's script filters the rows as one types)."""
    return K.host(K.schema({"q": K.text("Filter the applications", "q", format="search", hook={"data-filter": ""}, placeholder="Filter by name, id or origin")}),
                  {"q": ""}, attrs={"class": "emb-filter"}, layout="inline", label="Filter the applications")


def embedding_new(*, emb: dict | None = None, **_) -> Markup:
    """Admin › Embedding, New host application: the JWK set shows for a key, the scopes are a row of checkboxes."""
    emb = emb or {}
    scopes = emb.get("scopes") or ["embed:view"]
    token_max = emb.get("tokenMaxSeconds") or 900
    props = {
        "name": K.text("Name", "name", maxLength=100, placeholder="Client CRM"),
        "id": K.text("Id (lower case letters, digits, hyphens)", "id", mono=True, maxLength=32, pattern="[a-z][a-z0-9\\-]{1,31}",
                     patternMessage="a lower-case letter, then letters, digits or hyphens (2 to 32)", placeholder="client-crm"),
        "origins": K.area("Origins, one per line (scheme, host, optional port; no path)", "origins", rows=3, mono=True, placeholder="https://crm.bank.example",
                          description="https://*.suffix is allowed." if emb.get("allowWildcardOrigins") else None),
        "auth": K.radios("How the application signs in", "auth", [("secret", "Client secret (made now, shown once)"),
                                                                  ("key", "Public key: a JWK set; the application signs its requests"), ("both", "Both")],
                         option_hook="auth"),
        "jwks": K.area("JWK set (JSON, public keys only)", "jwks", rows=5, mono=True, hook={"data-jwks-field": ""},
                       placeholder='{"keys":[{"kty":"RSA","kid":"host-1","n":"...","e":"AQAB"}]}', when={"field": "auth", "notEquals": "secret"}),
        "scope": K.checks("Scopes", "scope", scopes, option_hook="scope"),
        "kinds": K.text("Kinds and reports it may show (comma separated; a report is report:<id>, every report report:*; empty: everything its users' "
                        "roles open)", "kinds", mono=True, placeholder="trade, counterparty, report:file:report:trades-overview"),
        "callsPerMinute": K.number("Calls per minute", "callsPerMinute", integer=True, minimum=1),
        "userCallsPerMinute": K.number("Calls per user per minute", "userCallsPerMinute", integer=True, minimum=1),
        "tokenSeconds": K.number(f"Token life (seconds, 30–{token_max})", "tokenSeconds", integer=True, minimum=30, maximum=token_max),
        "contact": K.text("Contact (optional)", "contact", maxLength=200, placeholder="crm-team@bank.example"),
    }
    value = {"auth": "secret", "scope": scopes[:1], "callsPerMinute": emb.get("callsPerMinute") or 600,
             "userCallsPerMinute": emb.get("userCallsPerMinute") or 60, "tokenSeconds": min(300, token_max)}
    return K.host(K.schema(props, required=["name", "id", "origins"]), value, attrs={"data-new-form": ""}, label="New host application", **ALONE)


def embedding_secret(**_) -> Markup:
    """Admin › Embedding: the client secret, shown once (the page's script sets it)."""
    return K.host(K.schema({"secret": K.text("Client secret", None, mono=True, hook={"data-secret-value": ""}, readOnly=True)}), {"secret": ""},
                  attrs={"data-secret-fields": ""}, label="Client secret")


def embedding_rotate(**_) -> Markup:
    """Admin › Embedding, Rotate the secret: the old one's grace period."""
    return K.host(K.schema({"grace": K.number("Grace period (seconds)", None, integer=True, hook={"data-rotate-grace": ""}, minimum=0)}), {"grace": 3600},
                  attrs={"data-rotate-fields": ""}, label="Rotate the secret")


def lake_pick(*, domains=(), **_) -> Markup:
    """Admin › Lakes › Maintain's toolbar: one domain's tables at once (every table: the table's own Select all, drishti-grid's row selection)."""
    return K.host(K.schema({"domain": K.select("Select a domain", None, [(d, d) for d in domains], empty="—", hook={"data-mt-domain": ""})}), {"domain": ""},
                  attrs={"class": "mt-picks"}, layout="inline", label="Choose tables")


def lake_settings(*, defaults: dict | None = None, **_) -> Markup:
    """Lakes › Maintain, Settings for the selected tables (a blank keeps the default)."""
    defaults = defaults or {}
    props = {
        "keep-business-days": K.number("Retention (business days; blank keeps every date)", None, integer=True, hook={"data-mt-set": "keep-business-days"},
                                       minimum=1, placeholder="keep all"),
        "target-file-mb": K.number("Target file size (MB)", None, integer=True, hook={"data-mt-set": "target-file-mb"}, minimum=1, maximum=4096,
                                   placeholder=str(defaults.get("target-file-mb", ""))),
        "vacuum-hours": K.number("Vacuum files unused for (hours; blank: no vacuum)", None, integer=True, hook={"data-mt-set": "vacuum-hours"}, minimum=0,
                                 placeholder=str(defaults.get("vacuum-hours", ""))),
        "checkpoint": K.check("Write a checkpoint", None, hook={"data-mt-set": "checkpoint"}),
    }
    return K.host(K.schema(props), {"checkpoint": True}, attrs={"class": "mt-settings"}, layout="columns", label="Settings for the selected tables")


def lake_schedule(*, zone: str = "", **_) -> Markup:
    """Lakes › Maintain, New schedule: at a time (the days, the time and the cron text it writes) or after every build."""
    at_time = {"field": "mode", "equals": "time"}
    props = {
        "name": K.text("Name", None, hook={"data-mt-sname": ""}, placeholder="nightly"),
        "mode": K.radios("When", None, [("time", "At a time"), ("after-build", "After every build of the table")], option_hook="mt-mode"),
        "days": K.checks("Days", None, [(d, d.title()) for d in DAYS], option_hook="mt-day", when=at_time),
        "at": K.time(f"Time ({zone})", None, hook={"data-mt-at": ""}, when=at_time),
        "cron": K.text("Cron text (minute hour day month weekday)", None, mono=True, hook={"data-mt-cron": ""}, when=at_time),
    }
    value = {"mode": "time", "days": ["tue", "wed", "thu", "fri", "sat"], "at": "02:30", "cron": "30 2 * * 2,3,4,5,6"}
    return K.host(K.schema(props), value, attrs={"class": "mt-schedule"}, layout="columns", label="New schedule for the selected tables")


BUILDERS: dict[str, Callable[..., Markup]] = {
    "connector_head": connector_head,
    "net_explain": net_explain, "net_node": net_node,
    "lake_file": lake_file, "lake_fields": lake_fields, "lake_target": lake_target,
    "capacity_growth": capacity_growth, "capacity_download": capacity_download,
    "pack_signature": pack_signature, "pack_data": pack_data,
    "role": role, "user": user, "search": search, "field": field, "access_filter": access_filter, "config_search": config_search,
    "config_propose": config_propose, "connector_filters": connector_filters, "artifact_filters": artifact_filters, "artifact_bi_file": artifact_bi_file,
    "busy_override": busy_override,
    "collab_search": collab_search, "collab_toggle": collab_toggle, "collab_hold": collab_hold, "collab_export": collab_export, "collab_verify": collab_verify,
    "loads_filter": loads_filter, "loads_announce": loads_announce, "loads_config": loads_config,
    "embedding_filter": embedding_filter, "embedding_new": embedding_new, "embedding_secret": embedding_secret, "embedding_rotate": embedding_rotate,
    "lake_pick": lake_pick, "lake_settings": lake_settings, "lake_schedule": lake_schedule}


def admin_form(form: str, /, **kw) -> Markup:
    """The host of an Admin form by name (a Jinja global)."""
    return BUILDERS[form](**kw)
