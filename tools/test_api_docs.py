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

"""tools/api_docs.py: the checks of the API references (IMPLEMENTATION_PLAN row 2g), and the Python surfaces held to them."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import api_docs  # noqa: E402

PAGE = """<section class="class-description" id="class-description"><div class="type-signature">x</div>{desc}</section>
<section class="detail" id="size()"><h3>size</h3><div class="member-signature">int size()</div>{size}</section>
<section class="detail" id="&lt;init&gt;(int)"><h3>Foo</h3><div class="member-signature">Foo(int)</div></section>"""


class JavaCheck(unittest.TestCase):
    def test_a_member_or_type_without_a_block_is_reported(self):
        page = PAGE.format(desc='<div class="block">A foo.</div>', size='<div class="block">The size.</div>')
        self.assertEqual(api_docs.undocumented_in_page(page, "a.Foo"), ["a.Foo#<init>(int)"])
        page = PAGE.format(desc="", size="")
        self.assertEqual(api_docs.undocumented_in_page(page, "a.Foo"), ["a.Foo", "a.Foo#size()", "a.Foo#<init>(int)"])

    def test_a_package_without_a_description_is_reported(self):
        self.assertEqual(api_docs.undocumented_in_page("<main>no description</main>", "a.b (package)"), ["a.b (package)"])
        ok = '<section class="package-description" id="package-description"><div class="block">The b things.</div></section>'
        self.assertEqual(api_docs.undocumented_in_page(ok, "a.b (package)"), [])


class PythonCheck(unittest.TestCase):
    def test_docstrings_and_type_hints_are_required_of_public_names_only(self):
        src = '''"""m."""
def ok(a: int) -> int:
    """Fine."""
def bare(a, b: int):
    pass
def _private(x):
    pass
class K:
    def m(self, x: int) -> None:
        pass
    @staticmethod
    def s(x: int) -> int:
        """Fine."""
'''
        self.assertEqual(api_docs.python_problems(src, "m"), [
            "m.bare: no docstring", "m.bare: no type hint for a", "m.bare: no return type", "m.K: no docstring", "m.K.m: no docstring"])

    def test_all_says_what_is_public(self):
        src = '"""m."""\n__all__ = ["ok"]\ndef ok() -> None:\n    """Fine."""\ndef handler(a):\n    pass\n'
        self.assertEqual(api_docs.python_problems(src, "m"), [])

    def test_every_python_surface_is_documented(self):
        self.assertEqual(api_docs.check_python(api_docs.config()), [])


class Config(unittest.TestCase):
    def test_every_java_module_documents_itself_in_its_pom(self):
        for m in api_docs.config()["java"]:
            pom = (api_docs.ROOT / m["module"] / "pom.xml").read_text(encoding="utf-8")
            self.assertIn("<drishti.apidocs.skip>false</drishti.apidocs.skip>", pom, m["module"])
            self.assertIn("<drishti.apidocs.packages>", pom, m["module"])

    def test_the_version_is_the_poms(self):
        self.assertRegex(api_docs.version(), r"^\d+\.\d+\.\d+")


if __name__ == "__main__":
    unittest.main()
