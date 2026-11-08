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

"""The data lake guide's .ready file and its push examples (docs/guides/DATA_LAKE_GUIDE.md sections 3.4 and 4.0) against the code: the
complete example names exactly the keys the inbox and the command-line loaders accept (ReadyNote.KEYS in Java, READY_KEYS in Python, the
same list), with values those parsers take; the Python and Java push programs shown in the guide are the checked-in, tested files."""
import importlib.util
import re

import pytest
import yaml

from conftest import CONSOLE

pytestmark = pytest.mark.static

ROOT = CONSOLE.parent
GUIDE = ROOT / "docs" / "guides" / "DATA_LAKE_GUIDE.md"
EXAMPLES = ROOT / "docs" / "guides" / "examples" / "lake-push"
JAVA_KEYS = ROOT / "drishti-dataload" / "src" / "main" / "java" / "com" / "ash" / "drishti" / "dataload" / "ReadyNote.java"


def _feedroute():
    spec = importlib.util.spec_from_file_location("drishti_feedroute", ROOT / "tools" / "lake" / "feedroute.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _section(title: str) -> str:
    text = GUIDE.read_text(encoding="utf-8")
    start = text.index(title)
    nxt = re.search(r"^#{2,3} ", text[start + len(title):], re.M)
    return text[start: start + len(title) + (nxt.start() if nxt else len(text))]


def test_the_java_and_python_key_lists_are_one_list():
    java = re.search(r"KEYS = List\.of\(([^;]+)\);", JAVA_KEYS.read_text(encoding="utf-8")).group(1)
    assert tuple(re.findall(r'"([a-z0-9-]+)"', java)) == _feedroute().READY_KEYS


def test_the_complete_example_has_every_key_and_parses():
    block = re.search(r"```yaml\n(.*?)```", _section("### 4.0 A complete `.ready` file, every key"), re.S).group(1)
    doc = yaml.safe_load(block)
    keys = _feedroute().READY_KEYS
    assert set(doc) == set(keys), f"the guide's example: missing {sorted(set(keys) - set(doc))}, unknown {sorted(set(doc) - set(keys))}"
    assert re.fullmatch(r"[a-z0-9][a-z0-9_-]*", doc["lake"])
    assert str(doc["business-date"]) == "2026-11-06" and doc["mode"] in ("replace", "append")
    assert re.fullmatch(r"[0-9a-f]{64}", str(doc["sha256"])) and isinstance(doc["rows"], int)
    assert isinstance(doc["folders"], dict) and isinstance(doc["hold"], bool) and isinstance(doc["new-table"], bool)


def test_the_example_marker_reads_with_the_real_parser(tmp_path):
    block = re.search(r"```yaml\n(.*?)```", _section("### 4.0 A complete `.ready` file, every key"), re.S).group(1)
    data = tmp_path / "trade_endur.2026-11-06.jsonl.gz"
    data.write_bytes(b"")
    (tmp_path / (data.name + ".ready")).write_text(re.sub(r"^sha256:.*$", "", block, flags=re.M), encoding="utf-8")
    out = _feedroute().read_marker(data)
    assert out["lake"] == "risklake" and out["mode"] == "overwrite-dates" and out["table"] == "trade_endur"


@pytest.mark.parametrize("name", ["push_inbox.py", "PushInbox.java"])
def test_the_guide_shows_the_tested_push_programs(name):
    code = (EXAMPLES / name).read_text(encoding="utf-8")
    body = code.split("\n", 15)[-1] if name.endswith(".py") else code[code.index("import java"):]
    assert body.strip() in GUIDE.read_text(encoding="utf-8"), f"DATA_LAKE_GUIDE.md section 3.4 must show {name} as checked in (from the imports on)"
