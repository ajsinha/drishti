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

"""Reads (and, for tests, writes) Avro object container files: enough for an Iceberg manifest list and manifest, so the S3 Tables
check can walk one table from its metadata file to a data file with no Avro library installed. Codecs: null and deflate (Iceberg's
default, gzip); snappy and zstandard when their Python modules are present. Logical types are returned as their underlying values."""
from __future__ import annotations

import io
import json
import struct
import zlib

MAGIC = b"Obj\x01"


class AvroError(Exception):
    pass


def _long(f) -> int:
    shift, out = 0, 0
    while True:
        b = f.read(1)
        if not b:
            raise AvroError("truncated varint")
        out |= (b[0] & 0x7F) << shift
        if not b[0] & 0x80:
            return (out >> 1) ^ -(out & 1)
        shift += 7


def _bytes(f) -> bytes:
    n = _long(f)
    data = f.read(n)
    if len(data) != n:
        raise AvroError("truncated bytes")
    return data


class _Decoder:
    def __init__(self, schema):
        self.named: dict[str, object] = {}
        self.schema = self._register(schema, None)

    def _register(self, s, ns):
        if isinstance(s, dict) and s.get("type") in ("record", "error", "enum", "fixed"):
            name = s["name"]
            space = s.get("namespace", ns)
            full = name if "." in name or not space else f"{space}.{name}"
            self.named[full] = s
            self.named[name] = s
            if s["type"] in ("record", "error"):
                for fld in s["fields"]:
                    self._register(fld["type"], space)
        elif isinstance(s, dict) and s.get("type") == "array":
            self._register(s["items"], ns)
        elif isinstance(s, dict) and s.get("type") == "map":
            self._register(s["values"], ns)
        elif isinstance(s, list):
            for b in s:
                self._register(b, ns)
        return s

    def read(self, s, f):
        if isinstance(s, list):
            return self.read(s[_long(f)], f)
        if isinstance(s, str):
            if s in self.named:
                return self.read(self.named[s], f)
            t = s
        else:
            t = s["type"]
            if isinstance(t, (dict, list)) or (isinstance(t, str) and t in self.named and t not in ("record", "enum", "fixed")):
                return self.read(t, f)
        if t == "null":
            return None
        if t == "boolean":
            return f.read(1) == b"\x01"
        if t in ("int", "long"):
            return _long(f)
        if t == "float":
            return struct.unpack("<f", f.read(4))[0]
        if t == "double":
            return struct.unpack("<d", f.read(8))[0]
        if t == "bytes":
            return _bytes(f)
        if t == "string":
            return _bytes(f).decode("utf-8")
        if t in ("record", "error"):
            return {fld["name"]: self.read(fld["type"], f) for fld in s["fields"]}
        if t == "enum":
            return s["symbols"][_long(f)]
        if t == "fixed":
            return f.read(s["size"])
        if t == "array":
            return [self.read(s["items"], f) for _ in self._blocks(f)]
        if t == "map":
            out = {}
            for _ in self._blocks(f):
                k = _bytes(f).decode("utf-8")
                out[k] = self.read(s["values"], f)
            return out
        raise AvroError(f"unknown Avro type {t!r}")

    @staticmethod
    def _blocks(f):
        while True:
            n = _long(f)
            if n == 0:
                return
            if n < 0:
                _long(f)                      # the block's byte size
                n = -n
            for _ in range(n):
                yield None


def _decompress(codec: str, data: bytes) -> bytes:
    if codec in ("null", ""):
        return data
    if codec == "deflate":
        return zlib.decompress(data, -15)
    if codec == "snappy":
        try:
            import snappy  # type: ignore
        except ImportError as e:
            raise AvroError("this file uses the snappy codec: pip install python-snappy") from e
        return snappy.decompress(data[:-4])
    if codec == "zstandard":
        try:
            import zstandard  # type: ignore
        except ImportError as e:
            raise AvroError("this file uses the zstandard codec: pip install zstandard") from e
        return zstandard.ZstdDecompressor().decompressobj().decompress(data)
    raise AvroError(f"unknown Avro codec {codec}")


def read(data: bytes) -> tuple[dict, list]:
    """(the file's metadata with its schema parsed, the records) of an Avro object container file."""
    f = io.BytesIO(data)
    if f.read(4) != MAGIC:
        raise AvroError("not an Avro object container file")
    meta = {}
    for _ in _Decoder._blocks(f):
        k = _bytes(f).decode("utf-8")
        meta[k] = _bytes(f)
    sync = f.read(16)
    schema = json.loads(meta["avro.schema"])
    codec = meta.get("avro.codec", b"null").decode()
    dec = _Decoder(schema)
    records = []
    while f.tell() < len(data):
        count = _long(f)
        block = io.BytesIO(_decompress(codec, _bytes(f)))
        for _ in range(count):
            records.append(dec.read(dec.schema, block))
        if f.read(16) != sync:
            raise AvroError("bad sync marker")
    return {k: (v.decode("utf-8", "replace") if k != "avro.schema" else schema) for k, v in meta.items()}, records


# --- writing (tests build manifest lists and manifests with it) --------------------------------------------------------------------

def _wlong(n: int) -> bytes:
    n = (n << 1) ^ (n >> 63)
    out = bytearray()
    while n & ~0x7F:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def _wbytes(b: bytes) -> bytes:
    return _wlong(len(b)) + b


def _write(s, v, named) -> bytes:
    if isinstance(s, list):
        for i, branch in enumerate(s):
            bt = branch if isinstance(branch, str) else branch.get("type")
            if (v is None) == (bt == "null"):
                return _wlong(i) + _write(branch, v, named)
        raise AvroError(f"no union branch for {v!r}")
    if isinstance(s, str) and s in named:
        return _write(named[s], v, named)
    t = s if isinstance(s, str) else s["type"]
    if isinstance(t, (dict, list)):
        return _write(t, v, named)
    if t == "null":
        return b""
    if t == "boolean":
        return b"\x01" if v else b"\x00"
    if t in ("int", "long"):
        return _wlong(int(v))
    if t == "double":
        return struct.pack("<d", v)
    if t == "bytes":
        return _wbytes(v)
    if t == "string":
        return _wbytes(v.encode("utf-8"))
    if t == "record":
        named[s["name"]] = s
        return b"".join(_write(fld["type"], v.get(fld["name"]), named) for fld in s["fields"])
    if t == "array":
        return (_wlong(len(v)) + b"".join(_write(s["items"], x, named) for x in v) if v else b"") + _wlong(0)
    if t == "map":
        return (_wlong(len(v)) + b"".join(_wbytes(k.encode()) + _write(s["values"], x, named) for k, x in v.items()) if v else b"") + _wlong(0)
    raise AvroError(f"cannot write Avro type {t!r}")


def write(schema: dict, records: list, codec: str = "deflate", meta: dict | None = None) -> bytes:
    """An Avro object container file of the records (one block)."""
    sync = b"drishti-sync-16b"
    head = {"avro.schema": json.dumps(schema).encode(), "avro.codec": codec.encode()}
    head.update({k: v.encode() for k, v in (meta or {}).items()})
    out = MAGIC + _wlong(len(head)) + b"".join(_wbytes(k.encode()) + _wbytes(v) for k, v in head.items()) + _wlong(0) + sync
    named: dict = {}
    body = b"".join(_write(schema, r, named) for r in records)
    if codec == "deflate":
        c = zlib.compressobj(wbits=-15)
        body = c.compress(body) + c.flush()
    return out + _wlong(len(records)) + _wbytes(body) + sync
