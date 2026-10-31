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

"""Help → Configuration (``/help/configuration``; docs/admin/CONFIGURATION.md, 2.9.7 C3): the one configuration document, searchable.

The page is the document as the help centre renders it, with the generated half (``Every setting``) made searchable: each settings
row carries its area and words (``data-hay``), a query (``?q=``, words in any order: a key, an environment variable or words of a
meaning) and an area (``?area=``) hide the rows that do not match, server-side and again in the browser as the person types
(``web/static/js/configuration.js``). On a phone each row becomes a card (``help-config.css``): no sideways scroll.

For an administrator, the rows of the server's and the console's ``application.yaml`` gain an *In effect* cell: the value on the
server (``GET /api/v1/admin/settings``) or in this console process, and where it came from. Secrets never show: a key with a word
secret, password, token, key or credential in it (and the known secret settings) shows ``••••``.
"""
from __future__ import annotations

import html
import os
import re
from pathlib import Path
from typing import Any

import yaml

MASK = "••••"
SERVER_AREA = "the-server-applicationyaml"
CONSOLE_AREA = "the-console-applicationyaml"
_SECRET_WORDS = {"secret", "secrets", "password", "passwd", "passphrase", "token", "key", "credential", "credentials", "apikey"}
_KNOWN_SECRETS = {"auth.session_secret", "auth.token_secret", "auth.oidc.client_secret", "bi.python.runner_token"}
_USERINFO = re.compile(r"(//[^/@:\s]+:)[^/@\s]+@")
_PIECE = re.compile(r"(<h3 id=\"[^\"]*\">.*?</h3>|<div class=\"tbl-wrap\"><table class=\"tbl help-tbl\">.*?</table></div>)", re.S)
_ROW = re.compile(r"<tr>(.*?)</tr>", re.S)
_CELL = re.compile(r"<t([hd])([^>]*)>(.*?)</t\1>", re.S)
_TAG = re.compile(r"<[^>]+>")
_PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_]+)(?::[^}]*)?\}")
BEGIN = "<!-- generated: settings begin"
END = "<!-- generated: settings end -->"


def is_secret(key: str) -> bool:
    """The same rule as the server's EffectiveSettingsController: a word of the key (split at . - _ [ ]) is a secret word."""
    if key in _KNOWN_SECRETS:
        return True
    return any(w in _SECRET_WORDS for w in re.split(r"[.\-_\[\]]+", key.lower()))


def shown(key: str, value: Any) -> str:
    """A value as the page shows it: secrets masked (empty stays empty), a URL's password masked, lists joined."""
    if isinstance(value, list):
        value = ", ".join(str(v) for v in value)
    elif isinstance(value, dict):
        value = "{…}" if value else "{}"
    text = "" if value is None else str(value)
    if is_secret(key):
        return MASK if text else ""
    return _USERINFO.sub(lambda m: m.group(1) + MASK + "@", text)


# ---- the console's own settings in effect ------------------------------------------------------------------------------------------

def _flat(node: Any, prefix: str = "") -> dict[str, Any]:
    out: dict[str, Any] = {}
    if isinstance(node, dict) and node:
        for k, v in node.items():
            out.update(_flat(v, f"{prefix}{k}."))
    else:
        out[prefix[:-1]] = node
    return out


def _read(path: Path) -> dict:
    try:
        return yaml.safe_load(path.read_text(encoding="utf-8")) or {} if path.is_file() else {}
    except (OSError, yaml.YAMLError):
        return {}


def console_effective(settings, config_dir: Path, environ: dict | None = None) -> dict[str, dict]:
    """Every key of the console's application.yaml, as this process has it: ``{key: {value, origin, source}}``. The origin is the
    layer that set it: ``env`` (``DRISHTI_CONSOLE__…`` or the placeholder's variable), ``file`` (application.local.yaml), ``argument``,
    ``edition`` (the edition's presets) or ``default`` (application.yaml as shipped)."""
    environ = os.environ if environ is None else environ
    raw = _flat(_read(config_dir / "application.yaml"))
    local = _flat(_read(config_dir / "application.local.yaml"))
    layers = getattr(settings, "_layers", {}) or {}
    out: dict[str, dict] = {}
    for key, bundled in raw.items():
        env_name = "DRISHTI_CONSOLE__" + key.upper().replace(".", "__")
        value = settings.get(key) if settings is not None else None
        if env_name in environ:
            origin, source = "env", env_name
        elif key in local:
            origin, source = "file", "application.local.yaml"
        elif settings is not None and settings.site(key, _Missing) is not _Missing:
            origin, source = "argument", f"--{key}"
        elif any(key in _flat(v) for v in layers.values()):
            origin, source = "edition", next(n for n, v in layers.items() if key in _flat(v))
        else:
            hit = next((m.group(1) for m in _PLACEHOLDER.finditer(str(bundled or "")) if m.group(1) in environ), None)
            origin, source = ("env", hit) if hit else ("default", "application.yaml")
        out[key] = {"value": shown(key, value), "origin": origin, "source": source, "secret": is_secret(key)}
    return out


class _MissingType:
    pass


_Missing = _MissingType()


def server_effective(answer: dict | None) -> dict[str, dict]:
    """``GET /api/v1/admin/settings`` by key (the server has already masked its secrets; masked again here, to be sure)."""
    out = {}
    for row in (answer or {}).get("settings") or []:
        key = str(row.get("key", ""))
        value = row.get("value")
        out[key] = {"value": MASK if is_secret(key) and value else shown(key, value), "origin": row.get("origin", ""),
                    "source": row.get("source", ""), "secret": bool(row.get("secret")) or is_secret(key)}
    return out


# ---- the page --------------------------------------------------------------------------------------------------------------------

def _text(cell: str) -> str:
    return html.unescape(_TAG.sub(" ", cell))


def _words(q: str) -> list[str]:
    return [w for w in re.split(r"\s+", (q or "").lower().strip()) if w]


def areas(page_html: str) -> list[dict]:
    """The areas of the generated half, in order: ``{id, title}`` (the area filter's options)."""
    start = page_html.find(BEGIN)
    if start < 0:
        return []
    out = []
    for m in re.finditer(r"<h3 id=\"([^\"]*)\">(.*?)</h3>", page_html[start:], re.S):
        out.append({"id": m.group(1), "title": _text(m.group(2)).strip()})
    return out


def _effective_cell(eff: dict | None, default_text: str) -> str:
    if eff is None:
        return '<td data-label="In effect" class="cfg-eff cfg-none">not set</td>'
    value = eff.get("value", "")
    shown_value = html.escape(value) if value != "" else "<em>empty</em>"
    differs = " cfg-differs" if eff.get("origin") not in ("default",) else ""
    origin = html.escape(f"{eff.get('origin', '')}: {eff.get('source', '')}".strip(": "))
    return (f'<td data-label="In effect" class="cfg-eff{differs}"><code>{shown_value}</code>'
            f'<span class="cfg-origin">{origin}</span></td>')


def searchable(page_html: str, q: str = "", area: str = "", server: dict | None = None, console: dict | None = None) -> tuple[str, int, int]:
    """The rendered document with its settings tables made searchable (and filtered by ``q`` and ``area``): returns the HTML, the
    rows that match and the rows in all. ``server`` and ``console`` (administrators only) add the In effect cells."""
    start = page_html.find(BEGIN)
    if start < 0:
        return page_html, 0, 0
    head, gen = page_html[:start], page_html[start:]
    words = _words(q)
    state = {"area": "", "shown": 0, "total": 0}

    def table(m: re.Match) -> str:
        block = m.group(0)
        if block.startswith("<h3"):
            state["area"] = re.search(r'id="([^"]*)"', block).group(1)
            return block
        rows = _ROW.findall(block)
        heads = [_text(c[2]).strip() for c in _CELL.findall(rows[0])] if rows else []
        if not heads or heads[0] not in ("Key", "Variable"):
            return block                                                   # the areas table, not settings
        a = state["area"]
        effective = server if a == SERVER_AREA else console if a == CONSOLE_AREA else None
        out_rows, any_shown = [], False
        for i, row in enumerate(rows):
            cells = _CELL.findall(row)
            if i == 0:
                extra = "<th>In effect</th>" if effective is not None else ""
                out_rows.append("<tr>" + row + extra + "</tr>")
                continue
            key = _text(cells[0][2]).strip()
            hay = re.sub(r"\s+", " ", " ".join(_text(c[2]) for c in cells).lower() + " " + a.replace("-", " ")).strip()
            match = all(w in hay for w in words) and (not area or area == a)
            state["total"] += 1
            state["shown"] += match
            any_shown |= match
            tds = "".join(f'<td data-label="{html.escape(heads[j] if j < len(heads) else "")}"{c[1]}>{c[2]}</td>' for j, c in enumerate(cells))
            if effective is not None:
                default_text = _text(cells[2][2]).strip() if len(cells) > 2 else ""
                tds += _effective_cell(effective.get(key), default_text)
            out_rows.append(f'<tr class="cfg-row" data-area="{html.escape(a)}" data-hay="{html.escape(hay)}"'
                            f'{"" if match else " hidden"}>{tds}</tr>')
        cls = "tbl help-tbl cfg-tbl" + (" cfg-has-eff" if effective is not None else "")
        body = "<thead>" + out_rows[0] + "</thead><tbody>" + "".join(out_rows[1:]) + "</tbody>"
        hidden = "" if any_shown else " data-cfg-empty"
        return f'<div class="tbl-wrap cfg-wrap"{hidden}><table class="{cls}" data-plain>{body}</table></div>'

    state["per"] = {}
    gen = _PIECE.sub(lambda m: _count(m, table, state), gen)
    filtered = bool(words or area)
    cuts = [m.start() for m in re.finditer(r'<h3 id="', gen)]
    end = gen.find(END)
    end = end if end >= 0 else len(gen)
    parts = [f'<div class="cfg-intro"{" hidden" if filtered else ""}>{gen[:cuts[0] if cuts else end]}</div>']
    for i, at in enumerate(cuts):
        stop = cuts[i + 1] if i + 1 < len(cuts) else end
        a = re.match(r'<h3 id="([^"]*)"', gen[at:]).group(1)
        hide = filtered and not state["per"].get(a)
        parts.append(f'<section class="cfg-area" data-area="{html.escape(a)}"{" hidden" if hide else ""}>{gen[at:stop]}</section>')
    parts.append(gen[end:])
    head = f'<div class="cfg-explain"{" hidden" if filtered else ""}>{head}</div>'
    return head + f'<div class="cfg-ref{" cfg-filtered" if filtered else ""}">{"".join(parts)}</div>', state["shown"], state["total"]


def _count(m: re.Match, table, state: dict) -> str:
    before = state["shown"]
    out = table(m)
    if state["shown"] > before:
        state["per"][state["area"]] = state["per"].get(state["area"], 0) + state["shown"] - before
    return out
