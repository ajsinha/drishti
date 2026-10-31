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

"""The Python runner's sandbox and results (docs/guides/BI_GUIDE.md): no browser, no server; real worker processes on small Arrow tables."""
import asyncio
import glob
import io
import os
import sys
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from core.config import Settings  # noqa: E402
from core.pyrunner import PyError, PyRunner  # noqa: E402


def arrow(**cols) -> bytes:
    table = pa.table(cols)
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, table.schema) as w:
        w.write_table(table)
    return sink.getvalue()


def runner(**over) -> PyRunner:
    cfg = {"workers": 2, "queue_seconds": 2, "packages": ["pandas", "numpy", "pyarrow"], "max_output_bytes": 20000, **over}
    return PyRunner(Settings({"bi": {"python": cfg}}))


def item(source: str, interface="measure", **over) -> dict:
    m = {"name": "t", "interface": interface, "entry": "compute" if interface == "measure" else "render", "inputs": ["pnl"], "packages": ["pandas"], "target": "server",
         "timeout": 10, "memory": 512, "output": "number" if interface == "measure" else "echarts", "params": {}, "source": source}
    m.update(over)
    return {"manifest": m}


DATA = arrow(desk=["A", "A", "B", "B", "B"], pnl=[1.0, 3.0, 2.0, 4.0, 6.0], secret=["x"] * 5, id=list("12345"))


def run(r, it, data=DATA, **kw):
    return asyncio.run(r.run(it, data, **kw))


def refused(r, it, code, data=DATA, **kw):
    with pytest.raises(PyError) as e:
        run(r, it, data, **kw)
    assert e.value.code == code, (e.value.code, e.value.detail)
    return e.value


def test_measure_is_one_number_per_group():
    out = run(runner(), item("def compute(table, params):\n    return float(table['pnl'].std(ddof=1))\n"), group_by=["desk"])
    assert out["kind"] == "measure" and out["by"] == ["desk"]
    assert [r[0] for r in out["rows"]] == ["A", "B"]
    assert out["rows"][0][1] == pytest.approx(1.4142135, rel=1e-5) and out["rows"][1][1] == pytest.approx(2.0)


def test_measure_groups_default_to_the_other_columns_and_params_reach_the_function():
    out = run(runner(), item("def compute(table, params):\n    return float(table['pnl'].sum()) * params['k']\n", params={"k": 2}), group_by=None, params={"k": 3})
    assert out["by"] == ["desk", "secret"]
    assert sorted(r[-1] for r in out["rows"]) == [12.0, 36.0]                      # 1+3 and 2+4+6, times the visual's k=3


def test_the_function_sees_only_the_declared_columns():
    out = run(runner(), item("def compute(table, params):\n    return len(table.columns) * 10 + ('secret' in table.columns)\n"), group_by=["desk"])
    assert [r[1] for r in out["rows"]] == [10, 10]                                  # pnl only: not secret, not id, not desk


def test_a_measure_must_return_a_number():
    e = refused(runner(), item("def compute(table, params):\n    return 'many'\n"), "DRS-9071", group_by=["desk"])
    assert "one number per group" in e.detail


def test_the_traceback_names_only_the_authors_lines():
    e = refused(runner(), item("def compute(table, params):\n    return 1 / 0\n"), "DRS-9071", group_by=["desk"])
    assert "ZeroDivisionError" in e.detail and "<python-item>" in e.traceback and "pyrunner_worker" not in e.traceback


def test_visual_echarts_option_has_formatters_removed():
    src = ("def render(table, params):\n    return {'tooltip': {'formatter': '<b>{b}</b>', 'trigger': 'axis'}, 'series': [{'type': 'bar', 'data': list(table['pnl'])}]}\n")
    out = run(runner(), item(src, "visual"))
    assert out["kind"] == "echarts" and out["option"]["series"][0]["data"] == [1.0, 3.0, 2.0, 4.0, 6.0]
    assert "formatter" not in out["option"]["tooltip"] and out["option"]["tooltip"]["trigger"] == "axis"


def test_visual_svg_and_png_and_script_in_svg_refused():
    out = run(runner(), item("def render(table, params):\n    return '<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"5\" height=\"5\"/></svg>'\n", "visual", output="svg"))
    assert out["kind"] == "svg" and out["svg"].startswith("<svg")
    png = "def render(table, params):\n    return b'\\x89PNG\\r\\n\\x1a\\n' + b'0' * 20\n"
    assert run(runner(), item(png, "visual", output="png"))["kind"] == "png"
    refused(runner(), item("def render(table, params):\n    return '<svg><script>alert(1)</script></svg>'\n", "visual", output="svg"), "DRS-9073")


def test_no_network():
    src = "import socket\ndef compute(table, params):\n    socket.create_connection(('127.0.0.1', 9), timeout=1)\n    return 1\n"
    e = refused(runner(), item(src), "DRS-9073", group_by=["desk"])                      # socket is not on the author's import list


def test_a_socket_is_refused_even_through_a_library():
    src = "import pandas\ndef compute(table, params):\n    imp = pandas.read_csv.__globals__['__builtins__']['__import__']        # a way round the author's import guard, through a library's own builtins\n    imp('socket').socket()\n    return 1\n"
    e = refused(runner(), item(src), "DRS-9073", group_by=["desk"])
    assert "network" in e.detail.lower() or "not available" in e.detail.lower()


def test_files_outside_the_scratch_folder_are_refused():
    target = os.path.join(tempfile.gettempdir(), "drishti-py-escape-test.txt")
    write = f"def compute(table, params):\n    open({target!r}, 'w').write('x')\n    return 1\n"
    refused(runner(), item(write), "DRS-9073", group_by=["desk"])
    assert not os.path.exists(target)
    refused(runner(), item("def compute(table, params):\n    return len(open('/etc/passwd').read())\n"), "DRS-9073", group_by=["desk"])
    ok = "def compute(table, params):\n    open('scratch.txt', 'w').write('x')\n    return len(open('scratch.txt').read())\n"
    assert run(runner(), item(ok), group_by=["desk"])["rows"][0][1] == 1                  # the scratch folder is writable


def test_processes_and_other_packages_are_refused():
    refused(runner(), item("import os\ndef compute(table, params):\n    return 1\n"), "DRS-9073", group_by=["desk"])
    refused(runner(), item("import subprocess\ndef compute(table, params):\n    return 1\n"), "DRS-9073", group_by=["desk"])
    refused(runner(), item("import pandas\ndef compute(table, params):\n    pandas.io.common.os.system('true')\n    return 1\n"), "DRS-9073", group_by=["desk"])


def test_package_allow_list_is_enforced_before_a_worker_starts():
    e = refused(runner(), item("def compute(table, params):\n    return 1\n", packages=["requests"]), "DRS-9073", group_by=["desk"])
    assert "allow-list" in e.detail
    r = runner(packages=["pandas"])
    assert run(r, item("def compute(table, params):\n    return 1\n"), group_by=["desk"])["rows"]


def test_wall_clock_and_cpu_limits_kill_the_worker():
    r = runner()
    e = refused(r, item("def compute(table, params):\n    while True:\n        pass\n", timeout=1), "DRS-9072", group_by=["desk"])
    assert e.status == 504 and "stopped" in e.detail
    e = refused(r, item("import time\ndef compute(table, params):\n    time.sleep(30)\n    return 1\n", timeout=1), "DRS-9072", group_by=["desk"])
    assert e.status == 504


def test_memory_limit():
    e = refused(runner(), item("def compute(table, params):\n    x = bytearray(900 * 1024 * 1024)\n    return len(x)\n", memory=64), "DRS-9072", group_by=["desk"])
    assert e.status == 413 and "memory" in e.detail


def test_output_limit():
    src = "def render(table, params):\n    return {'series': [{'data': list(range(50000))}]}\n"
    e = refused(runner(), item(src, "visual"), "DRS-9072")
    assert "bytes" in e.detail


def test_input_limit_and_busy_pool_and_switched_off():
    refused(runner(**{"max_input_bytes": 100}), item("def compute(table, params):\n    return 1\n"), "DRS-9072", group_by=["desk"])
    refused(runner(enabled=False), item("def compute(table, params):\n    return 1\n"), "DRS-9074", group_by=["desk"])

    async def two():
        r = runner(workers=1, **{"queue_seconds": 0.2})
        slow = item("import time\ndef compute(table, params):\n    time.sleep(2)\n    return 1\n", timeout=5)
        return await asyncio.gather(r.run(slow, DATA, group_by=["desk"]), r.run(slow, DATA, group_by=["desk"]), return_exceptions=True)
    results = asyncio.run(two())
    assert sum(isinstance(x, PyError) and x.code == "DRS-9074" for x in results) == 1
    assert sum(isinstance(x, dict) for x in results) == 1


def test_scratch_folders_are_removed():
    before = set(glob.glob(os.path.join(tempfile.gettempdir(), "drishti-py-*")))
    run(runner(), item("def compute(table, params):\n    return 1\n"), group_by=["desk"])
    refused(runner(), item("def compute(table, params):\n    return 1 / 0\n"), "DRS-9071", group_by=["desk"])
    assert set(glob.glob(os.path.join(tempfile.gettempdir(), "drishti-py-*"))) == before
