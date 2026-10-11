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

"""Reads ``config/ai/*.yaml`` (one provider per file) and ``config/mcp/*.yaml`` (one MCP server per file) with the server's rules: a bad file is
reported and skipped; a credential must be an ``${ENV_NAME}`` placeholder, never a literal."""
from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Callable

import yaml

from core.ai import proxy as proxy_mod
from core.ai.types import CAPABILITIES, LlmError, ProviderConfig

_ENV = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-?([^}]*))?\}$")
_EMBEDDED = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-?([^}]*))?\}")
_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
_PROVIDER_KEYS = {"name", "type", "enabled", "base-url", "api-key", "timeout", "retries", "default-model", "models"}
_MCP_KEYS = {"name", "transport", "enabled", "url", "headers", "timeout", "allowed-tools"}
_CREDENTIAL = re.compile(r"(?i).*(auth|key|token|secret|cookie).*")


def env_getter() -> Callable[[str], str | None]:
    return os.environ.get


def resolve_key(raw: str, env) -> str:
    if not raw:
        return ""
    m = _ENV.match(raw.strip())
    if not m:
        raise LlmError("config", "api-key must be an ${ENV_NAME} placeholder; keys are never written into a file")
    v = env(m.group(1))
    return v.strip() if v and v.strip() else (m.group(2) or "")


def resolve_header(name: str, raw: str, env) -> str:
    if "${" not in raw:
        if _CREDENTIAL.match(name) and raw.strip():
            raise LlmError("config", f"header {name} must use an ${{ENV_NAME}} placeholder; credentials are never written into a file")
        return raw
    return _EMBEDDED.sub(lambda m: (env(m.group(1)) or "").strip() or (m.group(2) or ""), raw)


def _duration(v, default: float) -> float:
    if v is None or str(v).strip() == "":
        return default
    t = str(v).strip().lower()
    try:
        if t.endswith("ms"):
            return int(t[:-2]) / 1000
        if t.endswith("m"):
            return int(t[:-1]) * 60
        return float(t[:-1] if t.endswith("s") else t)
    except ValueError:
        raise LlmError("config", f"timeout '{v}' is not a duration such as 30s") from None


def flatten(prefix: str, v, out: dict) -> None:
    if isinstance(v, dict):
        for k, x in v.items():
            flatten(f"{prefix}.{k}", x, out)
    elif isinstance(v, list):
        for i, x in enumerate(v):
            flatten(f"{prefix}.{i}", x, out)
    elif v is not None:
        out[prefix] = str(v).lower() if isinstance(v, bool) else str(v)


def _read(path: Path) -> dict:
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError):
        raise LlmError("config", "not readable YAML") from None
    if not isinstance(data, dict):
        raise LlmError("config", "the file is empty or not a mapping")
    return data


def _files(directory: Path | str | None) -> list[Path]:
    d = Path(directory) if directory else None
    if d is None or not d.is_dir():
        return []
    return sorted(p for p in d.iterdir() if p.is_file() and p.suffix in (".yaml", ".yml") and not p.name.startswith("."))


def parse_provider(path: Path, env) -> ProviderConfig:
    return provider_from_dict(_read(path), path.stem, env)


def provider_from_dict(data: dict, default_name: str, env) -> ProviderConfig:
    """One provider from its parsed block: a file's content, or the ``config`` that ``LLMProvider.from_settings`` is given."""
    name = str(data.get("name") or default_name).strip()
    if not _NAME.match(name):
        raise LlmError("config", f"name '{name}' must be letters, digits, '.', '_' or '-'")
    typ = str(data.get("type") or "").strip()
    if not typ:
        raise LlmError("config", "type is missing (openai, anthropic, server, mock, ...)")
    settings: dict = {}
    for k, v in data.items():
        if k not in _PROVIDER_KEYS:
            flatten(k, v, settings)
    for k in [k for k in settings if k.startswith("header.")]:
        settings[k] = resolve_header(k[7:], settings[k], env)
    proxy_mod.check_password(settings, env)
    models, seen = [], set()
    for m in data.get("models") or []:
        mn = str((m or {}).get("name") or "").strip()
        if not _NAME.match(mn):
            raise LlmError("config", "a model needs a logical name (letters, digits, '.', '_', '-')")
        if mn in seen:
            raise LlmError("config", f"model '{mn}' is listed twice")
        seen.add(mn)
        caps = tuple(str(c) for c in m.get("capabilities") or [])
        for c in caps:
            if c not in CAPABILITIES:
                raise LlmError("config", f"model '{mn}': unknown capability '{c}'")
        models.append({"name": mn, "id": str(m.get("id") or mn), "kind": str(m.get("kind") or "chat").lower(), "max_tokens": m.get("max-tokens"),
                       "temperature": m.get("temperature"), "capabilities": caps, "cost_per_1k_input": float(m.get("cost-per-1k-input") or 0),
                       "cost_per_1k_output": float(m.get("cost-per-1k-output") or 0)})
    return ProviderConfig(name=name, type=typ, enabled=bool(data.get("enabled", True)), base_url=str(data.get("base-url") or "").strip(),
                          api_key=resolve_key(str(data.get("api-key") or ""), env), timeout=_duration(data.get("timeout"), 60.0),
                          retries=int(data.get("retries") or 0), default_model=str(data.get("default-model") or "").strip(), settings=settings,
                          models=tuple(models))


def load_providers(directory, env=None) -> tuple[list[ProviderConfig], dict[str, str]]:
    env = env or env_getter()
    ok, errors, seen = [], {}, set()
    for f in _files(directory):
        try:
            c = parse_provider(f, env)
            if c.name in seen:
                errors[f.name] = f"another file already defines provider '{c.name}'"
            else:
                seen.add(c.name)
                ok.append(c)
        except LlmError as e:
            errors[f.name] = e.message
    return ok, errors


def parse_mcp(path: Path, env) -> dict:
    data = _read(path)
    name = str(data.get("name") or path.stem).strip()
    if not _NAME.match(name):
        raise LlmError("config", f"name '{name}' must be letters, digits, '.', '_' or '-'")
    settings: dict = {}
    for k, v in data.items():
        if k not in _MCP_KEYS:
            flatten(k, v, settings)
    headers = {str(k): resolve_header(str(k), str(v), env) for k, v in (data.get("headers") or {}).items()}
    proxy_mod.check_password(settings, env)
    return {"name": name, "transport": str(data.get("transport") or "streamable-http"), "enabled": bool(data.get("enabled", True)),
            "url": str(data.get("url") or "").strip(), "headers": headers, "timeout": _duration(data.get("timeout"), 30.0),
            "allowed_tools": [str(t) for t in data.get("allowed-tools") or []], "settings": settings}


def load_mcp(directory, env=None) -> tuple[list[dict], dict[str, str]]:
    env = env or env_getter()
    ok, errors, seen = [], {}, set()
    for f in _files(directory):
        try:
            c = parse_mcp(f, env)
            if c["name"] in seen:
                errors[f.name] = f"another file already defines server '{c['name']}'"
            else:
                seen.add(c["name"])
                ok.append(c)
        except LlmError as e:
            errors[f.name] = e.message
    return ok, errors
