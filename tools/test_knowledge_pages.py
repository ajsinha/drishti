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

"""tools/knowledge.py: the Knowledge pages' Glossary sections are generated from docs/knowledge/glossary.yaml, deterministically, and
the glossary itself is valid (every domain has its page, no word names two terms, no bare <placeholder> in a meaning)."""
from __future__ import annotations

import importlib.util
import re
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("knowledge", Path(__file__).resolve().parent / "knowledge.py")
K = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(K)


class KnowledgeTest(unittest.TestCase):
    def setUp(self):
        self.data = K.load()

    def test_the_glossary_is_valid(self):
        self.assertEqual([], K.problems(self.data))
        self.assertGreaterEqual(len(self.data["terms"]), 100)
        for domain in ("market-risk", "counterparty-credit-risk"):          # the two pages the product owner asked for first
            self.assertGreaterEqual(sum(1 for e in self.data["terms"] if e["domain"] == domain), 20, domain)

    def test_the_pages_are_current(self):
        self.assertEqual(0, K.main(["--check"]))

    def test_rendering_is_deterministic(self):
        self.assertEqual(K.rendered(self.data), K.rendered(K.load()))

    def test_every_entry_has_its_anchor_on_its_page_and_the_home_links_it(self):
        home = (K.DIR / "README.md").read_text(encoding="utf-8")
        for e in self.data["terms"]:
            page = (K.DIR / self.data["domains"][e["domain"]]["page"]).read_text(encoding="utf-8")
            self.assertIn(f'<a id="{K.anchor(e["term"])}"></a>', page, e["term"])
            self.assertIn(f"#{K.anchor(e['term'])})", home, e["term"])

    def test_the_anchor_rule(self):
        self.assertEqual("term-p-l-vector", K.anchor("P&L vector"))
        self.assertEqual("term-sa-ccr", K.anchor("SA-CCR"))
        self.assertEqual("term-var", K.anchor("VaR"))

    def test_a_word_that_names_two_terms_and_a_bare_placeholder_are_refused(self):
        data = {"domains": self.data["domains"], "terms": [
            {"term": "A", "synonyms": ["same"], "domain": "market-risk", "meaning": "one"},
            {"term": "B", "synonyms": ["Same"], "domain": "market-risk", "meaning": "two <id> here"},
            {"term": "C", "domain": "nowhere", "meaning": "three `<id>` is fine", "colour": "red"}]}
        bad = "\n".join(K.problems(data))
        self.assertIn("'same' names both A and B", bad)
        self.assertIn("B: a <placeholder> outside", bad)
        self.assertIn("C: unknown domain nowhere", bad)
        self.assertIn("C: unknown fields ['colour']", bad)
        self.assertNotIn("C: a <placeholder>", bad)

    def test_a_section_has_its_terms_in_file_order_with_their_commands(self):
        section = K.domain_section(self.data, "counterparty-credit-risk")
        terms = [e["term"] for e in self.data["terms"] if e["domain"] == "counterparty-credit-risk"]
        found = re.findall(r'<a id="(term-[a-z0-9-]+)"></a>', section)
        self.assertEqual([K.anchor(t) for t in terms], found)
        self.assertIn("`NSET NS-ALDERSHOT-LDN <GO>`", section)


if __name__ == "__main__":
    unittest.main()
