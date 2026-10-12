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
from core.ai.providers import catalog
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


_TLS_SECRETS = ("tls.truststore-password", "tls.keystore-password", "tls.key-password")


def resolve_secret(name: str, raw: str, env, required: bool = False) -> str:
    """A secret setting: an ${ENV_NAME} placeholder, resolved here; a literal is refused. ``required``: an unset variable without a default is an error naming it."""
    m = _ENV.match(raw.strip())
    if not m:
        raise LlmError("config", f"{name} must be an ${{ENV_NAME}} placeholder; secrets are never written into a file")
    v = env(m.group(1))
    if v and v.strip():
        return v.strip()
    if m.group(2) is None and required:
        raise LlmError("config", f"{name}: the environment variable {m.group(1)} is not set")
    return m.group(2) or ""


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
    base_url, api_key, default_model = str(data.get("base-url") or "").strip(), resolve_key(str(data.get("api-key") or ""), env), str(data.get("default-model") or "").strip()
    for k in catalog.CREDENTIAL_SETTINGS:
        if settings.get(k):
            settings[k] = resolve_secret(k, settings[k], env)
    resolve_tls_secrets(settings, env)
    pre = catalog.preset(typ)
    if pre:
        base_url, api_key, models, default_model = _preset_defaults(typ, pre, settings, models, base_url, api_key, default_model, env)
    return ProviderConfig(name=name, type=typ, enabled=bool(data.get("enabled", True)), base_url=base_url,
                          api_key=api_key, timeout=_duration(data.get("timeout"), 60.0),
                          retries=int(data.get("retries") or 0), default_model=default_model, settings=settings,
                          models=tuple(models))


def resolve_tls_secrets(settings: dict, env) -> None:
    if settings.get("tls.insecure-trust-all", "").lower() == "true":
        if (env("DRISHTI_ALLOW_INSECURE_TLS") or "").lower() != "true":
            raise LlmError("config", "tls.insecure-trust-all is refused unless DRISHTI_ALLOW_INSECURE_TLS=true is in the environment")
        settings["tls.insecure-allowed"] = "true"
    for k in _TLS_SECRETS:
        if settings.get(k):
            settings[k] = resolve_secret(k, settings[k], env, required=True)


def _preset_defaults(typ, pre, settings, models, base_url, api_key, default_model, env):
    """What a preset type fills in when the file leaves it out: the base URL (or ``base-url-env``), the key from the vendor's environment variable, the catalogue
    models (a model of the file with the same name wins; ``catalogue: false`` keeps only the file's), the vendor's settings-from-environment and the default model."""
    base_url = base_url or catalog.first_env(pre.get("base-url-env"), env) or pre["base-url"]
    api_key = api_key or catalog.first_env(pre.get("key-env"), env)
    for k, names in (pre.get("settings-env") or {}).items():
        if not settings.get(k) and catalog.first_env(names, env):
            settings[k] = catalog.first_env(names, env)
    have = {m["name"] for m in models}
    if settings.get("catalogue", "true").lower() != "false":
        models = list(models) + [m for m in catalog.catalog_models(typ) if m["name"] not in have]
    if typ == "azure_openai":                                    # deployments.<model id>: <deployment name> are this resource's models
        have = {m["name"] for m in models}
        for k in sorted(k for k in settings if k.startswith("deployments.")):
            mid = k[len("deployments."):]
            if catalog.logical_name(mid) not in have:
                models.append({"name": catalog.logical_name(mid), "id": mid, "kind": "embedding" if "embed" in mid else "chat", "max_tokens": None, "temperature": None,
                               "capabilities": ("embeddings",) if "embed" in mid else ("chat", "tool-calls", "json-mode"), "cost_per_1k_input": 0.0, "cost_per_1k_output": 0.0})
    default_model = default_model or catalog.default_model(typ)
    if default_model and default_model not in {m["name"] for m in models}:
        default_model = ""
    return base_url, api_key, models, default_model


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
    resolve_tls_secrets(settings, env)
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
