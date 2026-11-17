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

"""The product version, from its one home (.mvn/maven.config; drishti-console/core/product_version.py reads it).

  python3 tools/version.py                print the version (2.9.11.i5)
  python3 tools/version.py --tag          print the release tag (v2.9.11.i_5; 2.9.11.i7a is v2.9.11.i_7a)
  python3 tools/version.py --check TAG    exit 0 when TAG is the current version's tag, else 1 (the release check)
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("drishti_product_version", Path(__file__).resolve().parents[1] / "drishti-console" / "core" / "product_version.py")
_PV = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_PV)
current, tag_of = _PV.current, _PV.tag_of


def main(argv: list[str]) -> int:
    version = current(Path(__file__), default="")
    if not version:
        print("no version file (.mvn/maven.config) above this tool", file=sys.stderr)
        return 2
    if argv[:1] == ["--tag"]:
        print(tag_of(version))
    elif argv[:1] == ["--check"] and len(argv) == 2:
        if argv[1] != tag_of(version):
            print(f"release check failed: tag {argv[1]} but the version file says {version} (tag {tag_of(version)})", file=sys.stderr)
            return 1
    elif argv:
        print(__doc__)
        return 2
    else:
        print(version)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
