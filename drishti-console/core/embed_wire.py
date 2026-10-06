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

"""Compression of embed payloads (docs/architecture/ELEMENTS.md, section 6; build step 7): Brotli when the browser accepts it and the
``brotli`` package is installed, else gzip; nothing under ``embed.compress_min_bytes`` and never an already compressed font."""
from __future__ import annotations

import gzip

try:
    import brotli
except ImportError:                       # optional: gzip alone is fine
    brotli = None


def accepts(header: str | None) -> set[str]:
    """The content codings a request accepts (``q=0`` ones excluded)."""
    out = set()
    for part in (header or "").split(","):
        name, _, q = part.strip().partition(";")
        if name and not (q.strip().replace(" ", "") in ("q=0", "q=0.0")):
            out.add(name.strip().lower())
    return out


def compress(body: bytes, accept_encoding: str | None, min_bytes: int = 512) -> tuple[bytes, str | None]:
    """The body and its ``Content-Encoding`` (None when sent as is)."""
    if len(body) < min_bytes:
        return body, None
    wanted = accepts(accept_encoding)
    if "br" in wanted and brotli is not None:
        return brotli.compress(body, quality=5), "br"
    if "gzip" in wanted:
        return gzip.compress(body, 6), "gzip"
    return body, None
