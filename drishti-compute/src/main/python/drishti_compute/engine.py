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

"""The engine contract (docs/architecture/PYTHON_COMPUTE.md section 8): what an approved engine implements and what it is handed.

An engine is a class with ``kinds`` (what it can compute) and ``compute(req, inputs, progress) -> Result``. It is

- **pure**: no network, no files outside the request's scratch folder, no state that outlives a request (register module-level caches
  with :func:`register_cache` so a warm worker clears them between requests);
- **deterministic**: random numbers only from ``req.seed``;
- **typed in its refusals**: ``raise ComputeError("the scope is empty")`` reaches the person in those words; any other exception is
  "the computation failed" with the request id, and its traceback goes to the server's log only.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any, Callable

from . import arrow

_CACHES: list[Any] = []


class ComputeError(Exception):
    """A refusal in words, shown to the person who asked (no traceback, no internals)."""


def register_cache(cache) -> Any:
    """Registers an object with ``clear()`` (a dict, an lru_cache'd function) to be cleared between requests on a warm worker."""
    _CACHES.append(cache)
    return cache


def clear_caches() -> None:
    for c in _CACHES:
        clear = getattr(c, "cache_clear", None) or getattr(c, "clear", None)
        if clear is not None:
            clear()


@dataclass(frozen=True)
class Request:
    """What is asked: never code, never credentials."""

    id: str
    kind: str
    params: dict
    user: str
    roles: tuple
    view_signature: str
    cls: str
    seed: int
    scratch: str
    chunk: dict | None = None

    def param(self, name: str, default=None):
        return self.params.get(name, default)


class Inputs:
    """The request's inputs, read and masked as the viewer by the server, handed over as Arrow files in the scratch folder."""

    def __init__(self, scratch: str, files: dict[str, str]):
        self._scratch = scratch
        self._files = dict(files)
        self._open: list[arrow.Table] = []

    def names(self) -> list[str]:
        return sorted(self._files)

    def path(self, name: str) -> str:
        if name not in self._files:
            raise ComputeError(f"the request has no input '{name}' (inputs: {', '.join(self.names()) or 'none'})")
        return os.path.join(self._scratch, self._files[name])

    def table(self, name: str) -> arrow.Table:
        """The input as the SDK's own table (no third-party package needed): numbers are zero-copy memoryviews."""
        t = arrow.read(self.path(name))
        self._open.append(t)
        return t

    def arrow(self, name: str):
        """The input as a ``pyarrow.Table``, memory-mapped (the environment must have pyarrow)."""
        import pyarrow as pa  # noqa: WPS433 (optional dependency)
        return pa.ipc.open_stream(pa.memory_map(self.path(name))).read_all()

    def close(self) -> None:
        for t in self._open:
            t.close()
        self._open.clear()


class Progress:
    """Sends partial results while the engine works; the panel paints them as "computing N%"."""

    def __init__(self, send: Callable[[dict], None]):
        self._send = send

    def partial(self, data: dict) -> None:
        self._send(dict(data))

    def fraction(self, done: int, of: int, **extra) -> None:
        self._send({"done": done, "of": of, **extra})


@dataclass
class Result:
    """What the engine returns: small ``rows`` (JSON values) and named ``tables`` (SDK tables or pyarrow tables, written as Arrow
    files and validated by the server), plus ``meta`` (the engine's version, counts)."""

    rows: list = field(default_factory=list)
    tables: dict = field(default_factory=dict)
    meta: dict = field(default_factory=dict)


class Engine:
    """The base class of an engine. Subclasses set ``kinds`` and implement :meth:`compute`."""

    kinds: tuple = ()
    version: str = "0"

    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:  # pragma: no cover - the contract
        raise NotImplementedError

    def incremental(self, req: Request, previous, events, inputs: Inputs):  # optional (DATA_ARCHITECTURE.md › Reactive connectors, section 10.2)
        return None
