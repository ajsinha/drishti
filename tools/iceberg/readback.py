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

"""Reads Iceberg tables with pyiceberg and prints their rows as JSON, for drishti-iceberg's write interop test
(PyicebergReadsNativeTest): what drishti-iceberg writes, an independent reader reads.

    uv run --no-project --with pyiceberg --with pyiceberg-core --with pyarrow --with sqlalchemy python tools/iceberg/readback.py METADATA.json [...]

Prints one JSON object: metadata path to {"rows": [...], "snapshots": n}, values as fixtures.py records them.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fixtures import jsonable  # noqa: E402

from pyiceberg.table import StaticTable  # noqa: E402


def main(paths):
    out = {}
    for p in paths:
        t = StaticTable.from_metadata(p)
        rows = [{k: jsonable(v) for k, v in r.items()} for r in t.scan().to_arrow().to_pylist()]
        out[p] = {"rows": rows, "snapshots": len(t.metadata.snapshots), "format-version": t.metadata.format_version}
    print(json.dumps(out))


if __name__ == "__main__":
    main(sys.argv[1:])
