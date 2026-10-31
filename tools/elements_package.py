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

"""Builds the npm package of Drishti Elements (docs/architecture/CLIENT_RENDERER.md › Drishti Elements: the design, section 13; step 12).

    python3 tools/elements_package.py            # write drishti-console/web/elements/package/ and web/elements/integrity.json
    python3 tools/elements_package.py --check    # exit 1 when what is committed differs from what the sources make (what the guard test does)
    python3 tools/elements_package.py --docs     # print the element reference tables (for docs/architecture/CLIENT_RENDERER.md)

Run it after ANY change under drishti-console/web/embed/ or core/element_api.py. A change of a module needs a new VERSION in core/element_dist.py (and the
`version` in web/embed/drishti-elements.js): a version always names the same bytes. Publishing to a registry is the release build's job
(`npm pack` in the package folder); this tool only makes the folder, with no network and no Node."""
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "drishti-console"))
from core import element_api as api  # noqa: E402
from core import element_dist as dist  # noqa: E402
from core import element_package as pkg  # noqa: E402


def main(argv: list[str]) -> int:
    if "--docs" in argv:
        print("\n".join(api.markdown_tables(e) for e in api.ELEMENTS))
        return 0
    files = pkg.build()
    if "--check" in argv:
        problems = []
        for rel, data in files.items():
            path = dist.PACKAGE / rel
            if not path.is_file() or path.read_bytes() != data:
                problems.append(rel)
        have = {str(p.relative_to(dist.PACKAGE)) for p in dist.PACKAGE.rglob("*") if p.is_file()} if dist.PACKAGE.exists() else set()
        problems += sorted(have - set(files))
        if not dist.LOCK.is_file() or dist.LOCK.read_text(encoding="utf-8") != dist.lock_text():
            problems.append("web/elements/integrity.json")
        if problems:
            print("the package is stale (" + ", ".join(problems[:6]) + (", ..." if len(problems) > 6 else "") + "): python3 tools/elements_package.py")
            return 1
        print(f"up to date: @drishti/elements {dist.VERSION}")
        return 0
    if dist.PACKAGE.exists():
        shutil.rmtree(dist.PACKAGE)
    for rel, data in files.items():
        path = dist.PACKAGE / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    dist.LOCK.write_text(dist.lock_text(), encoding="utf-8")
    print(f"@drishti/elements {dist.VERSION}: {len(files)} files in {dist.PACKAGE.relative_to(dist.WEB.parent.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
