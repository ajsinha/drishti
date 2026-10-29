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

"""lake build --engine java (javaload.py): the build's options as the parallel loader's configuration, when --engine auto picks the loader,
and secrets kept out of the configuration file. The loads themselves are tested in drishti-lakeload (PythonPathEqualityTest runs lake build
both ways and compares the tables)."""
import gzip
import json
import pathlib
import sys
from datetime import date

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import build  # noqa: E402
import javaload  # noqa: E402


def args(**kw):
    a = build.build_parser().parse_args([])
    for k, v in kw.items():
        setattr(a, k, v)
    return a


def test_the_options_become_the_loaders_configuration(tmp_path):
    a = args(src=["/data/pv"], recursive=True, iceberg_set=["s3.region=eu-west-2", "token=s3cr3t"])
    p = {"key": "POSITION_ID", "date": "BUSINESS_DATE", "domain": "risk", "kind": "scenario-pv", "table": None, "promote": ["DESK", "LEGAL_ENTITY"],
         "partition_by": ["LEGAL_ENTITY"], "vectors": ["SCENARIO_PV"], "flatten": {"NOTIONAL": "LEGS[0].NOTIONAL"}}
    env = {}
    c = javaload.config(a, p, None, "/lake", True, tmp_path, env)
    assert c["source"] == {"files": ["/data/pv"], "recursive": True, "id": "POSITION_ID", "date": "BUSINESS_DATE",
                           "promote": ["LEGAL_ENTITY", "DESK"], "vectors": ["SCENARIO_PV"], "flatten": {"NOTIONAL": "LEGS[0].NOTIONAL"},
                           "partition-by": [], "latest-by": ""}   # Iceberg keeps the partition field as a column, as lake build's Iceberg path does
    assert javaload.config(a, {**p, "latest_by": "COMPUTED_AT"}, None, "/lake", True, tmp_path, {})["source"]["latest-by"] == "COMPUTED_AT"
    assert c["target"]["format"] == "iceberg" and c["target"]["lake"] == "/lake" and c["target"]["kind"] == "scenario-pv"
    assert c["target"]["iceberg"]["s3.region"] == "eu-west-2"
    assert c["target"]["iceberg"]["token"] == "${DRISHTI_LOADER_SECRET_1}" and env["DRISHTI_LOADER_SECRET_1"] == "s3cr3t"
    assert "s3cr3t" not in json.dumps(c)
    assert c["memory"]["spill-dir"] == str(tmp_path / "spill")

    d = javaload.config(args(src=["x.jsonl"]), {**p, "table": "pv_endur"}, date(2026, 10, 28), "/lake", False, tmp_path, {})
    assert d["source"]["as-of"] == "2026-10-28" and "date" not in d["source"]
    assert d["source"]["partition-by"] == ["LEGAL_ENTITY"] and d["target"]["table"] == "pv_endur"


def test_auto_picks_the_loader_only_for_large_json_lines(tmp_path, monkeypatch):
    small = tmp_path / "small.jsonl.gz"
    with gzip.open(small, "wt", encoding="utf-8") as fh:
        fh.write('{"id": 1}\n')
    arr = tmp_path / "array.json"
    arr.write_text('[{"id": 1}]', encoding="utf-8")
    monkeypatch.setattr(javaload, "command", lambda jar, java: ("java", ["-cp", "x", javaload.LOADER_MAIN]))
    use, why = javaload.wanted(args(java_above_mb=None), [small], None, None)
    assert not use and "Python path is the default" in why                 # not asked: never the loader, however large
    use, why = javaload.wanted(args(java_above_mb=1024), [small], None, None)
    assert not use and "--java-above-mb" in why
    use, why = javaload.wanted(args(java_above_mb=0), [small], None, None)
    assert use
    use, why = javaload.wanted(args(java_above_mb=0), [small, arr], None, None)
    assert not use and "not JSON Lines" in why
    use, why = javaload.wanted(args(java_above_mb=0, mode="append"), [small], None, None)
    assert not use and "append" in why
    assert javaload.is_jsonl(small) and not javaload.is_jsonl(arr)


def test_s3_input_needs_the_loader(tmp_path):
    code, s = build.run(args(src=["s3://bucket/pv/"], lake=str(tmp_path / "lake"), domain="risk", kind="pv", date="BUSINESS_DATE", quiet=True),
                        say=lambda *x: None)
    assert code == build.EXIT_FAILED and "--engine java" in s["error"]
