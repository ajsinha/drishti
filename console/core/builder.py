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

"""Build workbench, bringing data in (docs/architecture/BUILD_WORKBENCH.md): the console's side of reading files.

* :class:`Limits` are the same three limits the server enforces (``drishti.builder.max-samples``, ``max-file-mb``,
  ``max-total-mb``), read from the console's ``builder`` settings, so a too-big upload is refused here, cleanly, before
  anything is forwarded.
* :func:`read_files` turns the uploaded files into samples. A file that cannot be used is reported (its problems) and
  the others carry on.
* :func:`plain_schema` strips Drishti's annotations (``x-drishti``) from a shape's schema.

The samples themselves are kept by the server, in a Design owned by the signed-in user (``/api/v1/builder/designs``); this
console keeps nothing, so a restart loses nothing and two consoles show the same Designs.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

ANNOTATION = "x-drishti"
_NAME_MAPS = ("properties", "$defs", "definitions", "patternProperties")
_DATA = ("enum", "const", "default", "examples")
_MB = 1024 * 1024


class TooBig(Exception):
    """An upload over a whole-request limit (files, samples or total size): refused as a whole."""


@dataclass(frozen=True)
class Limits:
    max_samples: int = 50
    max_file_bytes: int = 5 * _MB
    max_total_bytes: int = 25 * _MB

    @classmethod
    def from_settings(cls, settings) -> "Limits":
        return cls(max(1, int(settings.get("builder.max_samples", 50))),
                   int(float(settings.get("builder.max_file_mb", 5)) * _MB),
                   int(float(settings.get("builder.max_total_mb", 25)) * _MB))

    def as_dict(self) -> dict:
        return {"maxSamples": self.max_samples, "maxFileMb": self.max_file_bytes / _MB, "maxTotalMb": self.max_total_bytes / _MB}


def read_files(files: Any, limits: Limits) -> tuple[list[dict], list[dict]]:
    """``files``: the request's ``[{name, text}]``. Returns ``(samples, report)``: the samples as ``[{name, document}]``
    and, per file, ``{name, size, samples, problems}``. Raises :class:`TooBig` for a count or total over the limit."""
    if not isinstance(files, list) or not files:
        raise ValueError("'files' is required: a list of {name, text}")
    if len(files) > limits.max_samples:
        raise TooBig(f"{len(files)} files, over the limit of {limits.max_samples} (builder.max_samples)")
    total = 0
    samples: list[dict] = []
    report: list[dict] = []
    for i, f in enumerate(files):
        name = str(f.get("name") or f"file {i + 1}")[:200] if isinstance(f, dict) else f"file {i + 1}"
        text = f.get("text") if isinstance(f, dict) else None
        entry = {"name": name, "size": 0, "samples": 0, "problems": []}
        report.append(entry)
        if not isinstance(text, str):
            claimed = f.get("size") if isinstance(f, dict) else None        # a browser leaves a file over the limit unsent
            if isinstance(claimed, (int, float)) and claimed > limits.max_file_bytes:
                entry["size"] = int(claimed)
                entry["problems"].append(f"{claimed / _MB:.1f} MB, over the limit of {limits.max_file_bytes / _MB:g} MB per file (builder.max_file_mb)")
            else:
                entry["problems"].append("no text: the file could not be read")
            continue
        entry["size"] = size = len(text.encode("utf-8"))
        total += size
        if total > limits.max_total_bytes:
            raise TooBig(f"the files total over the limit of {limits.max_total_bytes / _MB:g} MB (builder.max_total_mb)")
        if size > limits.max_file_bytes:
            entry["problems"].append(f"{size / _MB:.1f} MB, over the limit of {limits.max_file_bytes / _MB:g} MB per file (builder.max_file_mb)")
            continue
        found = _documents(name, text, entry["problems"])
        entry["samples"] = len(found)
        samples.extend(found)
        if len(samples) > limits.max_samples:
            raise TooBig(f"more than {limits.max_samples} samples (builder.max_samples; each line of a .jsonl file is one)")
    return samples, report


def _documents(name: str, text: str, problems: list[str]) -> list[dict]:
    lower = name.lower()
    if lower.endswith(".jsonl"):
        out = []
        for n, line in enumerate(text.splitlines(), 1):
            if not line.strip():
                continue
            try:
                out.append({"name": f"{name}:{n}", "document": json.loads(line)})
            except ValueError:
                problems.append(f"line {n} is not valid JSON")
        if not out and not problems:
            problems.append("no lines")
        return out
    if not lower.endswith(".json"):
        problems.append("not a .json or .jsonl file")
        return []
    try:
        return [{"name": name, "document": json.loads(text)}]
    except ValueError as e:
        problems.append(f"not valid JSON ({str(e)[:120]})")
        return []


def plain_schema(node: Any, names: bool = False) -> Any:
    """The schema without Drishti's ``x-drishti`` annotations. ``names``: ``node`` is a map of property names to schemas
    (so a property that happens to be called ``x-drishti`` stays); values of ``enum``, ``const``, ``default`` and
    ``examples`` are data and stay as they are."""
    if isinstance(node, list):
        return [plain_schema(v) for v in node]
    if not isinstance(node, dict):
        return node
    out = {}
    for k, v in node.items():
        if names:
            out[k] = plain_schema(v)
        elif k == ANNOTATION:
            continue
        elif k in _NAME_MAPS and isinstance(v, dict):
            out[k] = plain_schema(v, names=True)
        elif k in _DATA:
            out[k] = v
        else:
            out[k] = plain_schema(v)
    return out
