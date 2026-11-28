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

"""Your Home (routes/home_cards_routes.py, core/home_cards.py): everyone lands on their role Home after signing in, the layout follows the role
(Calm, or Cockpit for risk managers), the cards are ranked for the person's roles, what a person pins or hides is theirs alone, and the Needs-you
strip is not in the document when nothing needs them."""
import sys
from datetime import datetime, timezone
from pathlib import Path

from urllib.parse import unquote

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core import home_cards as hc  # noqa: E402
from core.app import create_app  # noqa: E402
from core.auth import COOKIE  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import Settings, load_settings  # noqa: E402
from tests.conftest import FakeBackend  # noqa: E402

FORM = {"Content-Type": "application/x-www-form-urlencoded"}
KPIS = [{"LABEL": "Firm VaR", "VALUE": 12_400_000.0, "DELTA": 800_000.0, "CAPTION": "since 2026-11-26", "OPEN": "VAR BANK"},
        {"LABEL": "Expected shortfall", "VALUE": 15_100_000.0, "DELTA": -100_000.0, "CAPTION": ""},
        {"LABEL": "Desk VaR", "VALUE": 2_100_000.0, "DELTA": None, "CAPTION": ""},
        {"LABEL": "Limit use", "VALUE": 0.82, "DELTA": 0.03, "CAPTION": ""},
        {"LABEL": "Breaches", "VALUE": 5, "DELTA": 0, "CAPTION": ""}]
TABLES = {"home_meta": [{"BUSINESS_DATE": "2026-11-27", "SINCE": "yesterday 17:00"}], "home_kpis": KPIS,
          "home_desk_limits": [{"DESK": "Rates", "VAR": 8_200_000.0, "LIMIT": 10_000_000.0, "CHANGE": 1.0}],
          "home_exposure": [{"CPTY": "ACME", "EXPOSURE": 9_000_000.0, "LIMIT": 9_500_000.0, "CHANGE": None}],
          "home_changes": [{"SUBJECT": "Rates", "MEASURE": "VAR", "KIND": "move", "THEN": 7_000_000.0, "NOW": 8_200_000.0, "CHANGE": 1_200_000.0}],
          "home_loads": [], "home_exceptions": [], "home_mnemonics": [{"MNEMONIC": "VAR", "ABOUT": "Value at risk"}]}
BANKING = [{"module": "banking", "role_cards": [
    {"id": "var", "title": "Value at risk", "kind": "metric", "table": "home_kpis", "pick": {"LABEL": "Firm VaR"}, "open": "VAR BANK",
     "explain": {"kind": "var", "measure": "var", "scope": "BANK"}, "rank": {"risk-manager": 100, "trader": 70}},
    {"id": "es", "title": "Expected shortfall", "kind": "metric", "table": "home_kpis", "pick": {"LABEL": "Expected shortfall"}, "rank": {"risk-manager": 90}},
    {"id": "desk-var", "title": "Desk VaR", "kind": "metric", "table": "home_kpis", "pick": {"LABEL": "Desk VaR"}, "rank": {"trader": 100}},
    {"id": "backtest", "title": "Backtest", "kind": "metric", "table": "home_kpis", "pick": {"LABEL": "Breaches"}, "fmt": "amount0", "light": [4, 9],
     "rank": {"risk-manager": 75}},
    {"id": "limits", "title": "Limit utilisation", "kind": "bars", "table": "home_desk_limits", "name": "DESK", "value": "VAR", "limit": "LIMIT",
     "rank": {"risk-manager": 80}},
    {"id": "exposure", "title": "Exposure", "kind": "bars", "table": "home_exposure", "name": "CPTY", "value": "EXPOSURE", "limit": "LIMIT",
     "rank": {"credit-officer": 100}}]}]


class HomeBackend(FakeBackend):
    """The stand-in server with Homes: who is offered which, the home tables, and each person's own settings."""

    def __init__(self):
        super().__init__()
        self.offered = {}                    # user -> [home names], the first the start Home
        self.mine = {}                       # user -> their settings as the server keeps them
        self.tables = {k: list(v) for k, v in TABLES.items()}
        self.since = []                      # the "since" each home_digest read was asked with

    async def _send(self, method, path, ident=None, **kw):
        user = getattr(ident, "user", "")
        if path == "/homes":
            homes = self.offered.get(user, [])
            return {"enabled": True, "picker": len(homes) > 1, "start": homes[0] if homes else None,
                    "homes": [{"home": h, "title": h.replace("-", " ").title(), "description": "", "arrange": True, "start": i == 0} for i, h in enumerate(homes)]}
        if path.startswith("/homes/") and "/tables/" in path:
            home, table = path[len("/homes/"):].split("/tables/")
            table, _q, since = table.partition("?since=")
            if table == "home_digest":
                self.since.append(unquote(since) or None)
            if home not in self.offered.get(user, []):
                raise BackendError(404, "DRS-1001", "no Home " + home + " is offered to you")
            return {"table": table, "rows": self.tables.get(table, [])}
        return await super()._send(method, path, ident, **kw)

    async def settings(self, ident=None):
        return dict(self.mine.get(ident.user, {}))

    async def patch_settings(self, changes, ident):
        mine = self.mine.setdefault(ident.user, {})
        for k, v in changes.items():
            mine.pop(k, None) if v is None else mine.__setitem__(k, v)
        return dict(mine)


def _users():
    base = {"enabled": True, "mustChangePassword": False, "locked": False, "email": "", "lastLoginAt": None}
    return {"qa-risk": {**base, "username": "qa-risk", "displayName": "Rina", "desk": "Risk", "roles": ["risk-manager"]},
            "qa-trader": {**base, "username": "qa-trader", "displayName": "Tom", "desk": "Rates", "roles": ["trader"]},
            "qa-both": {**base, "username": "qa-both", "displayName": "Bo", "desk": "Rates", "roles": ["trader", "credit-officer"]},
            "qa-none": {**base, "username": "qa-none", "displayName": "Nia", "desk": "Ops", "roles": ["viewer"]}}


def _app():
    data = load_settings(CONSOLE / "config").as_dict()
    data["auth"] = {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40, "token_ttl_seconds": 60, "session_hours": 1,
                    "secure_cookie": False, "recheck_seconds": 0}
    app = create_app(Settings(data))
    fake = HomeBackend()
    fake.users = _users()
    fake.passwords = {u: u + "-pass-1" for u in fake.users}
    fake.offered = {"qa-risk": ["risk-manager"], "qa-trader": ["trader"], "qa-both": ["trader", "credit-officer"], "qa-none": []}
    app.state.backend = fake
    app.state.module_parts.role_cards = lambda: BANKING          # the modules on: banking
    return app, fake


def _in(app, user, next_="/t"):
    c = TestClient(app)
    r = c.post("/login", content=f"user={user}&password={user}-pass-1&next={next_}", headers=FORM, follow_redirects=False)
    assert r.status_code == 303 and c.cookies.get(COOKIE)
    return c, r


def _post(c, body):
    return c.post("/api/home/cards/prefs", json=body)


def _ids(view):
    return [p["id"] for p in view["panels"]]


# ---- sign-in lands on the Home -------------------------------------------------------------------------------------------------------

def test_every_sign_in_lands_on_your_home_unless_a_page_was_asked_for_or_chosen():
    app, fake = _app()
    for user in ("qa-risk", "qa-trader", "qa-none"):                  # a role with a Home, another, and a person with none: all land on /home
        assert _in(app, user)[1].headers["location"] == "/home"
    assert _in(app, "qa-risk", "/var")[1].headers["location"] == "/var"          # a page asked for stays
    fake.mine["qa-trader"] = {"landing": "/w/Desk"}                   # a landing the person chose themselves stays
    assert _in(app, "qa-trader")[1].headers["location"] == "/w/Desk"
    fake.mine["qa-trader"] = {"landing": "/t"}                         # the default setting is not a choice
    assert _in(app, "qa-trader")[1].headers["location"] == "/home"


# ---- the layout follows the role ---------------------------------------------------------------------------------------------------

def test_a_risk_manager_gets_the_cockpit_and_other_roles_the_calm_layout():
    app, _ = _app()
    risk, _r = _in(app, "qa-risk")
    trader, _t = _in(app, "qa-trader")
    page = risk.get("/home")
    assert page.status_code == 200 and 'data-layout="cockpit"' in page.text and "drishti_home-cards.js" in page.text
    assert 'data-layout="calm"' in trader.get("/home").text
    assert risk.get("/api/home/cards").json()["view"]["layout"]["columns"] == [1] * 12
    assert trader.get("/api/home/cards").json()["view"]["layout"]["columns"] == [1] * 6


def test_cards_are_ranked_for_the_role_and_a_person_with_several_roles_sees_them_ranked_together():
    app, _ = _app()
    risk = _in(app, "qa-risk")[0].get("/api/home/cards").json()
    assert risk["cards"][:2] == ["var", "es"] and "desk-var" not in risk["cards"]
    trader = _in(app, "qa-trader")[0].get("/api/home/cards").json()
    assert trader["cards"][0] == "desk-var" and trader["cards"].index("var") < trader["cards"].index("loads") and "es" not in trader["cards"]
    both = _in(app, "qa-both")[0].get("/api/home/cards").json()
    assert set(both["cards"][:2]) == {"exposure", "desk-var"}           # the best weight over both roles puts the two roles' first cards on top
    assert "exposure" not in trader["cards"]


def test_a_person_with_no_role_home_still_lands_on_the_common_cards():
    app, _ = _app()
    got = _in(app, "qa-none")[0].get("/api/home/cards").json()
    assert got["homes"] == [] and "var" not in got["cards"]


def test_a_card_is_a_panel_the_painter_draws_with_the_figure_in_words_and_its_own_buttons():
    app, _ = _app()
    view = _in(app, "qa-risk")[0].get("/api/home/cards").json()["view"]
    var = next(p for p in view["panels"] if p["id"] == "var")
    assert var["kind"] == "metric" and next(p for p in view["panels"] if p["id"] == "limits")["kind"] == "hbar" and var["data"]["value"]["text"] == "12.4M" and var["data"]["delta"]["text"] == "+800K"
    assert [a["id"] for a in var["actions"]][:2] == ["var-explain", "var-open"]
    assert var["actions"][0]["effects"][0]["with"]["request"] == {"kind": "var", "measure": "var", "scope": "BANK"}
    back = next(p for p in view["panels"] if p["id"] == "backtest")
    assert back["data"]["value"]["tone"] == "warn"                                # five breaches: the amber light
    bars = next(p for p in view["panels"] if p["id"] == "limits")["data"]["bars"][0]
    assert bars["label"] == "Rates" and bars["tone"] == "warn" and "82%" in bars["text"]
    assert view["strip"][1] == {"label": "Data", "text": "every daily load is in", "tone": "ok"}
    assert view["contract"] == "1.2" and view["layout"]["phone"][0] == var["id"] or "needs" in view["layout"]["phone"]


# ---- Needs you ---------------------------------------------------------------------------------------------------------------------------

def test_needs_you_is_not_in_the_home_when_nothing_needs_you_and_snoozes_when_something_does():
    app, fake = _app()
    c, _ = _in(app, "qa-risk")
    assert "needs" not in _ids(c.get("/api/home/cards").json()["view"])
    fake.tables["home_loads"] = [{"PACK": "market-data", "KIND": "curve", "BUSINESS_DATE": "2026-11-27", "STATE": "late", "DEADLINE": ""}]
    view = c.get("/api/home/cards").json()["view"]
    needs = next(p for p in view["panels"] if p["id"] == "needs")
    assert needs["data"]["rows"][0]["cells"][0]["text"] == "Load late: market-data/curve" and view["strip"][1]["tone"] == "warn"
    assert view["layout"]["phone"][0] == "needs"                                  # on a phone it comes first
    done = _post(c, {"action": "snooze", "keys": needs["actions"][0]["effects"][0]["with"]["keys"]}).json()["view"]
    assert "needs" not in _ids(done)                                              # asleep for four hours
    fake.tables["home_loads"] = []


def test_an_alert_and_an_approval_waiting_are_what_needs_you():
    app, fake = _app()
    c, _ = _in(app, "qa-risk")
    fake.raised_kept = {"a1": {"id": "a1", "owner": "qa-risk", "source": "alert", "rule": "r", "title": "VaR above 12M", "severity": "critical", "stage": "raised",
                              "message": "now 12.4M", "link": "/alerts/measure/r"}}
    fake.approvals_kept = {("change", "chg-9"): {"kind": "change", "id": "chg-9", "group": "data", "title": "trade MX-1: 1 field(s)", "maker": "ana",
                                                 "at": "2026-11-27T08:00:00Z", "status": "pending", "reason": "", "due": None, "approvers": ["*"], "link": "/v/trade/MX-1"}}
    rows = next(p for p in c.get("/api/home/cards").json()["view"]["panels"] if p["id"] == "needs")["data"]["rows"]
    assert [r["cells"][0]["text"] for r in rows] == ["Alert: VaR above 12M", "Approve: trade MX-1: 1 field(s)"]


# ---- per person --------------------------------------------------------------------------------------------------------------------------

def test_pin_hide_and_move_are_the_persons_own_and_reach_no_one_else():
    app, fake = _app()
    risk, _r = _in(app, "qa-risk")
    other, _o = _in(app, "qa-risk")                                                  # the same person on another browser follows them
    trader, _t = _in(app, "qa-trader")
    before = trader.get("/api/home/cards").json()["cards"]
    assert _post(risk, {"action": "pin", "card": "limits"}).json()["cards"][0] == "limits"
    ids = _ids(risk.get("/api/home/cards").json()["view"])
    assert ids.index("limits") < ids.index("var")                                     # on the cockpit too, a pin is above the figures
    assert fake.mine["qa-risk"]["homeCards"]["pins"] == ["limits"] and "qa-trader" not in fake.mine
    assert other.get("/api/home/cards").json()["cards"][0] == "limits"
    assert trader.get("/api/home/cards").json()["cards"] == before
    hid = _post(risk, {"action": "hide", "card": "es"}).json()
    assert "es" not in hid["cards"] and "Show Expected shortfall" in [a["label"] for a in next(p for p in hid["view"]["panels"] if p["id"] == "arrange")["actions"]]
    assert _post(risk, {"action": "show", "card": "es"}).json()["cards"].count("es") == 1
    ordered = _post(risk, {"action": "down", "card": "var"}).json()["cards"]
    assert ordered.index("var") > ordered.index("es") and ordered[0] == "limits"      # the pin stays on top, var moved below es
    assert "homeCards" not in fake.mine.get("qa-trader", {})
    assert _post(risk, {"action": "reset"}).json()["cards"][0] == "var"
    assert _post(risk, {"action": "explode"}).status_code == 400 and _post(risk, {"action": "pin"}).status_code == 400


def test_a_visit_is_recorded_once_per_half_hour_and_shown():
    app, fake = _app()
    c, _ = _in(app, "qa-risk")
    _post(c, {"action": "visited"})
    first = fake.mine["qa-risk"]["homeCards"]["lastVisit"]
    assert _post(c, {"action": "visited"}).json() == {"kept": True} and fake.mine["qa-risk"]["homeCards"]["lastVisit"] == first
    assert [x["label"] for x in c.get("/api/home/cards").json()["view"]["strip"]] == ["Business date", "Data"]        # a first visit has no earlier one to show
    fake.mine["qa-risk"]["homeCards"] = {"lastVisit": "2026-11-20T09:00:00Z"}                                      # a visit long ago, then this one is recorded
    _post(c, {"action": "visited"})
    assert fake.mine["qa-risk"]["homeCards"]["prevVisit"] == "2026-11-20T09:00:00Z"
    assert c.get("/api/home/cards").json()["view"]["strip"][-1] == {"label": "Last visit", "text": "2026-11-20 09:00:00 UTC"}


# ---- the pieces ---------------------------------------------------------------------------------------------------------------------------

def test_preferences_are_functions_of_their_arguments_and_the_picture_fills_its_rows():
    now = datetime(2026, 11, 27, 20, 0, tzinfo=timezone.utc)
    p = hc.apply({}, "pin", "a")
    p = hc.apply(hc.apply(p, "pin", "b"), "unpin", "a")
    assert p["pins"] == ["b"] and hc.apply(p, "hide", "b")["pins"] == [] and hc.apply({}, "snooze", keys=["k"], now=now)["snooze"] == {"k": "2026-11-28T00:00:00Z"}
    assert not hc.awake({"snooze": {"k": "2026-11-28T00:00:00Z"}}, "k", now) and hc.awake({"snooze": {"k": "2026-11-27T00:00:00Z"}}, "k", now)
    assert hc.picture([("a", 3), ("b", 3), ("c", 2)], 6) == ["a a a b b b", "c c c c c c"]
    assert hc.picture([("a", 4), ("b", 4)], 12) == ["a a a a b b b b b b b b"]          # the last block of a row takes what is left
    assert hc.greeting("Rina", "UTC", now) == "Good evening, Rina" and hc.fmt(0.82, "pct0") == "82%" and hc.fmt(None) == "-"


def test_the_shipped_banking_cards_and_common_cards_read_and_name_only_what_exists():
    import yaml
    common, declared = hc.common_cards()
    assert {c.id for c in common} == {"loads", "pinned", "recent"} and declared["risk-manager"] == "cockpit"
    plugin = yaml.safe_load((CONSOLE.parent / "modules" / "banking" / "console" / "plugin.yaml").read_text(encoding="utf-8"))
    cards = hc.catalogue([{"module": "banking", "role_cards": plugin["home"]["role-cards"]}], common)
    assert {"var", "es", "limit-use", "limits", "backtest", "exposure", "movers"} <= {c.id for c in cards}
    homes = {p.stem.split(".")[0] for p in (CONSOLE.parent / "config" / "homes" / "banking").glob("*.home.yaml")}
    for c in cards:
        assert c.table.startswith("@") or c.table.startswith("home_"), c.id
        assert set(c.rank) - {"*"} <= homes, (c.id, set(c.rank) - homes)


# ---- View as ---------------------------------------------------------------------------------------------------------------------------

def test_view_as_offers_all_and_one_entry_per_home_the_person_has_and_limits_the_cards_to_it():
    app, fake = _app()
    both, _ = _in(app, "qa-both")
    sw = both.get("/api/home/cards").json()["viewAs"]
    assert sw["value"] == "" and [o["value"] for o in sw["options"]] == ["", "trader", "credit-officer"] and sw["options"][0]["label"] == "All"
    assert _in(app, "qa-risk")[0].get("/api/home/cards").json()["viewAs"]["options"] == []                # one role: no switcher
    got = _post(both, {"action": "viewas", "card": "credit-officer"}).json()
    assert got["viewAs"]["value"] == "credit-officer" and got["cards"][0] == "exposure" and "desk-var" not in got["cards"]
    assert fake.mine["qa-both"]["homeCards"]["viewAs"] == "credit-officer"                                  # remembered on the server, per person
    assert both.get("/api/home/cards").json()["cards"][0] == "exposure"                                       # a new request, a new page: the same choice
    assert "exposure" not in _post(both, {"action": "viewas", "card": "trader"}).json()["cards"]
    assert "desk-var" in _post(both, {"action": "viewas", "card": ""}).json()["cards"]                        # All again


def test_view_as_is_checked_on_the_server_and_one_persons_choice_reaches_no_one_else():
    app, fake = _app()
    risk, _r = _in(app, "qa-risk")
    both, _b = _in(app, "qa-both")
    assert _post(risk, {"action": "viewas", "card": "credit-officer"}).status_code == 400                    # not a Home this person has
    assert "viewAs" not in fake.mine.get("qa-risk", {}).get("homeCards", {})
    _post(both, {"action": "viewas", "card": "trader"})
    assert "viewAs" not in fake.mine.get("qa-risk", {}).get("homeCards", {})
    assert _post(risk, {"action": "reset"}).json()["viewAs"]["value"] == ""
    fake.mine["qa-both"]["homeCards"]["viewAs"] = "risk-manager"                                              # a Home the person lost (or was never given): ignored
    assert both.get("/api/home/cards").json()["viewAs"]["value"] == "" and hc.view_as({"viewAs": "x"}, ["a"]) == ""


def test_the_home_page_carries_the_switcher_and_the_logo_leads_to_home():
    app, _ = _app()
    page = _in(app, "qa-both")[0].get("/home").text
    assert "data-home-viewas" in page and 'class="tbar-brand" href="/home"' in page


# ---- the genomics Home ----------------------------------------------------------------------------------------------------------------

GENOMICS = [{"module": "genomics", "role_cards": [
    {"id": "g-cohort", "title": "Cohort", "kind": "metric", "table": "home_kpis", "pick": {"LABEL": "Samples"}, "rank": {"genomicist": 100}},
    {"id": "g-gwas", "title": "Top associations", "kind": "table", "table": "genomics_gwas_top", "columns": [{"label": "Gene", "col": "GENE"}],
     "rank": {"genomicist": 90}}]}]
G_TABLES = {"home_kpis": [{"LABEL": "Samples", "VALUE": 200.0, "DELTA": 4.0, "CAPTION": ""}], "genomics_gwas_top": [{"GENE": "HLXK7"}],
            "genomics_needs": [{"WHAT": "VCF failed validation: run7.vcf", "DETAIL": "line 12: bad genotype"}]}
G_NEEDS = [{"module": "genomics", "needs": [{"table": "genomics_needs", "what": "WHAT", "detail": "DETAIL", "tone": "bad", "open": "LOADS", "homes": ["genomicist"]}]}]


def _genomics_app():
    app, fake = _app()
    fake.users["qa-gen"] = {**fake.users["qa-none"], "username": "qa-gen", "displayName": "Gia", "roles": ["genomicist"]}
    fake.passwords["qa-gen"] = "qa-gen-pass-1"
    fake.offered["qa-gen"] = ["genomicist"]
    fake.offered["qa-bank-gen"] = []
    fake.tables.update(G_TABLES)
    fake.tables["home_kpis"] = KPIS + G_TABLES["home_kpis"]
    app.state.module_parts.role_cards = lambda: BANKING + GENOMICS
    app.state.module_parts.home_needs = lambda: G_NEEDS
    return app, fake


def test_genomics_cards_and_its_needs_you_item_appear_only_for_the_genomics_role():
    app, fake = _genomics_app()
    gen = _in(app, "qa-gen")[0].get("/api/home/cards").json()
    assert gen["cards"][:2] == ["g-cohort", "g-gwas"] and "var" not in gen["cards"]
    needs = next(p for p in gen["view"]["panels"] if p["id"] == "needs")
    assert needs["data"]["rows"][0]["cells"][0]["text"] == "VCF failed validation: run7.vcf" and needs["data"]["rows"][0]["highlight"]
    for user in ("qa-risk", "qa-trader", "qa-both", "qa-none"):
        got = _in(app, user)[0].get("/api/home/cards").json()
        assert not {"g-cohort", "g-gwas"} & set(got["cards"]) and "needs" not in [p["id"] for p in got["view"]["panels"]], user


# ---- since your last visit -------------------------------------------------------------------------------------------------------------

def test_the_digest_reads_since_the_last_visit_and_falls_back_to_the_cut_off_on_a_first_visit():
    app, fake = _app()
    fake.tables["home_digest"] = [{"AT": "2026-11-27T08:15:00Z", "KIND": "load", "TEXT": "genomics/variant 2026-11-27 loaded, 40 rows"}]
    fake.raised_kept = {"a1": {"id": "a1", "owner": "qa-risk", "source": "alert", "rule": "r", "title": "VaR above 12M", "severity": "high", "stage": "raised",
                               "raisedAt": "2026-11-27T09:00:00Z", "message": "", "link": ""},
                        "a0": {"id": "a0", "owner": "qa-risk", "source": "alert", "rule": "r0", "title": "Old alert", "severity": "high", "stage": "raised",
                               "raisedAt": "2026-11-19T09:00:00Z", "message": "", "link": ""}}
    c, _ = _in(app, "qa-risk")
    first = c.get("/api/home/cards").json()["view"]
    d = next(p for p in first["panels"] if p["id"] == "digest")
    assert fake.since[-1] is None and d["title"].startswith("Since yesterday")                               # a first visit: the Home's own cut-off
    assert any("Rates VAR" in r["cells"][1]["text"] for r in d["data"]["rows"])                              # (the cut-off's biggest movers)
    fake.mine["qa-risk"] = {"homeCards": {"lastVisit": "2026-11-26T18:00:00Z"}}                              # a visit long ago
    later = c.get("/api/home/cards").json()["view"]
    d = next(p for p in later["panels"] if p["id"] == "digest")
    assert fake.since[-1] == "2026-11-26T18:00:00Z" and d["title"] == "Since your last visit, 2026-11-26 18:00:00 UTC"
    texts = [r["cells"][1]["text"] for r in d["data"]["rows"]]
    assert texts == ["Alert fired: VaR above 12M", "genomics/variant 2026-11-27 loaded, 40 rows"]            # newest first; the old alert is before the window
    _post(c, {"action": "visited"})                                                                           # this visit is recorded: the window stays where it was
    assert fake.mine["qa-risk"]["homeCards"]["prevVisit"] == "2026-11-26T18:00:00Z"
    c.get("/api/home/cards")
    assert fake.since[-1] == "2026-11-26T18:00:00Z"


def test_the_visit_window_is_a_function_of_the_stored_visits():
    now = datetime(2026, 11, 27, 20, 0, tzinfo=timezone.utc)
    assert hc.digest_window({}, now) is None
    assert hc.digest_window({"lastVisit": "2026-11-27T19:50:00Z"}, now) is None                                # just recorded, no earlier visit
    assert hc.digest_window({"lastVisit": "2026-11-27T19:50:00Z", "prevVisit": "2026-11-26T10:00:00Z"}, now) == "2026-11-26T10:00:00Z"
    assert hc.digest_window({"lastVisit": "2026-11-26T10:00:00Z", "prevVisit": "2026-11-25T10:00:00Z"}, now) == "2026-11-26T10:00:00Z"
    assert hc.apply({"lastVisit": "a"}, "visited", now=now)["prevVisit"] == "a" and "viewAs" not in hc.apply({"viewAs": "x"}, "viewas", "")


def test_the_shipped_genomics_cards_name_a_home_a_table_and_a_need_that_exist():
    import yaml
    plugin = yaml.safe_load((CONSOLE.parent / "modules" / "genomics" / "console" / "plugin.yaml").read_text(encoding="utf-8"))
    cards = hc.catalogue([{"module": "genomics", "role_cards": plugin["home"]["role-cards"]}], hc.common_cards()[0])
    assert {"genomics-cohort", "genomics-gwas", "genomics-clinvar", "genomics-loads"} <= {c.id for c in cards}
    assert (CONSOLE.parent / "config" / "homes" / "genomics" / "genomicist.home.yaml").is_file()
    java = "".join(f.read_text(encoding="utf-8") for f in (CONSOLE.parent / "modules" / "genomics" / "java").rglob("*.java"))
    for c in cards:
        if c.module == "genomics":
            assert set(c.rank) <= {"genomicist"}, c.id
            assert c.table in ("home_kpis", "home_loads") or f'"{c.table}"' in java, (c.id, c.table)
    for n in plugin["home"]["needs"]:
        assert f'"{n["table"]}"' in java and n["homes"] == ["genomicist"]


def test_a_scatter_card_is_the_small_plot_of_a_tables_points():
    card = hc.Card(id="map", title="Association map", module="genomics", kind="scatter", table="t", x="X", value="Y", name="GENE", group="CHROM", limit_rows=3,
                   rank={"genomicist": 1})
    rows = [{"X": 2307.0, "Y": 28.0, "GENE": "HLXK7", "CHROM": "7"}, {"X": 5000.0, "Y": 3.1, "GENE": "TP53", "CHROM": "17"}, {"X": None, "Y": 1, "GENE": "BAD", "CHROM": "1"}]
    p = hc.panel(card, rows, pinned=False, first=True, last=True)
    assert p["kind"] == "scatter" and p["data"]["groups"] == ["17", "7"]
    assert p["data"]["points"][0] == {"x": 2307.0, "y": 28.0, "label": "HLXK7", "group": "7"} and len(p["data"]["points"]) == 2      # a row with no x is not a point
