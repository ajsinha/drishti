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

"""Layered configuration: YAML -> (the server's branding, the edition: :meth:`Settings.layer`) -> local YAML -> environment -> command line.

``${VAR:default}`` placeholders in YAML values are resolved from the environment.
Environment overrides use ``DRISHTI_CONSOLE__A__B=value`` for key ``a.b``.
"""
from __future__ import annotations

import copy
import os
import sys
import re
from pathlib import Path
from typing import Any

import yaml

from core import settings_ini

_PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_]+)(?::([^}]*))?\}")
_ENV_PREFIX = "DRISHTI_CONSOLE__"


class Settings:
    """Immutable-by-convention view over a nested dict with dotted-key access. ``base`` (application.yaml) and ``over`` (the site's own:
    application.local.yaml, environment, command line) are kept apart so a layer can go between them (:meth:`layer`): the edition
    (docs/guides/ADMIN_GUIDE.md) sits there, so the site's configuration always wins over it, key by key."""

    def __init__(self, data: dict[str, Any], base: dict[str, Any] | None = None, over: dict[str, Any] | None = None):
        self._data = data
        self._base = base if base is not None else copy.deepcopy(data)
        self._over = over if over is not None else {}
        self._layers: dict[str, dict[str, Any]] = {}
        self._pins: dict[str, dict[str, Any]] = {}

    def pin(self, name: str, values: dict[str, Any] | None) -> None:
        """A named layer above even the site's configuration: for what only the distribution file may say (the product's name and notices);
        a site's own ``ui.product`` and the like are not read."""
        if values is None:
            self._pins.pop(name, None)
        else:
            self._pins[name] = values
        self._compose()

    def _compose(self) -> None:
        data = copy.deepcopy(self._base)
        for v in self._layers.values():
            _merge(data, copy.deepcopy(v))
        data = _merge(data, copy.deepcopy(self._over))
        for v in self._pins.values():
            _merge(data, copy.deepcopy(v))
        self._data = data

    def layer(self, name: str, values: dict[str, Any] | None) -> None:
        """Puts (or, with None, removes) a named layer between the product's defaults and the site's configuration; the order the
        layers were first set is their precedence (later wins). Every reader of :meth:`get` sees the result at once."""
        if values is None:
            self._layers.pop(name, None)
        else:
            self._layers[name] = values
        self._compose()

    def site(self, key: str, default: Any = None) -> Any:
        """A key as the site's own configuration sets it (application.local.yaml, environment, command line), or ``default``."""
        node: Any = self._over
        for part in key.split("."):
            if not isinstance(node, dict) or part not in node:
                return default
            node = node[part]
        return node

    def get(self, key: str, default: Any = None) -> Any:
        node: Any = self._data
        for part in key.split("."):
            if not isinstance(node, dict) or part not in node:
                return default
            node = node[part]
        return node

    def as_dict(self) -> dict[str, Any]:
        return self._data


def _interpolate(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: _interpolate(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_interpolate(v) for v in value]
    if isinstance(value, str):
        return _coerce(_PLACEHOLDER.sub(lambda m: os.environ.get(m.group(1), m.group(2) or ""), value))
    return value


def _coerce(text: str) -> Any:
    if text.lower() in ("true", "false"):
        return text.lower() == "true"
    try:
        return int(text)
    except ValueError:
        pass
    try:
        return float(text)
    except ValueError:
        return text


def _set(data: dict[str, Any], dotted: str, value: Any) -> None:
    parts = dotted.split(".")
    node = data
    for part in parts[:-1]:
        node = node.setdefault(part, {})
    node[parts[-1]] = value


def _merge(base: dict[str, Any], over: dict[str, Any]) -> dict[str, Any]:
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            _merge(base[k], v)
        else:
            base[k] = v
    return base


def _read_settings_file(named: str) -> dict[str, str]:
    """The console's entries of the optional settings files (core/settings_ini.py, the server's format). The same files serve the server,
    so the console takes only the keys under ``console.`` and drops that prefix (``[console.server]`` ``port=17480`` is the console's
    ``server.port``); every other key is the server's and is ignored here."""
    try:
        entries = settings_ini.read_all(named)
    except settings_ini.SettingsError as e:
        raise SystemExit(settings_ini.fail(e)) from None
    return {k[len("console."):]: v for k, v in entries.items() if k.startswith("console.")}


#: the keys the console shares with the server in a settings file (a profile): each fills its environment variable when that is unset, so
#: the console and the server started on the same files agree (the data folder, the install, the distribution)
SHARED = {"drishti.home": "DRISHTI_HOME", "drishti.data.dir": "DRISHTI_DATA_DIR", "drishti.distribution": "DRISHTI_DISTRIBUTION",
          "drishti.site.dir": "DRISHTI_SITE_DIR"}
#: the launch roots among them: the settings file BEATS the environment (the Java order, LaunchRoots; the tools' settingsfile.apply_roots),
#: so a stale DRISHTI_HOME / DRISHTI_DATA_DIR of an old setx never sends the console to another folder; the old value is warned about
ROOT_KEYS = ("drishti.home", "drishti.data.dir")


def local_file(config_dir: Path) -> Path:
    """The console's own site file (``application.local.yaml``), the ONE place it is read and written: ``<site>/console/application.local.yaml``
    when the install names a site folder (``drishti.site.dir`` / ``DRISHTI_SITE_DIR``: production, the release folder is never written), else
    ``<console>/config/application.local.yaml`` (the developer layout, where the site folder is the configuration folder)."""
    site = (os.environ.get("DRISHTI_SITE_DIR") or "").strip()
    if site:
        return Path(site) / "console" / "application.local.yaml"
    return Path(config_dir) / "application.local.yaml"


def _shared(named: str) -> dict[str, Any]:
    """The server's keys the console reads too (``SHARED``, into the environment where unset) and the server's address the files give
    (``console.backend.url``, else a profile's ``server.port``), as the console's ``backend.url`` where DRISHTI_BACKEND_URL is unset."""
    try:
        entries = settings_ini.read_all(named)
    except settings_ini.SettingsError as e:
        raise SystemExit(settings_ini.fail(e)) from None
    home =(str(entries.get("drishti.home") or "").strip() or (os.environ.get("DRISHTI_HOME") or "").strip()
            or str(Path(__file__).resolve().parents[2]))
    for key, var in SHARED.items():
        value = str(entries.get(key) or "").strip()
        if not value:
            continue
        value = settings_ini.home_value(value, home)
        old = (os.environ.get(var) or "").strip()
        if key in ROOT_KEYS and old and old != value:
            print(f"WARNING: {var}={old} is ignored: your settings file wins ({key}={value}); you may delete the variable",
                  file=sys.stderr)
            os.environ[var] = value
        elif not old:
            os.environ[var] = value
    url = settings_ini.server_url(entries)
    out: dict[str, Any] = {"backend": {"url": url}} if url and not (os.environ.get("DRISHTI_BACKEND_URL") or "").strip() else {}
    profile = str(entries.get("drishti.profile") or "").strip()      # the one profile name of the server and the console (production.py)
    if profile and not (os.environ.get("DRISHTI_PROFILE") or "").strip():
        out["profile"] = profile
    tls = str(entries.get("drishti.production.tls") or "").strip()
    if tls and not (os.environ.get("DRISHTI_PRODUCTION_TLS") or "").strip():
        out.setdefault("production", {})["tls"] = tls
    return out


def load_settings(config_dir: Path, argv: list[str] | None = None) -> Settings:
    if not Path(config_dir).is_dir():   # a file named instead of its folder read no file at all, and every setting fell to its default
        raise ValueError(f"load_settings takes the console's config folder, not {config_dir}")
    try:
        argv, settings_path = settings_ini.pop_option(list(argv or []))
        # --profile demo|samples|qa: the install's committed settings file (config/profiles/<name>.ini), after --settings
        argv, profiles = settings_ini.pop_profile(argv)
        settings_path = settings_ini.with_profiles(settings_path, Path(config_dir).resolve().parents[1], profiles)
    except settings_ini.SettingsError as e:
        raise SystemExit(settings_ini.fail(e)) from None
    shared =_shared(settings_path) if settings_path else {}
    files: list[dict[str, Any]] = []
    for path in (config_dir / "application.yaml", local_file(config_dir)):
        files.append(_interpolate(yaml.safe_load(path.read_text(encoding="utf-8")) or {}) if path.exists() else {})
    base, over = files
    if settings_path:                       # --settings /abs/drishti.ini: the file's keys sit below the environment and the command line
        _merge(over, shared)
        for k, v in _read_settings_file(settings_path).items():
            _set(over, k, _coerce(v))
    for key, value in os.environ.items():
        if key.startswith(_ENV_PREFIX):
            _set(over, key[len(_ENV_PREFIX):].lower().replace("__", "."), _coerce(value))
    for arg in argv or []:
        if arg.startswith("--") and "=" in arg:
            k, v = arg[2:].split("=", 1)
            _set(over, k, _coerce(v))
    data = _merge(copy.deepcopy(base), copy.deepcopy(over))
    return Settings(data, base, over)
