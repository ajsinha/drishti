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

"""Hostile-JSON cases for POST /builder/shape; every case prints status + whether samples validate against returned schema."""
import json, sys
from qa import *
from jsonschema import Draft202012Validator
def nest(n, leaf=1, key="a"):
    d = leaf
    for _ in range(n): d = {key: d}
    return d
def nesta(n):
    d = 1
    for _ in range(n): d = [d]
    return d
CASES = {
 "depth60": [nest(60)], "depth64": [nest(64)], "depth65": [nest(65)], "depth70": [nest(70)], "depth200": [nest(200)], "depth5000": None,
 "arr_depth64": [nesta(64)], "arr_depth66": [nesta(66)],
 "bigarray_100k": [{"xs": list(range(100000))}],
 "bigarray_objs_20k": [{"rows": [{"a": i, "b": str(i)} for i in range(20000)]}],
 "keys_dots": [{"a.b": 1, "a": {"b": 2}, "c[0]": 3, "d[]": 4, "$": 5, "$.x": 6}],
 "keys_empty": [{"": 1, " ": 2, "x": {"": {"": 3}}}],
 "keys_unicode": [{"名前": "太郎", "emoji😀": 1, "ключ": [1, 2], "\u0000": 1, "a\nb": 2, "a\"b": 3, "'": 4}],
 "keys_proto": [{"__proto__": 1, "constructor": 2, "$ref": "#/x", "$defs": {"a": 1}, "properties": {"type": "string"}}],
 "nums_big": [{"a": 12345678901234567890123, "b": 1e400 if False else 1.7976931348623157e308, "c": -9223372036854775809, "d": 9223372036854775807, "e": 0.1, "f": 1e-320}],
 "num_huge_literal": "RAW:{\"a\": 1e999999, \"b\": 123456789012345678901234567890.5, \"c\": -0.0}",
 "nan_inf": "RAW:{\"a\": NaN, \"b\": Infinity, \"c\": -Infinity}",
 "mixed_types": [{"v": 1}, {"v": "x"}, {"v": None}, {"v": [1]}, {"v": {"k": 1}}, {"v": True}, {"v": 1.5}],
 "mixed_int_float": [{"v": 1}, {"v": 2.5}, {"v": 3}],
 "mixed_arr_elems": [{"v": [1, "a", None, {"x": 1}, [2], True]}],
 "empty_things": [{}, {"a": [], "b": {}, "c": "", "d": None}],
 "root_array": [[1, 2, 3]], "root_scalar": [5], "root_string": ["hello"], "root_null": [None],
 "recursive_same": [{"name": "r", "children": [{"name": "a", "children": [{"name": "b", "children": []}]}, {"name": "c", "children": []}]}],
 "recursive_diff": [{"nodes": [{"id": 1, "kids": [{"id": 2, "x": 1, "kids": [{"id": 3, "y": "s", "kids": []}]}]}]}],
 "recursive_deep_tree": [nest_t for nest_t in [ (lambda n: (lambda f: f(f, n))(lambda f, k: {"label": str(k), "children": [] if k == 0 else [f(f, k-1)]}))(40)]],
 "map_vs_record": [{"by": {"AAPL": {"p": 1}, "MSFT": {"p": 2}}}, {"by": {"GOOG": {"p": 3}, "TSLA": {"p": 4}}}],
 "map_numeric_keys": [{"m": {"1": 1, "2": 2, "3": 3}}, {"m": {"4": 1, "5": 2}}],
 "record_same_keys": [{"o": {"a": 1, "b": 2}}, {"o": {"a": 3, "b": 4}}],
 "map_overlap": [{"o": {"a": 1, "b": 2}}, {"o": {"a": 3, "c": 4}}],
 "many_keys_one": [{"m": {f"k{i}": i for i in range(500)}}],
 "dates": [{"d": "2026-02-30"}, {"d": "2026-13-01"}, {"d": "2026-01-01T25:00:00Z"}],
 "dates_ok": [{"d": "2026-02-28", "t": "2026-02-28T10:00:00Z", "u": "123e4567-e89b-12d3-a456-426614174000", "e": "a@b.co", "c": "USD"}],
 "strings_long": [{"s": "x" * 1000000}],
 "enum_edge": [{"s": "a"}, {"s": "b"}, {"s": "a"}, {"s": "b"}, {"s": "a"}, {"s": "b"}],
 "bool_only": [{"b": True}, {"b": False}],
 "null_only": [{"n": None}, {"n": None}],
 "dup_keys_raw": "RAW:{\"a\": 1, \"a\": \"two\"}",
 "bigint_key_order": [{"b": 1, "a": 2, "c": {"z": 1, "y": 2}}],
 "all_null_arr": [{"a": [None, None]}],
 "arr_of_arr_of_obj": [{"g": [[{"a": 1}, {"a": 2}], [{"a": 3, "b": 4}]]}],
 "unicode_values": [{"s": "😀", "t": "‮", "u": "é"}],
}
def run(name, samples):
    if samples is None:
        if name == "depth5000": raw = '{"samples":[{"name":"d","document":' + '{"a":' * 5000 + '1' + '}' * 5000 + '}]}'
        st, b = call("POST", "/api/v1/builder/shape", raw=raw.encode())
        return st, b[:200].decode("utf-8", "replace"), None
    if isinstance(samples, str):
        raw = '{"samples":[{"name":"raw","document":' + samples[4:] + '}]}'
        st, b = call("POST", "/api/v1/builder/shape", raw=raw.encode())
        docs = None
    else:
        body = {"samples": [{"name": f"s{i}.json", "document": d} for i, d in enumerate(samples)]}
        st, b = call("POST", "/api/v1/builder/shape", body=body); docs = samples
    try: out = json.loads(b)
    except Exception: return st, b[:200].decode("utf-8", "replace"), None
    if st != 200: return st, str(out)[:200], None
    msg = ""
    if docs:
        try:
            v = Draft202012Validator(out["schema"])
            bad = [i for i, d in enumerate(docs) if not v.is_valid(d)]
            msg = "INVALID samples %s: %s" % (bad, (lambda e: e.message[:120] if e else "")(next(iter(v.iter_errors(docs[bad[0]])), None))) if bad else "valid"
        except Exception as e: msg = "validator-exc " + repr(e)[:150]
    return st, msg, out
if __name__ == "__main__":
    for n, s in CASES.items():
        if len(sys.argv) > 1 and n not in sys.argv[1:]: continue
        st, msg, out = run(n, s)
        extra = ""
        if out:
            paths = out["report"]["paths"]; extra = f"paths={len(paths)} conflicts={len(out['report']['conflicts'])} size={len(json.dumps(out))}"
        print(f"{n:22} {st} {msg[:160]} {extra}")
