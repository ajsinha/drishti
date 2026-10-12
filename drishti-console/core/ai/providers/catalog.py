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

"""The curated model catalogue and the presets of the providers added after ``openai`` and ``anthropic`` (``sajha/ai/llm/catalog.py``), read from ``llm_catalog.json``.

Data, not code: a vendor's new model id is a line in a provider file's ``models:`` (which adds to or overrides the catalogue), never a code change. The Java layer reads
an identical copy (``drishti-ai/src/main/resources/ai/llm_catalog.json``). A preset is a base URL, the environment variables a key may be read from and a model list;
``apply_defaults`` fills a parsed provider block from it. Prices in the file are USD per million tokens; the layer's own unit is per 1,000 tokens."""
from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path

_NAME_BAD = re.compile(r"[^A-Za-z0-9_.-]")
CREDENTIAL_SETTINGS = ("aws-access-key-id", "aws-secret-access-key", "aws-session-token", "client-secret")


@lru_cache(maxsize=1)
def _data() -> dict:
    return json.loads((Path(__file__).with_name("llm_catalog.json")).read_text(encoding="utf-8"))


def presets() -> dict:
    return _data()["presets"]


def preset(type_name: str) -> dict | None:
    return presets().get((type_name or "").lower())


def logical_name(model_id: str) -> str:
    """A model id as a logical name (letters, digits, ``.``, ``_``, ``-``; at most 64 characters): ``openai/gpt-oss-20b`` is ``openai_gpt-oss-20b``."""
    n = _NAME_BAD.sub("_", model_id)[:64]
    return n if n and n[0].isalnum() else "m" + n[:63]


def catalog_models(type_name: str) -> list[dict]:
    """The catalogue of a type as the model dicts of ``config.provider_from_dict``."""
    out = []
    for e in _data()["catalog"].get((type_name or "").lower(), []):
        emb = e["kind"] == "embedding"
        caps = ("embeddings",) if emb else ("chat",) + (("tool-calls",) if e["tools"] else ()) + (("json-mode",) if e["json"] else ())
        out.append({"name": logical_name(e["id"]), "id": e["id"], "kind": e["kind"], "max_tokens": None, "temperature": None, "capabilities": caps,
                    "cost_per_1k_input": e["input-per-mtok"] / 1000, "cost_per_1k_output": e["output-per-mtok"] / 1000})
    return out


def default_model(type_name: str, kind: str = "chat") -> str:
    d = _data()["defaults"].get((type_name or "").lower()) or {}
    return logical_name(d[kind]) if d.get(kind) else ""


def first_env(names, env) -> str:
    for n in names or []:
        v = env(n)
        if v and v.strip():
            return v.strip()
    return ""
