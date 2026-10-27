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

"""Did you know: Home's fact of the day (core/facts.py, core/facts_expr.py, routes/home_routes.py). The shipped facts files are checked,
the fact language refuses what it does not have, conditions decide whether a fact is shown, the filled fact and its links are right, and
Home hides the tile when no fact holds or a fact's pack is off."""
import asyncio
from pathlib import Path

import pytest
import yaml

from core.facts import FactsService, Fetcher, check, compute, links, load_pack, parse, rotate
from core.facts_expr import Expr, ExprError, Template, fmt

PACKS = Path(__file__).resolve().parents[2] / "config" / "packs"


# ---------------------------------------------------------------------------------------------------------------------- the config

def test_every_shipped_facts_file_is_clean_and_there_are_at_least_six_facts_across_three_domains():
    problems = check(PACKS)
    assert problems and all(v == [] for v in problems.values()), problems
    facts = [f for name in problems for f in load_pack({"name": name, "dir": PACKS / name})[0]]
    assert len(facts) >= 6
    assert {f.pack for f in facts} >= {"market-risk", "counterparty-risk", "funds"}
    assert len({f.id for f in facts}) == len(facts)
    for f in facts:
        assert f.what and f.knowledge and f.picture, f.id                 # every shipped fact has its WHAT, its Why page and a picture


def test_no_number_is_written_into_a_shipped_fact_sentence():
    """The sentences are templates: every figure comes from {expressions}, never from the file."""
    import re
    for name in check(PACKS):
        doc = yaml.safe_load((PACKS / name / "config" / "facts.yaml").read_text())
        for item in doc["items"]:
            for key in ("text", "why"):
                literal = re.sub(r"\{[^{}]*\}", "", item.get(key) or "")
                for name in ("1 day 99%", "10-K", "100%"):           # not data: the VaR's definition, a form, the whole
                    literal = literal.replace(name, "")
                assert not re.search(r"\d", literal), (item["id"], key, literal)


@pytest.mark.parametrize("item, says", [
    ({"id": "x", "domain": "d", "text": "t", "open": "o", "steps": [{"as": "a", "let": "1"}], "colour": "red"}, "unknown key"),
    ({"id": "x", "domain": "d", "text": "t", "open": "o", "steps": [{"as": "a", "let": "__import__('os')"}]}, "only these functions"),
    ({"id": "x", "domain": "d", "text": "{b.c}", "open": "o", "steps": [{"as": "a", "let": "1"}]}, "which no earlier step sets"),
    ({"id": "x", "domain": "d", "text": "{a|nope}", "open": "o", "steps": [{"as": "a", "let": "1"}]}, "unknown format"),
    ({"id": "x", "domain": "d", "text": "t", "open": "o", "steps": [{"as": "a", "var": "tree"}]}, "API path"),
    ({"id": "x", "domain": "d", "text": "t", "open": "o", "steps": [{"as": "a", "let": "1", "var": "/tree"}]}, "one of"),
    ({"id": "x", "domain": "d", "text": "t", "steps": [{"as": "a", "let": "1"}]}, "'open' is needed"),
    ({"id": "x", "domain": "d", "text": "t", "open": "o", "steps": [{"as": "a", "let": "1"}],
      "picture": {"type": "pie"}}, "picture.type"),
])
def test_a_bad_fact_is_left_out_with_its_reason_and_the_others_stand(item, says):
    good = {"id": "ok", "domain": "d", "text": "fine", "open": "X 1", "steps": [{"as": "a", "let": "1"}]}
    facts, problems = parse({"facts": 1, "items": [item, good]}, "demo")
    assert [f.id for f in facts] == ["ok"]
    assert len(problems) == 1 and says in problems[0], problems


def test_a_file_without_the_version_is_refused():
    assert parse({"items": []}, "demo") == ([], ["demo/config/facts.yaml: needs 'facts: 1' and a list under 'items'"])


# ---------------------------------------------------------------------------------------------------------------------- the language

def test_the_language_reads_data_and_nothing_else():
    env = {"tree": {"nodes": [{"id": "A", "limitUse": 0.5}, {"id": "B", "parent": "A", "limitUse": 2.23}]}}
    assert Expr("[n.id for n in tree.nodes if n.limitUse > 1 and find(tree.nodes, 'id', n.parent).limitUse <= 1]").eval(env) == ["B"]
    assert Expr("missing.deeper.still + 1").eval(env) is None                      # None is forgiving
    assert Expr("missing > 1").eval(env) is False and Expr("1 / 0").eval(env) is None
    assert Expr("overlap(a, b, 'K', 'W')").eval({"a": [{"K": "x", "W": 3}, {"K": "y", "W": 2}, {"K": "x", "W": 1}],
                                                 "b": [{"K": "x", "W": 2}, {"K": "z", "W": 9}]}) == 2
    assert Expr("flat(pluck(g, 'children'))").eval({"g": [{"children": [1, 2]}, {"children": [3]}]}) == [1, 2, 3]
    for bad in ("().__class__", "open('x')", "lambda: 1", "{'a': 1}", "x ** 99", "[a for a in b for c in a]", "f(x=1)"):
        with pytest.raises(ExprError):
            Expr(bad)


def test_templates_fill_formats_and_a_hole_hides_the_sentence():
    t = Template("{a|pct} of {b|money}, {c|signpct}, {d|pctv2}, {e|times} {{braces}}")
    assert t.render({"a": 2.23, "b": 19_520_460, "c": 4.52, "d": 21.187, "e": 13.58}) == "223% of 19.5m, +452%, 21.19%, 13.6× {braces}"
    assert t.render({"a": 2.23}) is None
    assert fmt(1_043_705_085_656, "money") == "1,043.7bn" and fmt(-543_000_000, "money") == "-543.0m"


# ---------------------------------------------------------------------------------------------------------------------- computing

class FakeFetcher:
    """Answers the steps from a dict: (kind, target, params) -> answer; anything else raises as a refusal would."""

    def __init__(self, answers):
        self.answers, self.calls = answers, []

    async def fetch(self, kind, target, params):
        key = (kind, target if not isinstance(target, tuple) else "/".join(target), tuple(sorted(params.items())))
        self.calls.append(key)
        if key not in self.answers:
            raise LookupError(key)
        return self.answers[key]


def _tree(book_use, desk_use):
    return {"hierarchies": ["MGMT"], "nodes": [
        {"id": "MGMT~DESK-COMM", "name": "Commodities", "level": "desk", "parent": None, "var": 19_000_000, "limit": 25_000_000,
         "limitUse": desk_use},
        {"id": "MGMT~BOOK-COMM-1", "name": "BOOK-COMM-1", "level": "book", "parent": "MGMT~DESK-COMM", "var": 6_465_756,
         "limit": 2_500_000, "limitUse": book_use}]}


def _book_fact():
    facts, _ = load_pack({"name": "market-risk", "dir": PACKS / "market-risk"})
    return next(f for f in facts if f.id == "book-over-limit-desk-within")


def _answers(tree):
    return {("var", "/tree", ()): tree, ("var", "/tree", (("hierarchy", "MGMT"),)): tree}


def test_a_book_over_its_limit_under_a_desk_within_its_own_is_shown_filled_from_the_answer():
    out = asyncio.run(compute(_book_fact(), FakeFetcher(_answers(_tree(2.586, 0.76))), "2026-10-27"))
    assert out["text"] == "BOOK-COMM-1 is at **259%** of its VaR limit, while its desk, Commodities, is within its own."
    assert "6.5m against a limit of 2.5m" in out["why"]
    assert out["command"] == "VAR NODE MGMT~BOOK-COMM-1"
    assert [b["display"] for b in out["picture"]["bars"]] == ["259%", "76%"]
    assert out["picture"]["bars"][0]["width"] == 100.0 and out["picture"]["caption"].endswith("2026-10-27")


@pytest.mark.parametrize("book, desk", [(0.9, 0.5), (2.5, 1.2)])
def test_the_fact_is_not_shown_when_its_condition_does_not_hold(book, desk):
    """No book over its limit; or the desk is over its own too (then it is no surprise)."""
    assert asyncio.run(compute(_book_fact(), FakeFetcher(_answers(_tree(book, desk))), "2026-10-27")) is None


def test_a_refused_answer_means_the_fact_does_not_hold():
    assert asyncio.run(compute(_book_fact(), FakeFetcher({}), "2026-10-27")) is None


def test_links_rotation_and_the_fetcher_date():
    fact = {"id": "a", "pack": "p", "command": "EXP NS-X", "what": "Collateral effect", "knowledge": "x.md#4-y"}

    class Lib:
        by_file = {Path("/docs/knowledge/x.md"): "knowledge-x"}
    out = links(fact, None, Lib())
    assert out["href"] == "/go?q=EXP%20NS-X%20%3CGO%3E" and out["whatHref"] == "/what?q=Collateral%20effect"
    assert out["knowledgeHref"] == "/help/knowledge-x#4-y"
    items = [{"pack": "p", "id": i} for i in "cab"]
    assert [x["id"] for x in rotate(items, "2026-10-27")] == [x["id"] for x in rotate(list(reversed(items)), "2026-10-27")]
    assert {rotate(items, d)[0]["id"] for d in ("2026-10-27", "2026-10-28", "2026-10-29")} == {"a", "b", "c"}

    class Be:
        async def var(self, method, path, ident, **params):
            return params
    assert asyncio.run(Fetcher(Be(), None, "2026-10-27").fetch("var", "/tree", {"hierarchy": "LEGAL"})) == {"hierarchy": "LEGAL",
                                                                                                         "asOf": "2026-10-27"}


def test_a_fact_whose_pack_is_off_is_not_eligible():
    svc = FactsService()
    ccr = {"name": "counterparty-risk", "dir": PACKS / "counterparty-risk"}
    mr = {"name": "market-risk", "dir": PACKS / "market-risk"}
    alone = {f.id for f in svc.eligible([ccr])}
    assert "collateral-raises-exposure" in alone and "var-versus-peak-pfe" not in alone     # it needs market-risk too
    assert "var-versus-peak-pfe" in {f.id for f in svc.eligible([ccr, mr])}
    assert svc.eligible([]) == []


def test_one_computation_per_key_and_it_is_kept():
    svc = FactsService()
    fetcher = FakeFetcher(_answers(_tree(2.586, 0.76)))

    async def twice():
        return await asyncio.gather(*(svc.today(("k",), [_book_fact()], fetcher, "2026-10-27") for _ in range(3)))
    results = asyncio.run(twice())
    assert all(r == results[0] and len(r) == 1 for r in results)
    assert len(fetcher.calls) == 2                                        # the first tree and one per hierarchy, once for all three
    asyncio.run(svc.today(("k",), [_book_fact()], fetcher, "2026-10-27"))
    assert len(fetcher.calls) == 2


# ---------------------------------------------------------------------------------------------------------------------- Home

def test_home_draws_the_skeleton_tile_and_the_quick_cards_of_the_packs_on(client, with_packs):
    with_packs("market-risk", "funds")
    html = client.get("/").text
    assert "data-dyk" in html and "lp-home has-dyk" in html and "/static/js/dyk.js" in html
    assert "See what your data" in html                                   # the headline stays, beside the tile
    assert 'data-home-card="VAR BANK"' in html and 'data-home-card="FUND SPY"' in html and 'data-home-card="DATAMAP"' in html
    assert 'data-home-card="EXP BANK"' not in html                        # counterparty-risk is off


def test_no_fact_holds_the_answer_is_empty_and_the_tile_hides(client, backend, with_packs, monkeypatch):
    with_packs("market-risk", "funds")
    client.app.state.facts._done.clear()

    async def nothing(*a, **k):
        return {"nodes": [], "rows": []}
    for name in ("var", "exposure", "search", "raw"):
        monkeypatch.setattr(backend, name, nothing, raising=False)
    r = client.get("/api/home/facts")
    assert r.status_code == 200 and r.json() == {"date": "2026-09-30", "facts": []}
    assert r.headers["cache-control"] == "no-store"
    js = (Path(__file__).resolve().parents[1] / "web" / "static" / "js" / "dyk.js").read_text()
    assert "if (!facts.length) { hide(); return; }" in js and ".catch(hide)" in js


def test_home_facts_answer_with_links_for_the_packs_on_only(client, backend, with_packs, monkeypatch):
    with_packs("market-risk")
    client.app.state.facts._done.clear()
    tree = _tree(2.586, 0.76)

    async def var(method, path, ident, **params):
        if path == "/tree":
            return tree
        raise LookupError(path)
    monkeypatch.setattr(backend, "var", var, raising=False)
    d = client.get("/api/home/facts").json()
    assert d["date"] == "2026-09-30"
    assert [f["id"] for f in d["facts"]] == ["book-over-limit-desk-within"]
    f = d["facts"][0]
    assert f["href"].startswith("/go?q=VAR%20NODE%20MGMT~BOOK-COMM-1") and f["whatHref"] == "/what?q=VaR%20limit"
    assert f["knowledgeHref"].endswith("#4-a-worked-example-on-the-demo-data")
    assert all(x["pack"] == "market-risk" for x in d["facts"])


def test_facts_switched_off_draw_no_tile(client, with_packs, monkeypatch):
    with_packs("market-risk")
    monkeypatch.setattr(client.app.state.facts, "enabled", False)
    assert "data-dyk" not in client.get("/").text
    assert client.get("/api/home/facts").json()["facts"] == []
