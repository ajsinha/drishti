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
import re
from pathlib import Path
from typing import Any

import yaml

_PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_]+)(?::([^}]*))?\}")
_ENV_PREFIX = "DRISHTI_CONSOLE__"


class Settings:
    """Immutable-by-convention view over a nested dict with dotted-key access. ``base`` (application.yaml) and ``over`` (the site's own:
    application.local.yaml, environment, command line) are kept apart so a layer can go between them (:meth:`layer`): the edition
    (docs/admin/EDITIONS.md) sits there, so the site's configuration always wins over it, key by key."""

    def __init__(self, data: dict[str, Any], base: dict[str, Any] | None = None, over: dict[str, Any] | None = None):
        self._data = data
        self._base = base if base is not None else copy.deepcopy(data)
        self._over = over if over is not None else {}
        self._layers: dict[str, dict[str, Any]] = {}

    def layer(self, name: str, values: dict[str, Any] | None) -> None:
        """Puts (or, with None, removes) a named layer between the product's defaults and the site's configuration; the order the
        layers were first set is their precedence (later wins). Every reader of :meth:`get` sees the result at once."""
        if values is None:
            self._layers.pop(name, None)
        else:
            self._layers[name] = values
        data = copy.deepcopy(self._base)
        for v in self._layers.values():
            _merge(data, copy.deepcopy(v))
        self._data = _merge(data, copy.deepcopy(self._over))

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


def load_settings(config_dir: Path, argv: list[str] | None = None) -> Settings:
    files: list[dict[str, Any]] = []
    for name in ("application.yaml", "application.local.yaml"):
        path = config_dir / name
        files.append(_interpolate(yaml.safe_load(path.read_text(encoding="utf-8")) or {}) if path.exists() else {})
    base, over = files
    for key, value in os.environ.items():
        if key.startswith(_ENV_PREFIX):
            _set(over, key[len(_ENV_PREFIX):].lower().replace("__", "."), _coerce(value))
    for arg in argv or []:
        if arg.startswith("--") and "=" in arg:
            k, v = arg[2:].split("=", 1)
            _set(over, k, _coerce(v))
    data = _merge(copy.deepcopy(base), copy.deepcopy(over))
    return Settings(data, base, over)
