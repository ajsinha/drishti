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

"""The console's provider registry: providers and models by logical name, how each is doing, reload from ``config/ai``."""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field

from core import retry as retrymod
from core.ai import config as cfgmod
from core.ai.types import LlmError, ModelInfo, ProviderConfig

LOG = logging.getLogger("drishti.ai")


@dataclass
class State:
    calls: int = 0
    failures: int = 0
    last_ok: float | None = None
    last_failure: float | None = None
    last_error: str = ""
    last_was_failure: bool = False


@dataclass
class Entry:
    config: ProviderConfig
    provider: object | None
    error: str | None
    state: State = field(default_factory=State)

    @property
    def usable(self) -> bool:
        return self.provider is not None and self.config.enabled


class LlmRegistry:
    def __init__(self, factories: dict, source, runtime, default_model: str = "", retry: retrymod.RetryBoard | None = None):
        """``source()`` returns ``(configs, file_errors)``; ``runtime`` is handed to provider factories (the ``server`` provider).
        ``retry`` tracks providers that could not be set up: they are tried again on its schedule, not at every look."""
        self._factories, self._source, self._runtime = factories, source, runtime
        self.retry = retry or retrymod.RetryBoard("ai provider", retrymod.RetryPolicy())
        self._default = (default_model or "").strip()
        self._entries: dict[str, Entry] = {}
        self.file_errors: dict[str, str] = {}
        self.loaded_at = 0.0

    def reload(self) -> None:
        configs, errors = self._source()
        entries: dict[str, Entry] = {}
        for c in configs:
            prev = self._entries.get(c.name)
            state = prev.state if prev else State()
            if not c.enabled:
                self.retry.forget(c.name)
                entries[c.name] = Entry(c, None, None, state)
                continue
            if prev and prev.error and prev.config == c and self.retry.failing(c.name) and (
                    not self.retry.policy.enabled or c.name not in self.retry.due()):
                entries[c.name] = prev                      # refused before, same file, not yet time to try again
                continue
            if prev and prev.config != c:
                self.retry.forget(c.name)                    # the file changed: a fresh start
            f = self._factories.get(c.type.lower())
            if f is None:
                why = f"no provider type '{c.type}' is installed (have: {sorted(self._factories)})"
                self.retry.failed(c.name, why, True)
                entries[c.name] = Entry(c, None, why, state)
                continue
            try:
                entries[c.name] = Entry(c, f(c, self._runtime), None, state)
                self.retry.succeeded(c.name)
            except LlmError as e:
                self.retry.failed(c.name, e.message, retrymod.looks_permanent(e.message))
                entries[c.name] = Entry(c, None, e.message, state)
        for k in list(self.retry.statuses()):
            if not k.startswith("file ") and k not in entries:
                self.retry.forget(k)
        _track_files(self.retry, errors)
        self._entries, self.file_errors, self.loaded_at = entries, dict(errors), time.time()

    def types(self) -> list[str]:
        return sorted(self._factories)

    def entries(self) -> list[Entry]:
        return sorted(self._entries.values(), key=lambda e: e.config.name)

    def models(self) -> list[ModelInfo]:
        return [m for e in self.entries() if e.usable for m in e.provider.models()]

    def chat(self, ref: str | None):
        """``(entry, chat model)`` for ``provider/model``, a model name only one provider has, or (None) the default, then the only chat model."""
        r = (ref or self._default or "").strip()
        hits = []
        if not r:
            for e in self.entries():
                if e.usable:
                    for m in e.provider.models():
                        if not m.embedding and (not e.config.default_model or e.config.default_model == m.name):
                            hits.append((e, e.provider.chat_model(m.name)))
            if len(hits) == 1:
                return hits[0]
            raise LlmError("not_found", "no chat model is configured" if not hits else "several chat models are configured; name one (provider/model) or set a default-model")
        prov, _, name = r.rpartition("/") if "/" in r else ("", "", r)
        for e in self.entries():
            if e.usable and (not prov or e.config.name == prov):
                m = e.provider.chat_model(name)
                if m is not None:
                    hits.append((e, m))
        if not hits:
            raise LlmError("not_found", f"no chat model '{r}' is configured")
        if len(hits) > 1:
            raise LlmError("not_found", f"model name '{r}' is in several providers; use provider/model")
        return hits[0]

    def embedding(self, ref: str):
        prov, _, name = ref.rpartition("/") if "/" in ref else ("", "", ref)
        for e in self.entries():
            if e.usable and (not prov or e.config.name == prov):
                m = e.provider.embedding_model(name)
                if m is not None:
                    return m
        return None

    def record(self, entry: Entry, ok: bool, error: str | None = None) -> None:
        s = entry.state
        s.calls += 1
        if ok:
            s.last_ok, s.last_was_failure = time.time(), False
        else:
            s.failures += 1
            s.last_failure, s.last_error, s.last_was_failure = time.time(), error or "", True

    def health(self, e: Entry) -> dict:
        s = e.state
        if not e.config.enabled:
            st, detail = "OFF", "enabled: false in the provider file"
        elif e.error:
            st, detail = "ERROR", e.error
        elif s.calls == 0:
            st, detail = "UNKNOWN", "configured; no call yet"
        else:
            st, detail = ("DOWN", s.last_error) if s.last_was_failure else ("UP", "last call succeeded")
        return {"status": st, "detail": detail, "calls": s.calls, "failures": s.failures, "lastOk": s.last_ok, "lastFailure": s.last_failure}

    async def aclose(self) -> None:
        for e in self._entries.values():
            if e.provider is not None:
                await e.provider.aclose()


def _track_files(board: retrymod.RetryBoard, errors: dict) -> None:
    """A file that cannot be read is a failure too (it is read again when it changes or its retry is due)."""
    for f, why in errors.items():
        key = f"file {f}"
        s = board.status(key)
        if s is None or s.last_error != why or key in board.due():
            board.failed(key, why, retrymod.looks_permanent(why))
    for k in list(board.statuses()):
        if k.startswith("file ") and k[5:] not in errors:
            board.succeeded(k)


def from_directory(directory, factories, runtime, default_model: str = "", env=None) -> LlmRegistry:
    reg = LlmRegistry(factories, lambda: cfgmod.load_providers(directory, env), runtime, default_model)
    reg.reload()
    return reg
