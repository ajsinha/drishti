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

"""The Arrow IPC stream format in pure Python, for the column types the compute transport carries (docs/architecture/PYTHON_COMPUTE.md
section 7.3): 64-bit integers (any integer width is read), doubles (float32 is read), booleans, text and fixed-size lists of doubles (a
509-day P&L path per row). No pyarrow, no numpy, no compiled code: the SDK must run in a bare interpreter (the Windows QA machine takes
no native binaries). Numbers are exposed as zero-copy ``memoryview`` objects over the file (memory-mapped where the platform lets a mapped
file be deleted while open), so reading a million rows costs no Python objects until they are used. With pyarrow installed, an engine may
ask for ``pyarrow`` tables instead (``Inputs.arrow``); the bytes are the same.
"""
from __future__ import annotations

import array
import mmap
import os
import struct
import sys
from typing import Iterable, Sequence

INT64, FLOAT64, BOOL, UTF8, FLOAT64_VECTOR = "int64", "float64", "bool", "utf8", "float64_vector"

# Arrow's flatbuffer enums (Schema.fbs, Message.fbs)
_T_INT, _T_FLOAT, _T_UTF8, _T_BOOL, _T_FSL, _T_LARGE_UTF8 = 2, 3, 5, 6, 16, 20
_H_SCHEMA, _H_DICT, _H_BATCH = 1, 2, 3
_P_HALF, _P_SINGLE, _P_DOUBLE = 0, 1, 2
_V5 = 4
_LITTLE = sys.byteorder == "little"


class ArrowError(ValueError):
    """The bytes are not an Arrow stream this codec reads, or a table cannot be written."""


class Column:
    """One column. ``data`` is a memoryview/array of int64 ('q') or float64 ('d') values, a list of bool, a list of str (None for null),
    or for a vector column the flat float64 values, ``width`` per row. ``nulls`` is a set of null row numbers or None."""

    __slots__ = ("name", "type", "width", "data", "nulls", "rows")

    def __init__(self, name: str, type_: str, data, width: int = 1, nulls: set | None = None):
        if type_ not in (INT64, FLOAT64, BOOL, UTF8, FLOAT64_VECTOR):
            raise ArrowError(f"column {name}: unknown type {type_}")
        self.name, self.type, self.width = name, type_, (width if type_ == FLOAT64_VECTOR else 1)
        if type_ == FLOAT64_VECTOR and self.width < 1:
            raise ArrowError(f"vector column {name} needs a width of at least 1")
        self.data = data
        self.nulls = nulls or None
        self.rows = len(data) // self.width

    def __len__(self) -> int:
        return self.rows

    def is_null(self, row: int) -> bool:
        return bool(self.nulls) and row in self.nulls

    def __getitem__(self, row: int):
        if self.nulls and row in self.nulls:
            return None
        if self.type == FLOAT64_VECTOR:
            return self.data[row * self.width:(row + 1) * self.width]
        return self.data[row]

    def vector(self, row: int):
        """One row of a vector column (a memoryview of ``width`` doubles)."""
        if self.type != FLOAT64_VECTOR:
            raise ArrowError(f"column {self.name} is {self.type}, not a vector column")
        return self.data[row * self.width:(row + 1) * self.width]

    def to_list(self) -> list:
        if self.type == FLOAT64_VECTOR:
            return [None if self.is_null(r) else list(self.vector(r)) for r in range(self.rows)]
        if self.nulls:
            return [None if r in self.nulls else self.data[r] for r in range(self.rows)]
        return list(self.data)


class Table:
    """Columns of one length, in order."""

    def __init__(self, columns: Sequence[Column] = ()):
        self.columns = list(columns)
        names = [c.name for c in self.columns]
        if len(set(names)) != len(names):
            raise ArrowError(f"two columns share a name: {names}")
        lens = {c.rows for c in self.columns}
        if len(lens) > 1:
            raise ArrowError("the columns have different lengths: " + ", ".join(f"{c.name}={c.rows}" for c in self.columns))
        self.rows = lens.pop() if lens else 0
        self._mm = None

    @property
    def names(self) -> list[str]:
        return [c.name for c in self.columns]

    def column(self, name: str) -> Column:
        for c in self.columns:
            if c.name == name:
                return c
        raise KeyError(f"no column {name} (columns: {self.names})")

    __getitem__ = column

    def __len__(self) -> int:
        return self.rows

    def to_rows(self) -> list[dict]:
        cols = [(c.name, c.to_list()) for c in self.columns]
        return [{n: v[r] for n, v in cols} for r in range(self.rows)]

    def to_pyarrow(self):
        """The same table as a ``pyarrow.Table`` (pyarrow must be installed)."""
        import pyarrow as pa  # noqa: WPS433 (optional dependency)
        return pa.ipc.open_stream(to_bytes(self)).read_all()

    def close(self) -> None:
        """Releases the file mapping (views taken from the table must be dropped first)."""
        self.columns = []
        if self._mm is not None:
            try:
                self._mm.close()
            except BufferError:
                pass  # a view is still alive; the mapping goes with it
            self._mm = None


# ------------------------------------------------------------------------------------------------------------------- building

def int64(name: str, values: Iterable[int | None]) -> Column:
    vals, nulls = _nulls(values, 0)
    return Column(name, INT64, array.array("q", vals), nulls=nulls)


def float64(name: str, values: Iterable[float | None]) -> Column:
    if isinstance(values, array.array) and values.typecode == "d":
        return Column(name, FLOAT64, values)
    vals, nulls = _nulls(values, 0.0)
    return Column(name, FLOAT64, array.array("d", vals), nulls=nulls)


def boolean(name: str, values: Iterable[bool | None]) -> Column:
    vals, nulls = _nulls(values, False)
    return Column(name, BOOL, [bool(v) for v in vals], nulls=nulls)


def utf8(name: str, values: Iterable[str | None]) -> Column:
    vals = list(values)
    nulls = {i for i, v in enumerate(vals) if v is None}
    return Column(name, UTF8, [None if v is None else str(v) for v in vals], nulls=nulls)


def vectors(name: str, width: int, flat: Iterable[float]) -> Column:
    """A vector column from flat values, ``width`` per row (an ``array('d')``, a list, or anything with the buffer protocol)."""
    data = flat if isinstance(flat, array.array) and flat.typecode == "d" else array.array("d", flat)
    if len(data) % width:
        raise ArrowError(f"{name}: {len(data)} values is not a whole number of rows of {width}")
    return Column(name, FLOAT64_VECTOR, data, width=width)


def _nulls(values, fill):
    vals, nulls = [], set()
    for i, v in enumerate(values):
        if v is None:
            nulls.add(i)
            vals.append(fill)
        else:
            vals.append(v)
    return vals, nulls


# ------------------------------------------------------------------------------------------------------------------- reading

class _Fb:
    """A flatbuffer table at ``pos`` in ``buf``: the few accessors Arrow's messages need."""

    __slots__ = ("b", "pos", "vt", "vlen")

    def __init__(self, buf, pos: int):
        self.b, self.pos = buf, pos
        self.vt = pos - struct.unpack_from("<i", buf, pos)[0]
        self.vlen = struct.unpack_from("<H", buf, self.vt)[0]

    def _off(self, slot: int) -> int:
        o = 4 + 2 * slot
        return struct.unpack_from("<H", self.b, self.vt + o)[0] if o < self.vlen else 0

    def scalar(self, slot: int, fmt: str, default):
        o = self._off(slot)
        return struct.unpack_from(fmt, self.b, self.pos + o)[0] if o else default

    def _indirect(self, at: int) -> int:
        return at + struct.unpack_from("<I", self.b, at)[0]

    def table(self, slot: int):
        o = self._off(slot)
        return _Fb(self.b, self._indirect(self.pos + o)) if o else None

    def string(self, slot: int) -> str:
        o = self._off(slot)
        if not o:
            return ""
        at = self._indirect(self.pos + o)
        n = struct.unpack_from("<I", self.b, at)[0]
        return bytes(self.b[at + 4:at + 4 + n]).decode("utf-8")

    def vector(self, slot: int) -> tuple[int, int]:
        """(start of the first element, element count)."""
        o = self._off(slot)
        if not o:
            return 0, 0
        at = self._indirect(self.pos + o)
        return at + 4, struct.unpack_from("<I", self.b, at)[0]

    def tables(self, slot: int) -> list:
        start, n = self.vector(slot)
        return [_Fb(self.b, self._indirect(start + 4 * i)) for i in range(n)]


def read(path_or_bytes) -> Table:
    """Reads an Arrow IPC stream from a file path or bytes."""
    mm = None
    if isinstance(path_or_bytes, (bytes, bytearray, memoryview)):
        buf = memoryview(path_or_bytes)
    elif os.name == "nt":
        with open(path_or_bytes, "rb") as f:  # Windows cannot delete a mapped file: read it whole
            buf = memoryview(f.read())
    else:
        with open(path_or_bytes, "rb") as f:
            size = os.fstat(f.fileno()).st_size
            if size == 0:
                raise ArrowError(f"{path_or_bytes} is empty, not an Arrow stream")
            mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
            buf = memoryview(mm)
    t = _read(buf)
    t._mm = mm
    return t


def _read(buf: memoryview) -> Table:
    if not _LITTLE:
        raise ArrowError("the Arrow codec needs a little-endian machine")
    pos, fields, batches = 0, None, []
    while pos + 4 <= len(buf):
        size = struct.unpack_from("<i", buf, pos)[0]
        pos += 4
        if size == -1:
            if pos + 4 > len(buf):
                break
            size = struct.unpack_from("<i", buf, pos)[0]
            pos += 4
        if size == 0:
            break
        if size < 0 or pos + size > len(buf):
            raise ArrowError(f"not an Arrow stream: a message of {size} bytes at {pos}")
        meta = buf[pos:pos + size]
        pos += size
        msg = _Fb(meta, struct.unpack_from("<I", meta, 0)[0])
        htype = msg.scalar(1, "<B", 0)
        body_len = msg.scalar(3, "<q", 0)
        body = buf[pos:pos + body_len]
        if len(body) != body_len:
            raise ArrowError("the Arrow stream is cut short")
        pos += body_len
        header = msg.table(2)
        if htype == _H_SCHEMA:
            fields = _fields(header)
        elif htype == _H_BATCH:
            if fields is None:
                raise ArrowError("a record batch before the schema")
            batches.append(_batch(header, body, fields))
        elif htype == _H_DICT:
            raise ArrowError("dictionary-encoded columns are not supported: cast them to text")
        else:
            raise ArrowError(f"unexpected Arrow message type {htype}")
    if fields is None:
        raise ArrowError("not an Arrow stream: no schema")
    if not batches:
        return Table([Column(f["name"], f["kind"], _empty(f["kind"]), width=f.get("width", 1)) for f in fields])
    return batches[0] if len(batches) == 1 else concat(batches)


def _empty(kind):
    return array.array("q") if kind == INT64 else array.array("d") if kind in (FLOAT64, FLOAT64_VECTOR) else []


def _fields(schema: _Fb) -> list[dict]:
    out = []
    for f in schema.tables(1):
        name, ttype, t = f.string(0), f.scalar(2, "<B", 0), f.table(3)
        if f.table(4) is not None:
            raise ArrowError(f"column {name} is dictionary-encoded: cast it to text")
        if ttype == _T_INT:
            out.append({"name": name, "kind": INT64, "bits": t.scalar(0, "<i", 0), "signed": bool(t.scalar(1, "<B", 0))})
        elif ttype == _T_FLOAT:
            prec = t.scalar(0, "<h", 0)
            if prec == _P_HALF:
                raise ArrowError(f"column {name} is a half-precision float: cast it to float64")
            out.append({"name": name, "kind": FLOAT64, "prec": prec})
        elif ttype == _T_BOOL:
            out.append({"name": name, "kind": BOOL})
        elif ttype in (_T_UTF8, _T_LARGE_UTF8):
            out.append({"name": name, "kind": UTF8, "large": ttype == _T_LARGE_UTF8})
        elif ttype == _T_FSL:
            children = f.tables(5)
            if len(children) != 1 or children[0].scalar(2, "<B", 0) != _T_FLOAT:
                raise ArrowError(f"column {name} is a fixed-size list of something other than floats")
            prec = children[0].table(3).scalar(0, "<h", 0)
            out.append({"name": name, "kind": FLOAT64_VECTOR, "width": t.scalar(0, "<i", 0), "prec": prec})
        else:
            raise ArrowError(f"column {name} has Arrow type {ttype}: the transport carries int, float, bool, utf8 and fixed-size lists of floats")
    return out


def _batch(rb: _Fb, body: memoryview, fields: list[dict]) -> Table:
    if rb.table(3) is not None:
        raise ArrowError("compressed Arrow bodies are not supported: write without compression")
    rows = rb.scalar(0, "<q", 0)
    nstart, _ = rb.vector(1)
    bstart, _ = rb.vector(2)
    b = rb.b
    node_i, buf_i = 0, 0

    def node():
        nonlocal node_i
        length, nulls = struct.unpack_from("<qq", b, nstart + 16 * node_i)
        node_i += 1
        return length, nulls

    def buffer():
        nonlocal buf_i
        off, length = struct.unpack_from("<qq", b, bstart + 16 * buf_i)
        buf_i += 1
        if off < 0 or off + length > len(body):
            raise ArrowError("an Arrow buffer points outside its body")
        return body[off:off + length]

    cols = []
    for f in fields:
        _, null_count = node()
        validity = buffer()
        nulls = _null_rows(validity, rows) if null_count else None
        kind = f["kind"]
        if kind == INT64:
            raw = buffer()
            bits, signed = f["bits"], f["signed"]
            code = {8: "b", 16: "h", 32: "i", 64: "q"}[bits]
            data = raw[:rows * bits // 8].cast(code if signed else code.upper())
            if code != "q" or not signed:
                data = array.array("q", data)
            cols.append(Column(f["name"], INT64, data, nulls=nulls))
        elif kind == FLOAT64:
            raw = buffer()
            data = raw[:rows * 8].cast("d") if f["prec"] == _P_DOUBLE else array.array("d", raw[:rows * 4].cast("f"))
            cols.append(Column(f["name"], FLOAT64, data, nulls=nulls))
        elif kind == BOOL:
            raw = buffer()
            cols.append(Column(f["name"], BOOL, [bool(raw[i >> 3] >> (i & 7) & 1) for i in range(rows)], nulls=nulls))
        elif kind == UTF8:
            offs = buffer()
            data = buffer()
            o = offs[:(rows + 1) * (8 if f["large"] else 4)].cast("q" if f["large"] else "i")
            vals = [None if nulls and i in nulls else bytes(data[o[i]:o[i + 1]]).decode("utf-8") for i in range(rows)]
            cols.append(Column(f["name"], UTF8, vals, nulls=nulls))
        else:
            _, child_nulls = node()
            if child_nulls:
                raise ArrowError(f"column {f['name']} has nulls inside its vectors")
            buffer()  # the child's validity
            raw = buffer()
            n = rows * f["width"]
            data = raw[:n * 8].cast("d") if f["prec"] == _P_DOUBLE else array.array("d", raw[:n * 4].cast("f"))
            cols.append(Column(f["name"], FLOAT64_VECTOR, data, width=f["width"], nulls=nulls))
    return Table(cols)


def _null_rows(validity: memoryview, rows: int) -> set:
    return {i for i in range(rows) if not (validity[i >> 3] >> (i & 7)) & 1}


def concat(tables: Sequence[Table]) -> Table:
    """The tables one after another (same columns, same order)."""
    first = tables[0]
    cols = []
    for ci, c in enumerate(first.columns):
        nulls, base = set(), 0
        if c.type in (INT64, FLOAT64, FLOAT64_VECTOR):
            data = array.array("q" if c.type == INT64 else "d")
        else:
            data = []
        for t in tables:
            o = t.columns[ci]
            if o.name != c.name or o.type != c.type or o.width != c.width:
                raise ArrowError(f"the tables differ at column {c.name}")
            if isinstance(data, list):
                data.extend(o.data)
            else:
                data.frombytes(o.data.tobytes())
            if o.nulls:
                nulls.update(base + r for r in o.nulls)
            base += o.rows
        cols.append(Column(c.name, c.type, data, width=c.width, nulls=nulls or None))
    return Table(cols)


# ------------------------------------------------------------------------------------------------------------------- writing

class _Builder:
    """A minimal flatbuffer builder (the official algorithm: the buffer is filled from its end towards its start)."""

    def __init__(self, size: int = 1024):
        self.buf = bytearray(size)
        self.head = size
        self.minalign = 1
        self.vtable: list[int] | None = None
        self.object_end = 0

    def offset(self) -> int:
        return len(self.buf) - self.head

    def _grow(self, need: int) -> None:
        while self.head < need:
            old = len(self.buf)
            self.buf = bytearray(old) + self.buf
            self.head += old

    def _pad(self, n: int) -> None:
        self.head -= n
        self.buf[self.head:self.head + n] = bytes(n)

    def prep(self, size: int, additional: int) -> None:
        self.minalign = max(self.minalign, size)
        align = (~(len(self.buf) - self.head + additional) + 1) & (size - 1)
        self._grow(align + size + additional)
        self._pad(align)

    def place(self, fmt: str, v) -> None:
        n = struct.calcsize(fmt)
        self.head -= n
        struct.pack_into(fmt, self.buf, self.head, v)

    def scalar(self, fmt: str, v) -> int:
        self.prep(struct.calcsize(fmt), 0)
        self.place(fmt, v)
        return self.offset()

    def uoffset(self, off: int) -> None:
        self.prep(4, 0)
        self.place("<I", self.offset() - off + 4)

    def string(self, s: str) -> int:
        b = s.encode("utf-8")
        self.prep(4, len(b) + 1)
        self.place("<B", 0)
        self.head -= len(b)
        self.buf[self.head:self.head + len(b)] = b
        self.place("<I", len(b))
        return self.offset()

    def offsets_vector(self, offs: list[int]) -> int:
        self.prep(4, 4 * len(offs))
        for o in reversed(offs):
            self.uoffset(o)
        self.place("<I", len(offs))
        return self.offset()

    def struct_vector(self, pairs: list[tuple[int, int]]) -> int:
        """A vector of 16-byte structs of two int64 (FieldNode, Buffer)."""
        self.prep(4, 16 * len(pairs))
        self.prep(8, 16 * len(pairs))
        for a, b2 in reversed(pairs):
            self.prep(8, 16)
            self.place("<q", b2)
            self.place("<q", a)
        self.place("<I", len(pairs))
        return self.offset()

    def start(self, slots: int) -> None:
        self.vtable = [0] * slots
        self.object_end = self.offset()

    def add_scalar(self, slot: int, fmt: str, v) -> None:
        self.vtable[slot] = self.scalar(fmt, v)

    def add_offset(self, slot: int, off: int) -> None:
        self.uoffset(off)
        self.vtable[slot] = self.offset()

    def end(self) -> int:
        self.scalar("<i", 0)
        obj = self.offset()
        for off in reversed(self.vtable):
            self.scalar("<h", obj - off if off else 0)
        self.scalar("<h", obj - self.object_end)
        self.scalar("<h", (len(self.vtable) + 2) * 2)
        vt = self.offset()
        struct.pack_into("<i", self.buf, len(self.buf) - obj, vt - obj)
        self.vtable = None
        return obj

    def finish(self, root: int) -> bytes:
        self.prep(self.minalign, 4)
        self.uoffset(root)
        return bytes(self.buf[self.head:])


def _type_table(fb: _Builder, c: Column) -> tuple[int, int]:
    if c.type == INT64:
        fb.start(2)
        fb.add_scalar(0, "<i", 64)
        fb.add_scalar(1, "<B", 1)
        return _T_INT, fb.end()
    if c.type == FLOAT64:
        fb.start(1)
        fb.add_scalar(0, "<h", _P_DOUBLE)
        return _T_FLOAT, fb.end()
    if c.type == BOOL:
        fb.start(0)
        return _T_BOOL, fb.end()
    if c.type == UTF8:
        fb.start(0)
        return _T_UTF8, fb.end()
    fb.start(1)
    fb.add_scalar(0, "<i", c.width)
    return _T_FSL, fb.end()


def _field(fb: _Builder, name: str, ttype: int, type_off: int, children: list[int]) -> int:
    n = fb.string(name)
    ch = fb.offsets_vector(children)
    fb.start(7)
    fb.add_offset(0, n)
    fb.add_scalar(1, "<B", 1)
    fb.add_scalar(2, "<B", ttype)
    fb.add_offset(3, type_off)
    fb.add_offset(5, ch)
    return fb.end()


def _message(fb: _Builder, htype: int, header: int, body_len: int) -> bytes:
    fb.start(5)
    fb.add_scalar(0, "<h", _V5)
    fb.add_scalar(1, "<B", htype)
    fb.add_offset(2, header)
    fb.add_scalar(3, "<q", body_len)
    return fb.finish(fb.end())


def _schema(t: Table) -> bytes:
    fb = _Builder()
    fields = []
    for c in t.columns:
        if c.type == FLOAT64_VECTOR:
            fb.start(1)
            fb.add_scalar(0, "<h", _P_DOUBLE)
            child = _field(fb, "item", _T_FLOAT, fb.end(), [])
            tt, to = _type_table(fb, c)
            fields.append(_field(fb, c.name, tt, to, [child]))
        else:
            tt, to = _type_table(fb, c)
            fields.append(_field(fb, c.name, tt, to, []))
    fv = fb.offsets_vector(fields)
    fb.start(4)
    fb.add_scalar(0, "<h", 0)
    fb.add_offset(1, fv)
    return _message(fb, _H_SCHEMA, fb.end(), 0)


def _validity(c: Column) -> bytes:
    if not c.nulls:
        return b""
    bits = bytearray((c.rows + 7) // 8)
    for i in range(c.rows):
        if i not in c.nulls:
            bits[i >> 3] |= 1 << (i & 7)
    return bytes(bits)


def _as_bytes(data, code: str) -> bytes:
    if isinstance(data, memoryview):
        return data.tobytes() if data.format == code else array.array(code, data).tobytes()
    if isinstance(data, array.array) and data.typecode == code:
        return data.tobytes()
    if hasattr(data, "tobytes") and getattr(data, "dtype", None) is not None:  # numpy, without importing it
        return data.astype("<f8" if code == "d" else "<i8", copy=False).tobytes()
    return array.array(code, data).tobytes()


def _body(t: Table) -> tuple[list[tuple[int, int]], list[bytes]]:
    nodes, bufs = [], []
    for c in t.columns:
        nodes.append((c.rows, len(c.nulls) if c.nulls else 0))
        bufs.append(_validity(c))
        if c.type == INT64:
            bufs.append(_as_bytes(c.data, "q"))
        elif c.type == FLOAT64:
            bufs.append(_as_bytes(c.data, "d"))
        elif c.type == BOOL:
            bits = bytearray((c.rows + 7) // 8)
            for i, v in enumerate(c.data):
                if v:
                    bits[i >> 3] |= 1 << (i & 7)
            bufs.append(bytes(bits))
        elif c.type == UTF8:
            offs, data, at = array.array("i", [0]), bytearray(), 0
            for v in c.data:
                if v is not None:
                    data += v.encode("utf-8")
                offs.append(len(data))
            bufs.append(offs.tobytes())
            bufs.append(bytes(data))
        else:
            nodes.append((c.rows * c.width, 0))
            bufs.append(b"")
            bufs.append(_as_bytes(c.data, "d"))
    return nodes, bufs


def _encap(out, meta: bytes, body: list[bytes]) -> None:
    padded = len(meta) + (-(len(meta) + 8) % 8)
    out.write(struct.pack("<iI", -1, padded))
    out.write(meta)
    out.write(bytes(padded - len(meta)))
    for b in body:
        out.write(b)
        out.write(bytes(-len(b) % 8))


def write(t: Table, out) -> None:
    """Writes the table as one Arrow IPC stream to a binary file object."""
    _encap(out, _schema(t), [])
    nodes, bufs = _body(t)
    spans, at = [], 0
    for b in bufs:
        spans.append((at, len(b)))
        at += len(b) + (-len(b) % 8)
    fb = _Builder()
    bv = fb.struct_vector(spans)
    nv = fb.struct_vector(nodes)
    fb.start(5)
    fb.add_scalar(0, "<q", t.rows)
    fb.add_offset(1, nv)
    fb.add_offset(2, bv)
    _encap(out, _message(fb, _H_BATCH, fb.end(), at), bufs)
    out.write(struct.pack("<iI", -1, 0))


def write_file(t: Table, path) -> None:
    with open(path, "wb") as f:
        write(t, f)


def to_bytes(t: Table) -> bytes:
    import io
    b = io.BytesIO()
    write(t, b)
    return b.getvalue()
