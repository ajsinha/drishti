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

"""The Admin screens' forms as drishti-inputs draws them (docs/architecture/ELEMENTS.md section 12.3): the server builds each form's schema
here and the template hands it to the page in a ``data-di-form`` attribute (``admin_form(name, ...)``, a template global); drishti-inputs'
``formFromData`` draws it and the page's script adds what it does on submit. Every control keeps the name and the data hook it had, so the
routes, the tests and the tutorials find the same fields. A form inside a page's own GET form (the loads filter) is embedded: its named
fields are what that form submits."""
from __future__ import annotations

from core.form_schema import check, date, form_json, number, obj, select, text

DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
ACTIONS = {"resetButton": False, "doneText": "", "layout": "columns"}


def _plain(title: str, name: str, hook=None, **more) -> dict:
    """A line of plain text (no monospace face) with no browser autocomplete."""
    return text(title, name, hook or {"spellcheck": "false"}, **more)


# ---- Admin > Collaboration ---------------------------------------------------------------------------------------------------------
def collab_search() -> str:
    return form_json(obj({"user": _plain("User", "user"), "kind": _plain("Kind", "kind"), "id": _plain("Id", "id"),
                          "from": date("From", "from"), "to": date("To", "to")}), label="Find threads", submitLabel="Search", **ACTIONS)


def collab_toggle(title: str, hook: str) -> str:
    """One checkbox that filters a list at once (Only hidden comments, Active holds only)."""
    return form_json(obj({"on": check(title, None, {hook: ""})}), label=title, live=True)


def collab_hold() -> str:
    scope = select("Scope", "scope", [("entity", "one entity"), ("kind", "a kind"), ("user", "a user"), ("thread", "a thread"), ("all", "everything")],
                   {"data-acol-scope": ""})
    return form_json(obj({
        "scope": scope,
        "kind": _plain("Kind", "kind", when={"field": "scope", "in": ["entity", "kind"]}),
        "id": _plain("Id", "id", when={"field": "scope", "equals": "entity"}),
        "user": _plain("User", "user", when={"field": "scope", "equals": "user"}),
        "thread": _plain("Thread", "thread", when={"field": "scope", "equals": "thread"}),
        "from": date("From", "from"), "to": date("To", "to"),
        "reason": _plain("Reason", "reason", maxLength=400, description="Required: why the hold is placed (kept in the record)."),
    }, required=["reason"]), {"scope": "entity"}, label="Place a legal hold", submitLabel="Place hold", **ACTIONS)


def collab_export() -> str:
    return form_json(obj({"from": date("From", "from"), "to": date("To", "to"), "kind": _plain("Kind", "kind"), "id": _plain("Id", "id"),
                          "user": _plain("User", "user"), "includeShares": check("Shares", "includeShares"),
                          "includeThreads": check("Threads", "includeThreads")}),
                     {"includeShares": True, "includeThreads": True}, label="Compliance export", submitLabel="Start export", **ACTIONS)


def collab_verify() -> str:
    return form_json(obj({"thread": text("Thread", "thread", placeholder="th_...")}), label="Verify the chains", submitLabel="Verify", **ACTIONS)


# ---- Admin > Packs > Data loads ------------------------------------------------------------------------------------------------------
def loads_filter(kinds: list, kind: str = "", status: str = "", day: str = "") -> str:
    """The history's filter, embedded in the page's GET form (kind, status and date as before)."""
    return form_json(obj({
        "kind": select("Kind", "kind", [("", "all")] + [(k, k) for k in kinds], {"data-filter-kind": ""}),
        "status": select("Status", "status", [("", "all"), ("ready", "ready"), ("failed", "failed")]),
        "date": date("Date", "date"),
    }), {"kind": kind or "", "status": status or "", **({"date": day} if day else {})}, label="Filter the loads", embedded=True, layout="columns")


def loads_announce(kinds: list) -> str:
    return form_json(obj({
        "kind": select("Kind", "kind", [(k, k) for k in kinds]),
        "businessDate": date("Business date", "businessDate"),
        "status": select("Status", "status", [("ready", "ready"), ("failed", "failed")]),
        "rows": number("Rows (optional)", "rows", minimum=0),
        "batchId": _plain("Batch id (optional)", "batchId", maxLength=100),
        "note": _plain("Note (optional)", "note", maxLength=500),
    }, required=["kind", "businessDate"]), {"kind": kinds[0] if kinds else "", "status": "ready"}, label="Announce a load", actions=False)


def loads_config(kinds: list, config: dict) -> str:
    """The expectations settings: who is told, the smoke test, and each expected kind as a repeatable row."""
    notify = config.get("notify") or {}
    expect = [{"kind": k, "by": e.get("by") or "19:00", "zone": e.get("zone") or "", "calendar": e.get("calendar") or ""}
              for k, e in (config.get("expect") or {}).items()]
    row = obj({"kind": select("Kind", "x-kind", [(k, k) for k in kinds], {"data-x-kind": ""}),
               "by": text("Due by (HH:mm)", "x-by", {"data-x-by": ""}, pattern="[0-2][0-9]:[0-5][0-9]", patternMessage="a time such as 19:00", default="19:00"),
               "zone": _plain("Time zone", "x-zone", {"data-x-zone": ""}, placeholder="server zone"),
               "calendar": _plain("Calendar", "x-cal", {"data-x-cal": ""}, placeholder="server calendar")}, title="Expected kind", required=["kind", "by"])
    return form_json(obj({
        "roles": _plain("Tell these roles (comma separated)", "roles"),
        "users": _plain("and these users", "users"),
        "email": check("also send an email (needs the email channel)", "email"),
        "smoke": number("Smoke test: open this many sample entities after each ready load (0 = off)", "smoke", minimum=0),
        "expect": {"type": "array", "title": "Expected each business day", "items": row, "hook": {"data-expect-rows": ""},
                   "itemHook": {"data-expect-row": ""}, "addHook": {"data-expect-add": ""}, "addLabel": "Add a kind"},
    }), {"roles": ", ".join(notify.get("roles") or []), "users": ", ".join(notify.get("users") or []), "email": bool(notify.get("email")),
         "smoke": config.get("smoke") or 0, "expect": expect}, label="Expectations settings", actions=False)


# ---- Admin > Embedding ---------------------------------------------------------------------------------------------------------------
def embedding_filter() -> str:
    return form_json(obj({"q": _plain("Filter the applications", "q", {"data-filter": ""}, placeholder="Filter by name, id or origin")}),
                     label="Filter the applications", live=True)


def embedding_new(emb: dict) -> str:
    scopes = emb.get("scopes") or ["embed:view"]
    token_max = emb.get("tokenMaxSeconds") or 900
    wild = " https://*.suffix is allowed." if emb.get("allowWildcardOrigins") else ""
    return form_json(obj({
        "name": _plain("Name", "name", maxLength=100, placeholder="Client CRM"),
        "id": text("Id (lower case letters, digits, hyphens)", "id", maxLength=32, pattern="[a-z][a-z0-9\\-]{1,31}",
                   patternMessage="a lower-case letter, then letters, digits or hyphens (2 to 32)", placeholder="client-crm"),
        "origins": text("Origins, one per line (scheme, host, optional port; no path)", "origins", format="textarea", options={"rows": 3},
                        placeholder="https://crm.bank.example", description=wild.strip()),
        "auth": select("How the application signs in", "auth", [("secret", "Client secret (made now, shown once)"),
                                                                ("key", "Public key: a JWK set; the application signs its requests"), ("both", "Both")],
                       widget="radio", optionHook="auth"),
        "jwks": text("JWK set (JSON, public keys only)", "jwks", {"class": "mono", "data-jwks-field": ""}, format="textarea", options={"rows": 5},
                     placeholder='{"keys":[{"kty":"RSA","kid":"host-1","n":"...","e":"AQAB"}]}', when={"field": "auth", "notEquals": "secret"}),
        "scope": {"type": "array", "title": "Scopes", "name": "scope", "widget": "checkboxes", "optionHook": "scope",
                  "items": {"type": "string", "enum": scopes}},
        "kinds": text("Kinds and reports it may show (comma separated; a report is report:<id>, every report report:*; empty: everything its users' "
                      "roles open)", "kinds", placeholder="trade, counterparty, report:file:report:trades-overview"),
        "callsPerMinute": number("Calls per minute", "callsPerMinute", minimum=1),
        "userCallsPerMinute": number("Calls per user per minute", "userCallsPerMinute", minimum=1),
        "tokenSeconds": number(f"Token life (seconds, 30–{token_max})", "tokenSeconds", minimum=30, maximum=token_max),
        "contact": _plain("Contact (optional)", "contact", maxLength=200, placeholder="crm-team@bank.example"),
    }, required=["name", "id", "origins"]), {"auth": "secret", "scope": scopes[:1], "callsPerMinute": emb.get("callsPerMinute") or 600,
                                             "userCallsPerMinute": emb.get("userCallsPerMinute") or 60, "tokenSeconds": min(300, token_max)},
        label="New host application", actions=False)


def embedding_secret() -> str:
    return form_json(obj({"secret": text("Client secret", None, {"class": "mono", "data-secret-value": ""}, readOnly=True)}), label="Client secret", live=True)


def embedding_rotate() -> str:
    return form_json(obj({"grace": number("Grace period (seconds)", None, {"data-rotate-grace": ""}, minimum=0)}), {"grace": 3600},
                     label="Rotate the secret", live=True)


# ---- Admin > Lakes: Maintain ---------------------------------------------------------------------------------------------------------
def lake_pick(domains: list) -> str:
    """The toolbar's choices: every table, or one domain's."""
    return form_json(obj({"all": check("Select all", None, {"data-mt-all": ""}),
                          "domain": select("Select a domain", None, [("", "—")] + [(d, d) for d in domains], {"data-mt-domain": ""})}),
                     label="Choose tables", live=True, layout="columns")


def lake_settings(defaults: dict) -> str:
    return form_json(obj({
        "keep-business-days": number("Retention (business days; blank keeps every date)", None, {"data-mt-set": "keep-business-days"}, minimum=1,
                                     placeholder="keep all"),
        "target-file-mb": number("Target file size (MB)", None, {"data-mt-set": "target-file-mb"}, minimum=1, maximum=4096,
                                 placeholder=str(defaults.get("target-file-mb", ""))),
        "vacuum-hours": number("Vacuum files unused for (hours; blank: no vacuum)", None, {"data-mt-set": "vacuum-hours"}, minimum=0,
                               placeholder=str(defaults.get("vacuum-hours", ""))),
        "checkpoint": check("Write a checkpoint", None, {"data-mt-set": "checkpoint"}),
    }), {"checkpoint": True}, label="Settings for the selected tables", live=True, layout="columns")


def lake_schedule(zone: str) -> str:
    return form_json(obj({
        "name": _plain("Name", None, {"data-mt-sname": ""}, placeholder="nightly"),
        "mode": select("When", None, [("time", "At a time"), ("after-build", "After every build of the table")], widget="radio", optionHook="mt-mode"),
        "days": {"type": "array", "title": "Days", "widget": "checkboxes", "optionHook": "mt-day", "when": {"field": "mode", "equals": "time"},
                 "items": {"type": "string", "enum": DAYS, "enumLabels": [d.title() for d in DAYS]}},
        "at": {"type": "time", "title": f"Time ({zone})", "hook": {"data-mt-at": ""}, "when": {"field": "mode", "equals": "time"}},
        "cron": text("Cron text (minute hour day month weekday)", None, {"class": "mono", "data-mt-cron": ""}, when={"field": "mode", "equals": "time"}),
    }), {"mode": "time", "days": ["tue", "wed", "thu", "fri", "sat"], "at": "02:30", "cron": "30 2 * * 2,3,4,5,6"},
        label="New schedule for the selected tables", live=True, layout="columns")


FORMS = {
    "collab.search": collab_search, "collab.toggle": collab_toggle, "collab.hold": collab_hold, "collab.export": collab_export, "collab.verify": collab_verify,
    "loads.filter": loads_filter, "loads.announce": loads_announce, "loads.config": loads_config,
    "embedding.filter": embedding_filter, "embedding.new": embedding_new, "embedding.secret": embedding_secret, "embedding.rotate": embedding_rotate,
    "lake.pick": lake_pick, "lake.settings": lake_settings, "lake.schedule": lake_schedule,
}


def admin_form(name: str, *args, **kwargs) -> str:
    """A template global: the ``data-di-form`` JSON of one Admin form (``{{ admin_form('loads.announce', config.kinds) }}``)."""
    return FORMS[name](*args, **kwargs)
