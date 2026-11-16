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

"""Child kinds (tools/packgen/childkinds.py): an array of objects made a kind of its own by `pack new`'s one pass and written by the one loader.

    python3 -m pytest tools/test_pack_new_children.py -q

The profile and the files store need nothing else; the Delta case needs deltalake (the drill's tools environment); the end-to-end case (the
pack's Sutras through `sutra lint` and `sutra test`, the BI tables and model through `tables check`) needs the built server jar. The BI files of
the fixed book below are kernel/drishti-server/src/test/resources/childtables/, where ChildTablesModelTest sums over them.
"""
from __future__ import annotations

import argparse
import glob
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "packgen"))
import childkinds as CK  # noqa: E402
import ingest_jsonl as IJ  # noqa: E402
import module_from_jsonl as MJ  # noqa: E402

PFJ = MJ.PFJ
FIXTURE = ROOT / "kernel" / "drishti-server" / "src" / "test" / "resources" / "childtables"
JARS = sorted(glob.glob(str(ROOT / "kernel/drishti-server/target/drishti-server-*-exec.jar")))


def book(n: int = 40) -> list[dict]:
    """A small book: LEGS (2 or 3 per deal, LEG_ID 1..), CASHFLOWS (0 to 2 per deal, no id), TAGS (a list of values)."""
    out = []
    for i in range(n):
        legs = [{"LEG_ID": j + 1, "LEG_TYPE": ["FIXED", "FLOAT"][j % 2], "CURRENCY": ["USD", "EUR", "GBP"][(i + j) % 3],
                 "NOTIONAL": 1000.0 * (i + 1) * (j + 1)} for j in range(2 + i % 2)]
        cfs = [{"PAYMENT_DATE": f"2027-0{k + 1}-15", "AMOUNT": 10.0 * (k + 1)} for k in range(i % 3)]
        out.append({"TRADE_ID": f"T{i:03d}", "PRODUCT_TYPE": ["IRS", "CCS"][i % 2], "SOURCE_SYSTEM": "ENDUR", "NOTIONAL": 1000.0 * (i + 1),
                    "LEGS": legs, "CASHFLOWS": cfs, "TAGS": ["a", "b"]})
    return out


def write(f: pathlib.Path, docs) -> pathlib.Path:
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text("".join(json.dumps(d) + "\n" for d in docs), encoding="utf-8")
    return f


def opts(inputs, **kw) -> argparse.Namespace:
    a = MJ.build_parser().parse_args([*map(str, inputs), "--name", "deals"])
    a.kind, a.key, a.match, a.workers = "deal", "TRADE_ID", "PRODUCT_TYPE", 1
    for k, v in kw.items():
        setattr(a, k, v)
    return a


def quiet(*_):
    pass


def ingest_args(src, **kw) -> argparse.Namespace:
    a = IJ.build_parser().parse_args(["--from", str(src)])
    for k, v in kw.items():
        setattr(a, k, v)
    return a


def pack_yaml(folder: pathlib.Path, an, connector: dict | None = None) -> pathlib.Path:
    """The pack.yaml `pack new` writes, its ingest block and (pack make) a connector."""
    import yaml
    kinds = sorted(an.prof.kinds)
    m = {"pack": "deals", "kinds": kinds,
         "ingest": {k: an.parents[k].ingest() if k in an.parents else {"key": an.keys[k], "snapshot": True} for k in kinds}}
    if connector:
        m["connectors"] = {"deals-store": connector}
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "pack.yaml").write_text(yaml.safe_dump(m, sort_keys=False), encoding="utf-8")
    return folder


class ChildKinds(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)
        self.src = write(self.dir / "in" / "deal.2026-11-03.jsonl", book())

    def tearDown(self):
        self.tmp.cleanup()

    def test_profiling_picks_up_nested_arrays(self):
        cands = CK.arrays(book())
        self.assertEqual(["CASHFLOWS", "LEGS"], list(cands))                       # TAGS holds values, not records
        self.assertEqual(2.5, cands["LEGS"]["avg"])
        self.assertEqual("LEG_ID", cands["LEGS"]["natural"])
        self.assertIsNone(cands["CASHFLOWS"]["natural"])
        an = PFJ.analyse(opts([self.src]), quiet)
        self.assertEqual(["deal-leg"], [c.kind for c in an.children["deal"]])    # automatic: 2.5 on average >= 2; CASHFLOWS 1.5 is not
        ch = an.parents["deal-leg"]
        self.assertEqual(("LEGS", "LEG_KEY", "TRADE_ID", "LEG_ID"), (ch.field, ch.key, ch.link, ch.natural))
        kp = an.prof.kinds["deal-leg"]
        self.assertEqual(100, kp.rows)                                             # every element of every deal
        self.assertTrue(kp.unique())
        self.assertEqual({"2026-11-03": 100}, dict(kp.dates))                      # its parent's date
        self.assertEqual({"string": 100}, kp.fields["TRADE_ID"].types)
        self.assertEqual({"number": 100}, kp.fields["NOTIONAL"].types)
        self.assertEqual("T000:1", kp.head[0]["LEG_KEY"])
        self.assertEqual(["deal-leg-default"], [g.name for g in an.groups if g.kind == "deal-leg"])
        self.assertIs(ch, next(g for g in an.groups if g.kind == "deal-leg").parent)
        self.assertNotIn("LEGS", PFJ.promoted(an, "deal"))                        # the array stays in the parent's document
        self.assertEqual("TRADE_ID", PFJ.promoted(an, "deal-leg")[0])

    def test_by_choice_none_and_the_threshold(self):
        an = PFJ.analyse(opts([self.src], child="CASHFLOWS"), quiet)
        self.assertEqual(["deal-cashflow"], list(an.parents))
        kp = an.prof.kinds["deal-cashflow"]
        self.assertEqual(39, kp.rows)                                              # 0 + 1 + 2, over and over
        self.assertEqual("T001:0", kp.head[0]["CASHFLOW_KEY"])                     # no natural key: the index
        self.assertEqual({}, PFJ.analyse(opts([self.src], child="none"), quiet).children)
        self.assertEqual({}, PFJ.analyse(opts([self.src], child_min_avg=0), quiet).children)
        self.assertEqual(["deal-cashflow", "deal-leg"], sorted(PFJ.analyse(opts([self.src], child_min_avg=1.5), quiet).parents))
        an = PFJ.analyse(opts([self.src], child="deal=LEGS,NOPE"), quiet)
        self.assertEqual(["deal-leg"], list(an.parents))
        self.assertTrue(any("--child NOPE" in w for w in an.why["deal"]))

    def test_several_files_and_workers_count_the_same(self):
        docs = book(60)
        files = [write(self.dir / "many" / f"deal.2026-11-0{d}.jsonl", docs[d * 20 - 20:d * 20]) for d in (1, 2, 3)]
        for workers in (1, 3):
            kp = PFJ.analyse(opts(files, workers=workers), quiet).prof.kinds["deal-leg"]
            self.assertEqual(150, kp.rows, workers)
            self.assertEqual({"2026-11-01": 50, "2026-11-02": 50, "2026-11-03": 50}, dict(kp.dates))
            self.assertTrue(kp.unique())

    def test_explode_keeps_the_parent_whole_and_counts_duplicate_ids(self):
        doc = {"ID": "D1", "LEGS": [{"LEG_ID": 7, "X": 1}, {"X": 2}, "not a record", {"LEG_ID": 7}]}
        ch = CK.Child("deal", "LEGS", "deal-leg", "LEG_KEY", "DEAL_ID", "ID", "LEG_ID")
        rows = CK.explode(doc, ch)
        self.assertEqual(["D1:7", "D1:1", "D1:7"], [r["LEG_KEY"] for r in rows])   # no LEG_ID: the index
        self.assertEqual({"LEG_KEY": "D1:7", "DEAL_ID": "D1", "LEG_ID": 7, "X": 1}, rows[0])
        self.assertEqual(4, len(doc["LEGS"]))
        self.assertEqual([], CK.explode({"LEGS": [{"A": 1}]}, ch))               # a parent without an id has no rows
        self.assertEqual("DEAL_ID", CK.link_of("deal", "id"))
        self.assertEqual(("ENTRY", "ADDRESS", "LEG"), (CK.singular("ENTRIES"), CK.singular("ADDRESSES"), CK.singular("LEGS")))

    def test_dry_run_shows_the_child_kinds_planned(self):
        a = opts([self.src], dry_run=True, out=self.dir / "mod", site=self.dir / "site")
        said = []
        self.assertEqual(0, MJ.run(a, None, said.append))
        text = "\n".join(said)
        self.assertIn("child kind LEGS -> deal-leg: one row per element, id LEG_KEY (TRADE_ID:LEG_ID), link TRADE_ID -> deal: 100 rows", text)
        self.assertIn("kind deal-leg: 100 rows", text)
        self.assertIn("binding (config/bindings.yaml): deal, deal-leg", text)
        self.assertIn("deals/deals.model.yaml", text)
        self.assertFalse((self.dir / "mod").exists() or (self.dir / "site").exists())

    def test_the_loader_writes_the_child_rows_beside_the_parent(self):
        an = PFJ.analyse(opts([self.src]), quiet)
        pack = pack_yaml(self.dir / "pack", an)
        root = self.dir / "files"
        IJ.ingest(ingest_args(self.src, pack=pack, store="files", root=root, kind="deal", mode="replace"), quiet)
        day = root / "deals" / "2026-11-03"
        legs = [json.loads(x) for x in (day / "deal-leg.jsonl").read_text().splitlines()]
        self.assertEqual(100, len(legs))
        first = next(x for x in legs if x["id"] == "T000:1")
        doc = json.loads(first["doc"])
        self.assertEqual(("deal-leg", "T000", 1000.0), (first["kind"], doc["TRADE_ID"], doc["NOTIONAL"]))
        deals = [json.loads(x) for x in (day / "deal.jsonl").read_text().splitlines()]
        self.assertEqual(40, len(deals))
        self.assertEqual(2, len(json.loads(deals[0]["doc"])["LEGS"]))              # the parent keeps its array

    def test_the_child_table_loads_into_delta(self):
        try:
            import deltalake
        except ImportError:
            self.skipTest("deltalake not installed")
        an = PFJ.analyse(opts([self.src]), quiet)
        cols = {k: PFJ.promoted(an, k) for k in an.prof.kinds}
        conn = {"plugin": "delta", "kinds": sorted(an.prof.kinds), "settings": {"root": "x", "domain": "deals",
                                                                                "layout": {k: {"columns": cols[k], "sort-by": "id"} for k in cols}}}
        pack = pack_yaml(self.dir / "pack", an, conn)
        lake = self.dir / "lake"
        IJ.ingest(ingest_args(self.src, pack=pack, lake=lake, kind="deal", mode="replace"), quiet)
        rows = deltalake.DeltaTable(str(lake / "deals" / "deal-leg")).to_pyarrow_table().to_pylist()
        self.assertEqual(100, len(rows))
        r = next(x for x in rows if x["id"] == "T001:3")
        self.assertEqual(("T001", "USD", 6000.0), (r["TRADE_ID"], r["CURRENCY"], r["NOTIONAL"]))
        self.assertEqual("2026-11-03", str(r["business_date"]))
        self.assertEqual(40, len(deltalake.DeltaTable(str(lake / "deals" / "deal")).to_pyarrow_table().to_pylist()))

    def test_bi_tables_and_model_relate_the_child_to_its_parent(self):
        an = PFJ.analyse(opts([self.src]), quiet)
        a = opts([self.src], version="1.0.0")
        files = MJ.bi_plan(a, an)
        self.assertEqual(["deals/deal_legs.table.yaml", "deals/deals.model.yaml", "deals/deals.table.yaml"], sorted(files))
        model = files["deals/deals.model.yaml"]
        self.assertIn("- { from: deal_leg.TRADE_ID, to: deal.TRADE_ID, cardinality: many-to-one, direction: both, name: deal_leg_deal }", model)
        self.assertIn('"Deal leg NOTIONAL": { expr: sum(deal_leg.NOTIONAL), format: amount0 }', model)
        for rel, text in files.items():                                            # the fixture the Java test sums over is this output
            self.assertEqual((FIXTURE / rel).read_text(encoding="utf-8"), text, rel)
        self.assertEqual({}, CK.bi_files("x", "1.0.0", an.prof.kinds, an.keys, {}, {}))

    @unittest.skipUnless(JARS, "needs the built server jar (kernel/drishti-server/target/drishti-server-*-exec.jar)")
    def test_the_pack_its_sutras_and_the_bi_files_pass_their_checks(self):
        a = opts([self.src], out=self.dir / "deals", force=True, jar=None, java=None)
        a.jar, a.java = PFJ.SG.find_jar(None), PFJ.SG.find_java(None)
        said = []
        rc = PFJ.run(a, said.append)
        self.assertEqual(0, rc, "\n".join(said[-30:]))                            # sutra lint and sutra test, the parent's and the child's Sutras
        import yaml
        m = yaml.safe_load((self.dir / "deals" / "pack.yaml").read_text(encoding="utf-8"))
        self.assertIn("deal-leg", m["kinds"])
        self.assertEqual({"key": "LEG_KEY", "child-of": {"kind": "deal", "field": "LEGS", "link": "TRADE_ID", "natural": "LEG_ID"}}, m["ingest"]["deal-leg"])
        self.assertEqual({"kind": "deal", "label": "Deal"}, m["graph"]["fields"]["TRADE_ID"])
        child = (self.dir / "deals" / "sutras" / "deal-leg" / "deal-leg-default.v1.sutra.yaml").read_text(encoding="utf-8")
        self.assertIn("link($.TRADE_ID, 'deal')", child)                           # the leg's screen links back to its deal
        sys.path.insert(0, str(HERE))
        import drishti
        self.assertEqual([], drishti.check_ingest_block(self.dir / "deals"))
        site = self.dir / "site"
        MJ.write_bi(opts([self.src], version="1.0.0"), PFJ.analyse(opts([self.src], child_min_avg=1.5), quiet), site, quiet)   # two child kinds
        self.assertEqual(4, len(list((site / "tables" / "deals").iterdir())))
        java = PFJ.SG.find_java(None)
        import javacmd
        r = subprocess.run(javacmd.java_cmd(java, ["-jar", JARS[-1], "tables", "check", "--root", str(site / "tables")], tmp=self.dir / "jvm", cwd=ROOT),
                           cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(0, r.returncode, r.stdout[-3000:] + r.stderr[-2000:])


if __name__ == "__main__":
    unittest.main()
