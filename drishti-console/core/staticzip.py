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

"""Static files served compressed (RUPAKA_POC.md, adjustment 2). Starlette's ``StaticFiles`` sends bytes as they are: the 36 MB DuckDB-Wasm module is
35 MB on the wire, 8 MB gzipped. This subclass answers a request that accepts ``br`` or ``gzip`` with the compressed file instead, for the text
and WebAssembly types worth compressing.

Each file is compressed once per version (its path, size and modification time name the cache entry) into a cache folder and served from there by
``FileResponse``, so the cost is one compression, off the event loop, never one per request; a second request for the same file while it is being
compressed waits for the first. Brotli is used when the ``brotli`` package is installed (smaller), else gzip. A request with ``Range``, a file below
``min_bytes`` or of a type already compressed (images, fonts) is served as it is."""
from __future__ import annotations

import gzip
import hashlib
import os
from core import tmpdir
import threading
from pathlib import Path

import anyio
from starlette.responses import FileResponse
from starlette.staticfiles import StaticFiles

try:  # optional
    import brotli
except ImportError:  # pragma: no cover - exercised where brotli is absent
    brotli = None

COMPRESSIBLE = {".js", ".mjs", ".css", ".wasm", ".json", ".html", ".svg", ".txt", ".map", ".yaml", ".yml", ".csv", ".md", ".ttf", ".otf"}
MEDIA = {".wasm": "application/wasm"}


class CompressedStatic(StaticFiles):
    def __init__(self, *, directory, cache_dir: str = "", min_bytes: int = 1024, enabled: bool = True, brotli_quality: int = 5, gzip_level: int = 6):
        super().__init__(directory=directory)
        self.enabled = enabled
        self.min_bytes = max(0, int(min_bytes))
        self.brotli_quality = brotli_quality
        self.gzip_level = gzip_level
        self.cache = Path(cache_dir) if cache_dir else tmpdir.sub_dir("static")
        self._locks: dict[str, threading.Lock] = {}
        self._guard = threading.Lock()
        self.compressed = 0             # files compressed since start (for tests and Health)

    def _encoding(self, scope) -> str | None:
        accept = ""
        for k, v in scope.get("headers", []):
            if k == b"accept-encoding":
                accept = v.decode("latin-1").lower()
            elif k == b"range":
                return None
        offered = {part.split(";")[0].strip() for part in accept.split(",")}
        if brotli is not None and "br" in offered:
            return "br"
        return "gzip" if "gzip" in offered else None

    async def get_response(self, path: str, scope):
        response = await super().get_response(path, scope)
        if not self.enabled or scope["method"] not in ("GET", "HEAD") or response.status_code != 200 or not isinstance(response, FileResponse):
            return response
        source = Path(response.path)
        enc = self._encoding(scope)
        if enc is None or source.suffix.lower() not in COMPRESSIBLE:
            return response
        try:
            stat = source.stat()
        except OSError:
            return response
        if stat.st_size < self.min_bytes:
            return response
        key = hashlib.sha1(f"{source}|{stat.st_size}|{stat.st_mtime_ns}|{enc}".encode()).hexdigest()
        target = self.cache / f"{key}.{'br' if enc == 'br' else 'gz'}"
        if not target.exists():
            await anyio.to_thread.run_sync(self._compress, source, target, enc, key)
        out = FileResponse(target, media_type=MEDIA.get(source.suffix.lower()) or response.media_type, headers={"Content-Encoding": enc, "Vary": "Accept-Encoding"})
        for h in ("cache-control", "etag", "last-modified"):
            if h in response.headers:
                out.headers[h] = response.headers[h]
        if "etag" in out.headers:                       # a representation of its own: the validator must say so
            out.headers["etag"] = out.headers["etag"].rstrip('"') + f'-{enc}"'
        return out

    def _compress(self, source: Path, target: Path, enc: str, key: str) -> None:
        with self._guard:
            lock = self._locks.setdefault(key, threading.Lock())
        with lock:                                      # the second request waits for the first, then finds the file
            if target.exists():
                return
            self.cache.mkdir(parents=True, exist_ok=True)
            tmp = target.with_suffix(target.suffix + f".{os.getpid()}.{threading.get_ident()}.tmp")
            data = source.read_bytes()
            packed = brotli.compress(data, quality=self.brotli_quality) if enc == "br" else gzip.compress(data, compresslevel=self.gzip_level, mtime=0)
            tmp.write_bytes(packed)
            tmp.replace(target)
            self.compressed += 1
