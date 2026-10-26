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

"""Tests for the pack.yaml form: section (query by form): what pack check says about it, what the pack tools write, and that every pack in the
repository declares forms that pass. No Java needed."""
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import drishti  # noqa: E402
import packform as PF  # noqa: E402

ROOT = HERE.parent


def manifest(form, kinds=("trade",)):
    return {"pack": "demo", "kinds": list(kinds), "form": form}


class ValidateTest(unittest.TestCase):
    def test_a_good_form_has_no_problems(self):
        form = {"trade": [{"path": "tradeId", "type": "id"},
                          {"path": "mtm", "type": "amount", "operators": [">", "between"], "default": "1m", "order": 2, "group": "Money", "help": "h", "required": False},
                          {"path": "book", "type": "enum", "values": ["A", "B"]}, {"path": "counterparty.name"}, {"path": "$.status", "hidden": True}]}
        self.assertEqual(PF.validate(manifest(form)), [])
        self.assertEqual(PF.validate({"kinds": ["trade"]}), [])                       # no form: nothing to check
        self.assertEqual(PF.validate(manifest({"trade": {"calendar": "USNY", "fields": [{"path": "a"}]}})), [])

    def test_problems_are_named_clearly(self):
        bad = {"nope": [{"path": "a"}],
               "trade": [{"path": "a b"}, {"path": "x", "type": "money"}, {"path": "y", "type": "text", "operators": ["between"]}, {"path": "z", "values": ["q"]},
                         {"path": "w", "order": "first"}, {"path": "v", "colour": "red"}, {"path": "u", "required": "yes"}, "oops", {"path": "x"}, {"path": "x"},
                         {"path": "t", "required": True, "hidden": True}]}
        text = "\n".join(PF.validate(manifest(bad)))
        for part in ["'nope' is not one of the pack's kinds", "form.trade.fields[0]: path 'a b' is not a field path", "type 'money' is not one of text, number",
                     "operator 'between' does not suit type text (allowed: equals, contains, starts, regex)", "values belongs to type enum", "order must be a whole number",
                     "unknown key 'colour'", "required must be true or false", "fields[7] must be a mapping", "x appears twice",
                     "a required field that is hidden needs a default"]:
            self.assertIn(part, text)
        self.assertIn("must be a mapping of kind", PF.validate({"form": ["x"]})[0])
        self.assertIn("needs a non-empty list of fields", PF.validate(manifest({"trade": "text"}))[0])
        self.assertIn("unknown key 'colour'", PF.validate(manifest({"trade": {"colour": 1, "fields": [{"path": "a"}]}}))[0])

    def test_the_cli_check_reads_it(self):
        with tempfile.TemporaryDirectory() as t:
            pack = pathlib.Path(t) / "demo"
            pack.mkdir()
            (pack / "pack.yaml").write_text("pack: demo\nkinds: [trade]\nform:\n  trade:\n    - {path: mtm, type: money}\n", encoding="utf-8")
            problems = drishti.check_form_block(pack)
            self.assertEqual(len(problems), 1)
            self.assertIn("pack.yaml form.trade.fields[0] (mtm): type 'money'", problems[0])
            (pack / "pack.yaml").write_text("pack: demo\nkinds: [trade]\nform:\n  trade:\n    - {path: mtm, type: amount}\n", encoding="utf-8")
            self.assertEqual(drishti.check_form_block(pack), [])


class WriteTest(unittest.TestCase):
    def test_types_come_from_values_then_names(self):
        self.assertEqual(PF.type_of("flag", [True, False]), "bool")
        self.assertEqual(PF.type_of("mtm", [1, 2.5]), "amount")
        self.assertEqual(PF.type_of("count", [1, 2]), "number")
        self.assertEqual(PF.type_of("when", ["2026-01-02", "2026-02-03T00:00:00Z"]), "date")
        self.assertEqual(PF.type_of("ccy", ["USD", "EUR", "USD", "EUR"]), "enum")
        self.assertEqual(PF.type_of("tradeId", ["a", "b"]), "id")
        self.assertEqual(PF.type_of("note", [f"text {i}" for i in range(30)]), "text")
        self.assertEqual([PF.type_of(n) for n in ("maturityDate", "isLive", "tradeId", "status", "notional", "growth", "name")],
                         ["date", "bool", "id", "enum", "amount", "number", "text"])

    def test_forms_from_documents_and_columns(self):
        docs = [{"id": "A-1", "status": "LIVE", "mtm": 5.5, "nested": {"x": 1}, "tags": ["a"]}, {"id": "A-2", "status": "LIVE", "mtm": 7, "nested": {}}]
        form = PF.form_from_documents(docs, 10, "id")
        self.assertEqual([(f["path"], f["type"]) for f in form], [("id", "id"), ("status", "enum"), ("mtm", "amount")])      # objects and lists stay out
        self.assertEqual(PF.validate(manifest({"trade": form})), [])
        cols = PF.form_from_columns(["name", "totalDeposits", "reviewDue"], "customerId")
        self.assertEqual([(f["path"], f["type"]) for f in cols], [("customerId", "id"), ("name", "text"), ("totalDeposits", "amount"), ("reviewDue", "date")])

    def test_every_pack_in_the_repository_declares_forms_that_pass(self):
        import yaml
        found = 0
        for yml in sorted((ROOT / "config" / "packs").glob("*/pack.yaml")):
            if yml.parent.name == "compute-demo":     # computed kinds only (the reactive engines' demo): nothing to search by form
                continue
            m = yaml.safe_load(yml.read_text(encoding="utf-8"))
            self.assertEqual(PF.validate(m), [], yml.parent.name)
            self.assertTrue(m.get("form"), f"{yml.parent.name} has no form: section")
            self.assertEqual(set(m["form"]) - set(m["kinds"]), set(), yml.parent.name)
            found += 1
        self.assertEqual(found, 9)                    # the seven shipped packs, us-equities and gleif (retired ones: examples/packs)


if __name__ == "__main__":
    unittest.main()
