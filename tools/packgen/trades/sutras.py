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

"""The trading pack's Sutras for production-shaped trades (schema.py): one deal Sutra per PRODUCT_TYPE, and the pages of the derived
kinds that gather a counterparty's, a portfolio's, a legal entity's and an index's trades from every source system.

Every deal Sutra is the same terminal-grade screen, generic over the common schema (nothing in it is one product's own), so a product
type is a match and a few words:

* F2 DES: the deal ticket (deal, product, parties, dates and settlement, as a booking system's ticket groups them);
* F3 LEGS: the legs side by side, each with its terms, its notional profile (a step line), its fixings (a line) and its cash-flow
  schedule, each flow opening to its resets;
* F4 FLOWS: every flow of every leg in one schedule; F5 EVT: the events in date order, each naming its leg; F6 PAY: the netted payment
  ladder (cash events by date, paid below the line, received above) and payments by month;
* F7 the counterparty's trades page, F8 impact, F9 the raw document.

Blank values (the empty strings extracts are full of) show as a dash; numbers that arrive as text are formatted as numbers.
"""
from __future__ import annotations

import re

RATE_GROUPS = {"Rates", "Inflation", "Credit", "Money market", "Fixed income", "Securities financing"}
DERIVED = {  # derived kind: (mnemonic, label, group-by expression, id field, what it is)
    "counterparty-trades": ("CTRD", "Counterparty trades", "$.COUNTERPARTY", "COUNTERPARTY", "counterparty", "Every trade with a counterparty, from every source system"),
    "portfolio-trades": ("PTRD", "Portfolio trades", "$.PORTFOLIO", "PORTFOLIO", "book", "Every trade of a portfolio (book), from every source system"),
    "legal-entity-trades": ("LTRD", "Legal entity trades", "$.LEGAL_ENTITY", "LEGAL_ENTITY", "legal-entity", "Every trade a legal entity of the bank booked, from every source system"),
    "index-trades": ("ITRD", "Index trades", "nonblank($.LEGS[0].INDEX.NAME, $.LEGS[1].INDEX.NAME)", "INDEX_NAME", None, "Every trade with a leg on an index (the first leg that names one)"),
}
# a derived page's row per member trade (the derived connector's rows:), upper snake like the trades they come from
ROWS = {"TRADE_ID": "$.TRADE_ID", "SOURCE_SYSTEM": "$.SOURCE_SYSTEM", "PRODUCT_TYPE": "$.PRODUCT_TYPE", "BUY_SELL_CODE": "$.BUY_SELL_CODE",
        "CURRENCY": "$.CURRENCY", "NOTIONAL": "first($.LEGS).NOTIONAL", "MATURITY_DATE": "$.MATURITY_DATE", "TRADE_STATUS": "$.TRADE_STATUS",
        "INDEX_NAME": "nonblank($.LEGS[0].INDEX.NAME, $.LEGS[1].INDEX.NAME, '')", "COUNTERPARTY": "$.COUNTERPARTY", "PORTFOLIO": "$.PORTFOLIO",
        "EVENTS": "$.EVENTS"}
FIELDS = {"TRADE_COUNT": "count", "NOTIONAL": "sum first($.LEGS).NOTIONAL", "SOURCE_SYSTEMS": "distinct $.SOURCE_SYSTEM",
          "PRODUCT_TYPES": "distinct $.PRODUCT_TYPE", "CURRENCIES": "distinct $.CURRENCY", "LAST_MATURITY": "max $.MATURITY_DATE"}


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def q(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def dash(expr: str) -> str:
    """A field that may be blank, shown as a dash when it is."""
    return f"nonblank({expr}, '–')"


def side(expr: str = "$.BUY_SELL_CODE") -> str:
    return f"{expr} == 'B' ? 'Buy' : ({expr} == 'S' ? 'Sell' : nonblank({expr}, '–'))"


RF = "\"%{rate-fmt}\""                # the blocks' parameter: rates as a percentage (pct4), prices and amounts in their unit (price4)


def _panels(rate_fmt: str) -> list[str]:
    """Every panel of the deal screen, in order (the blocks are cut from this list)."""
    return [
        "  - id: ticket",
        "    kind: kv",
        "    title: Deal ticket",
        "    code: DES",
        "    key: F2",
        "    columns:",
        "      - { label: Trade id, bind: $.TRADE_ID }",
        f"      - {{ label: Transaction, bind: {q(dash('$.TRANSACTION_ID'))} }}",
        f"      - {{ label: Version, bind: {q(dash('$.TRADE_VERSION'))} }}",
        f"      - {{ label: Action, bind: {q(dash('$.TRADE_ACTION'))} }}",
        f"      - {{ label: Package, bind: {q(dash('$.PACKAGE_ID'))} }}",
        f"      - {{ label: Source system, bind: $.SOURCE_SYSTEM }}",
        f"      - {{ label: Updated, bind: {q(dash('$.SOURCE_UPDATE_TIME'))} }}",
        "      - { label: Product, bind: $.PRODUCT_TYPE }",
        f"      - {{ label: Sub type, bind: {q(dash('$.PRODUCT_SUB_TYPE'))} }}",
        f"      - {{ label: Group, bind: {q(dash('$.PRODUCT_GROUP') + ' + \" / \" + ' + dash('$.PRODUCT_SUB_GROUP'))} }}",
        f"      - {{ label: Option, bind: {q(dash('$.OPTION_TYPE'))} }}",
        f"      - {{ label: Strike, bind: $.STRIKE_PRICE, fmt: {rate_fmt} }}",
        f"      - {{ label: Price, bind: $.PRICE, fmt: {rate_fmt} }}",
        "      - { label: Quantity, bind: $.QUANTITY, fmt: amount2 }",
        "      - { label: Counterparty, bind: \"link($.COUNTERPARTY, 'counterparty')\" }",
        f"      - {{ label: UEN, bind: {q(dash('$.COUNTERPARTY_UEN'))} }}",
        f"      - {{ label: Country of risk, bind: {q(dash('$.COUNTERPARTY_COUNTRY_OF_RISK'))} }}",
        "      - { label: Original counterparty, bind: \"nonblank($.ORIGINAL_COUNTERPARTY) ? link($.ORIGINAL_COUNTERPARTY, 'counterparty') : '–'\" }",
        f"      - {{ label: Internal / external, bind: {q(dash('$.INTERNAL_EXTERNAL_CODE'))} }}",
        "      - { label: Legal entity, bind: \"nonblank($.LEGAL_ENTITY) ? link($.LEGAL_ENTITY, 'legal-entity') : '–'\" }",
        f"      - {{ label: Transit, bind: {q(dash('$.TRANSIT'))} }}",
        "      - { label: Portfolio, bind: \"link($.PORTFOLIO, 'book')\" }",
        "      - { label: Trader, bind: \"link($.TRADER_ID, 'trader', nonblank($.TRADER_FIRST_NAME + ' ' + $.TRADER_LAST_NAME, $.TRADER_ID))\" }",
        "      - { label: Trade date, bind: $.TRADE_DATE, fmt: date }",
        f"      - {{ label: Input date, bind: {q(dash('$.INPUT_DATE'))} }}",
        "      - { label: Start, bind: $.START_DATE, fmt: date }",
        "      - { label: Maturity, bind: $.MATURITY_DATE, fmt: date }",
        f"      - {{ label: Settlement, bind: {q(dash('$.SETTLEMENT_DATE'))} }}",
        "      - { label: Currency, bind: $.CURRENCY }",
        f"      - {{ label: DVP, bind: {q(dash('$.DELIVERY_VERSUS_PAYMENT'))} }}",
        f"      - {{ label: Book (TT), bind: {q(dash('$.TT_BOOK'))} }}",
        "  - id: legs",
        "    kind: tabs",
        "    title: Legs",
        "    code: LEGS",
        "    key: F3",
        "    each: $.LEGS",
        "    layout: columns",
        "    tabTitle: \"'Leg ' + @.LEG_ID + ' · ' + nonblank(@.LEG_ECONOMIC_TYPE + ' ' + @.LEG_TYPE, @.LEG_TYPE, '–') + (nonblank(@.INDEX.NAME) ? ' · ' + @.INDEX.NAME : '')\"",
        "    panels:",
        "      - id: leg-terms",
        "        kind: kv",
        "        title: Terms",
        "        columns:",
        "          - { label: Type, bind: \"nonblank(@.LEG_TYPE, '–')\" }",
        "          - { label: Pay / receive, bind: \"nonblank(@.LEG_ECONOMIC_TYPE, '–')\" }",
        "          - { label: Index, bind: \"nonblank(@.INDEX.NAME, '–')\" }",
        "          - { label: Index group, bind: \"nonblank(@.INDEX.GROUP, '–')\" }",
        "          - { label: Notional, bind: \"@.CURRENCY + ' ' + fmt(@.NOTIONAL, 'amount0')\" }",
        "          - { label: Price unit, bind: \"nonblank(@.PRICE_UNIT, '–')\" }",
        f"          - {{ label: Spread, bind: \"@.FLOAT_SPREAD\", fmt: {rate_fmt} }}",
        "          - { label: Reset, bind: \"nonblank(@.RESET_CONVENTION, '–')\" }",
        "          - { label: Fixing shift, bind: \"nonblank(@.RESET_FIXING_SHIFT, '–')\" }",
        "          - { label: Settlement, bind: \"nonblank(@.SETTLEMENT_TYPE, '–')\" }",
        "          - { label: Discount index, bind: \"nonblank(@.DISCOUNT_INDEX, '–')\" }",
        "          - { label: Daily volume, bind: \"@.DAILY_VOLUME\", fmt: amount0 }",
        "          - { label: Flows, bind: \"size(@.CASH_FLOWS)\" }",
        "      - id: leg-notional",
        "        kind: line",
        "        title: Notional profile",
        "        rows: \"@.CASH_FLOWS\"",
        "        x: START_DATE",
        "        y: NOTIONAL",
        "        step: true",
        "        fmt: amount0",
        "      - id: leg-fixings",
        "        kind: line",
        "        title: Fixings",
        "        rows: \"flatten(@.CASH_FLOWS[*].RESETS)[?nonblank(@.RATE)]\"",
        "        x: START_DATE",
        "        y: RATE",
        f"        fmt: {rate_fmt}",
        "      - id: leg-flows",
        "        kind: table",
        "        title: Cash flows and resets",
        "        rows: \"@.CASH_FLOWS\"",
        "        children: \"@.RESETS\"",
        "        expand: 1",
        "        columns:",
        "          - { label: Flow / reset, bind: \"nonblank(@.FLOW_ID, @.RESET_ID, '·')\" }",
        "          - { label: Start, bind: \"@.START_DATE\", fmt: date }",
        "          - { label: End, bind: \"@.END_DATE\", fmt: date }",
        "          - { label: Pay / reset date, bind: \"nonblank(@.PAYMENT_DATE, @.RESET_DATE, '–')\" }",
        "          - { label: Status, bind: \"nonblank(@.PAYMENT_DATE) ? (@.PAYMENT_DATE < today() ? 'Paid' : (@.START_DATE <= today() ? 'Pricing' : 'Future')) : (nonblank(@.RATE) ? 'Fixed' : 'Open')\", tone: status }",
        "          - { label: Type, bind: \"nonblank(@.FLOW_TYPE, '–')\" }",
        "          - { label: Notional, bind: \"@.NOTIONAL\", fmt: amount0, total: true }",
        f"          - {{ label: Rate / price, bind: \"@.RATE\", fmt: {rate_fmt} }}",
        f"          - {{ label: Spread, bind: \"@.FLOAT_SPREAD\", fmt: {rate_fmt} }}",
        f"          - {{ label: Strike, bind: \"@.STRIKE_PRICE\", fmt: {rate_fmt} }}",
        "  - id: flows",
        "    kind: table",
        "    title: Cash-flow schedule, every leg",
        "    code: FLOWS",
        "    key: F4",
        "    rows: \"flatten($.LEGS[*].CASH_FLOWS)\"",
        "    columns:",
        "      - { label: Flow, bind: \"@.FLOW_ID\" }",
        "      - { label: Start, bind: \"@.START_DATE\", fmt: date }",
        "      - { label: End, bind: \"@.END_DATE\", fmt: date }",
        "      - { label: Payment, bind: \"@.PAYMENT_DATE\", fmt: date }",
        "      - { label: Fixing, bind: \"nonblank(@.RATE_DETERMINATION_DATE, '–')\" }",
        "      - { label: Notional, bind: \"@.NOTIONAL\", fmt: amount0, total: true }",
        f"      - {{ label: Rate / price, bind: \"@.RATE\", fmt: {rate_fmt} }}",
        "      - { label: Resets, bind: \"size(@.RESETS)\" }",
        "  - id: events",
        "    kind: timeline",
        "    title: Events by leg",
        "    code: EVT",
        "    key: F5",
        "    rows: \"$.EVENTS[?nonblank(@.EVENT_DATE)]\"",
        "    date: EVENT_DATE",
        "    label: \"@.EVENT_TYPE + (nonblank(@.LEG_ID) ? ' · leg ' + @.LEG_ID : ' · deal')\"",
        "    detail: \"nonblank(@.PAYMENT_TYPE, '') + (nonblank(@.AMOUNT) ? ' ' + @.CURRENCY + ' ' + fmt(@.AMOUNT, 'signed2') : '')\"",
        "  - id: event-list",
        "    kind: table",
        "    title: \"Events${ctl.when ? ' · ' + ctl.when : ''}\"",
        "    code: EVTL",
        "    rows: $.EVENTS",
        "    where: \"(!nonblank(@.LEG_ID) || oneOf(@.LEG_ID, ctl.legs)) && within(@.EVENT_DATE, ctl.when)\"",
        "    empty: No events booked",
        "    columns:",
        "      - { label: Leg, bind: \"nonblank(@.LEG_ID, 'deal')\" }",
        "      - { label: Event, bind: \"nonblank(@.EVENT_ID, '–')\" }",
        "      - { label: Type, bind: \"nonblank(@.EVENT_TYPE, '–')\" }",
        "      - { label: Payment type, bind: \"nonblank(@.PAYMENT_TYPE, '–')\" }",
        "      - { label: Date, bind: \"nonblank(@.EVENT_DATE, '–')\" }",
        "      - { label: Amount, bind: \"@.AMOUNT\", fmt: signed2, tone: sign, total: true }",
        "      - { label: Currency, bind: \"nonblank(@.CURRENCY, '–')\" }",
        "  - id: ladder",
        "    kind: hbar",
        "    title: Netted payments by date (paid − / received +)",
        "    code: PAY",
        "    key: F6",
        "    area: right",
        "    rows: \"group($.EVENTS[?nonblank(@.AMOUNT) && nonblank(@.EVENT_DATE)], 'EVENT_DATE', 'AMOUNT')\"",
        "    fmt: signed0",
        "    tone: sign",
        "  - id: by-month",
        "    kind: hbar",
        "    title: Payments by month",
        "    code: PAYM",
        "    area: right",
        "    rows: \"group($.EVENTS[?nonblank(@.AMOUNT) && nonblank(@.EVENT_DATE)], 'EVENT_DATE', 'AMOUNT', 'month')\"",
        "    fmt: signed0",
        "    tone: sign",
        "  - { id: built, kind: provenance, title: How this view was built }",
        "  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }",
    ]


# the shared blocks of a deal screen (sutras/common/<name>.v1.block.yaml): (first panel id, description)
BLOCKS = [("deal-ticket", "ticket", "The deal ticket (DES): deal, product, parties, dates and settlement, as a booking system's ticket groups them."),
          ("legs", "legs", "The legs side by side: each leg's terms, notional profile (a step line), fixings and cash flows opening to their resets."),
          ("flow-schedule", "flows", "Every cash flow of every leg in one schedule."),
          ("events", "events", "The events by leg (a timeline) and as a table filtered by the Sutra's legs and dates controls (ctl.legs, ctl.when)."),
          ("payments", "ladder", "The netted payment ladder (cash events by date, paid below the line) and payments by month."),
          ("deal-footer", "built", "How the view was built and the entities the deal refers to.")]


def _cut(lines: list[str]) -> dict[str, list[str]]:
    """The panel lines grouped by block: a block runs from its first panel to the next block's first panel."""
    firsts = {first: name for name, first, _ in BLOCKS}
    out: dict[str, list[str]] = {}
    current = None
    for line in lines:
        stripped = line.strip()
        pid = stripped[len("- id: "):] if stripped.startswith("- id: ") else None
        if pid is None and stripped.startswith("- { id: "):
            pid = stripped[len("- { id: "):].split(",")[0]
        if pid in firsts and line.startswith("  - "):
            current = firsts[pid]
        out.setdefault(current, []).append(line)
    return out


def blocks() -> dict[str, str]:
    """{block name: its YAML (after rachana: 1)}: the shared panels every deal Sutra uses, rate format a parameter."""
    cut = _cut(_panels(RF))
    out = {}
    for name, _first, desc in BLOCKS:
        lines = [f"block: {name}", "version: 1", f"description: {q(desc)}"]
        if any(RF in ln for ln in cut[name]):
            lines.append("params: { rate-fmt: price4 }   # pct4 for rates (a fraction a year), price4 for prices per unit")
        lines += ["panels:"] + cut[name]
        out[name] = "\n".join(lines) + "\n"
    return out


def deal_yaml(code: str, name: str, group: str, desc: str, system: str, sutra: str, extra: list[str] | None = None) -> str:
    """One product type's Sutra: the match, title and strip of a deal, the controls, and the shared blocks (with a product's own panels,
    shown by `when:` for one sub type, after the ticket)."""
    rate_fmt = "pct4" if group in RATE_GROUPS else "price4"
    pill = f"{group} · {name}"
    use = lambda b: f"  - {{ use: {b}@1, with: {{ rate-fmt: {rate_fmt} }} }}" if any(RF in ln for ln in _cut(_panels(RF))[b]) else f"  - {{ use: {b}@1 }}"  # noqa: E731
    lines = [
        f"sutra: {sutra}",
        "version: 1",
        f"description: {q(desc)}",
        f"match: {{ kind: trade, where: \"$.SOURCE_SYSTEM == '{system}' && $.PRODUCT_TYPE == '{code}'\", priority: 10 }}",
        f"title: {{ pill: {q(pill)}, id: $.TRADE_ID, with: \"link($.COUNTERPARTY, 'counterparty')\" }}",
        "strip:",
        "  - { label: Source, bind: $.SOURCE_SYSTEM }",
        f"  - {{ label: Side, bind: {q(side())} }}",
        "  - { label: Notional, bind: \"$.CURRENCY + ' ' + fmt(first($.LEGS).NOTIONAL, 'amount0')\", emphasis: true }",
        "  - { label: Quantity, bind: \"fmt($.QUANTITY, 'amount0') + (nonblank(first($.LEGS).PRICE_UNIT) ? ' ' + first($.LEGS).PRICE_UNIT : '')\" }",
        "  - { label: Trade date, bind: $.TRADE_DATE, fmt: date }",
        "  - { label: Maturity, bind: $.MATURITY_DATE, fmt: date }",
        "  - { label: Status, bind: \"nonblank($.TRADE_STATUS, '–') + (nonblank($.TRADE_VERSION) ? ' v' + $.TRADE_VERSION : '')\", tone: status }",
        "  - { label: Portfolio, bind: \"link($.PORTFOLIO, 'book')\" }",
        "controls:                       # the events by leg and by date: a trader's 'what is paid when, on which leg'",
        "  - { id: legs, kind: multi-select, label: Legs, options: \"$.LEGS[*].LEG_ID\", default: all, placement: header, panel: event-list, remember: user }",
        "  - { id: when, kind: date-range, label: Between, placement: header, panel: event-list }",
        "panels:",
        use("deal-ticket"),
        *(extra or []),
        *[use(b) for b, _, _ in BLOCKS[1:]],
        "keys: { F7: \"link($.COUNTERPARTY, 'counterparty-trades')\", F8: impact, F9: raw }",
    ]
    return "\n".join(lines) + "\n"


def kv_when(pid: str, title: str, sub: str, code: str, columns: list[tuple[str, str, str | None]]) -> list[str]:
    """A product's own panel, shown only for one sub type (`when:`): a label/value grid."""
    out = [f"  - id: {pid}", "    kind: kv", f"    title: {q(title)}", f"    code: {code}", f"    when: \"$.PRODUCT_SUB_TYPE == '{sub}'\"", "    columns:"]
    for label, bind, fmt in columns:
        out.append(f"      - {{ label: {q(label)}, bind: {q(bind)}" + (f", fmt: {fmt}" if fmt else "") + " }")
    return out


PHYS = "$.LEGS[?@.LEG_TYPE == 'PHYSICAL'][0]"
FIXED = "$.LEGS[?@.LEG_TYPE == 'FIXED'][0]"
FLOAT = "$.LEGS[?@.LEG_TYPE == 'FLOAT'][0]"
# each Endur product type's own panels, one per sub type it books (shown by when:, inside the product's one Sutra)
ENDUR_EXTRA = {
    "ComSwap": kv_when("fixed-float", "Fixed against floating", "ENGY-SWAP", "SWAP", [
        ("Fixed price", f"{FIXED}.CASH_FLOWS[0].STRIKE_PRICE", "price4"), ("Floating index", f"nonblank({FLOAT}.INDEX.NAME, '–')", None),
        ("Averaging", f"nonblank({FLOAT}.RESET_CONVENTION, '–')", None), ("Price unit", f"nonblank({FIXED}.PRICE_UNIT, '–')", None),
        ("Periods", f"size({FIXED}.CASH_FLOWS)", None)])
        + kv_when("basis", "Basis: one index against another", "ENGY-B-SWAP", "BAS", [
            ("Index, leg 1", "nonblank($.LEGS[0].INDEX.NAME, '–')", None), ("Index, leg 2", "nonblank($.LEGS[1].INDEX.NAME, '–')", None),
            ("Spread, leg 2", "$.LEGS[1].FLOAT_SPREAD", "price4"), ("Location", "nonblank($.LEGS[1].INDEX_LOCATION_NAME, '–')", None),
            ("Averaging", "nonblank($.LEGS[0].RESET_CONVENTION, '–')", None)]),
    "ComFut": kv_when("contract", "Exchange contract", "ENGY-EXCH-FUT", "FUT", [
        ("Quantity", "fmt($.QUANTITY, 'amount0') + ' ' + nonblank(first($.LEGS).PRICE_UNIT, '')", None), ("Price", "$.PRICE", "price4"),
        ("Index", "nonblank(first($.LEGS).INDEX.NAME, '–')", None), ("Settles", "first($.LEGS).CASH_FLOWS[0].PAYMENT_DATE", "date"),
        ("Settlement", "nonblank(first($.LEGS).SETTLEMENT_TYPE, '–')", None)]),
    "Cash": kv_when("payment", "Cash payment", "CASH", "CSH", [
        ("Amount", "$.CURRENCY + ' ' + fmt(first($.LEGS).NOTIONAL, 'amount2')", None), ("Paid on", "first($.LEGS).CASH_FLOWS[0].PAYMENT_DATE", "date"),
        ("Direction", "$.BUY_SELL_CODE == 'B' ? 'Paid' : 'Received'", None), ("Settlement", "nonblank(first($.LEGS).SETTLEMENT_TYPE, '–')", None)]),
    "CallNot": kv_when("deposit", "Call-notice deposit (nostro)", "NOSTRO", "DEP", [
        ("Principal", "$.CURRENCY + ' ' + fmt($.QUANTITY, 'amount0')", None), ("Rate", "nonblank(first($.LEGS).CASH_FLOWS[0].RATE, '–')", None),
        ("Interest periods", "size(first($.LEGS).CASH_FLOWS)", None),
        ("Next interest", "first($.LEGS).CASH_FLOWS[?@.PAYMENT_DATE >= today()][0].PAYMENT_DATE", "date"),
        ("Lease currency", "nonblank(first($.LEGS).LEASE_CURRENCY, '–')", None)]),
    "Commodity": kv_when("delivery", "Physical delivery at the hub", "NGX-COMM-PHYS", "DLV", [
        ("Hub", f"nonblank({PHYS}.PHYSICAL_LOCATION_NAME, '–')", None), ("Index location", f"nonblank({PHYS}.INDEX_LOCATION_NAME, '–')", None),
        ("Daily volume", f"{PHYS}.DAILY_VOLUME", "amount0"), ("Unit", f"nonblank({PHYS}.PRICE_UNIT, '–')", None),
        ("Delivery from", f"{PHYS}.CASH_FLOWS[0].START_DATE", "date"), ("Delivery to", f"last({PHYS}.CASH_FLOWS).END_DATE", "date"),
        ("Settlement", f"nonblank({PHYS}.SETTLEMENT_TYPE, '–')", None)])
        + kv_when("inventory", "Warehouse warrants (inventory)", "COMM-WARRANTS-INV", "INV", [
            ("Vault", "nonblank($.VAULT_LOCATION_ALLOCATION, '–')", None), ("Registered", "nonblank($.REGISTRY_DELIVERY_DATE, '–')", None),
            ("Quantity", "fmt($.QUANTITY, 'amount0') + ' ' + nonblank(first($.LEGS).PRICE_UNIT, '')", None), ("Price", "$.PRICE", "price4"),
            ("Lease perpetuity", "nonblank($.LEASE_PERPETUITY_FLAG, '–')", None), ("Precious metal", "nonblank($.PRECIOUS_METAL_FLAG, '–')", None)]),
}


def derived_yaml(kind: str) -> str:
    mnemonic, label, _group, id_field, _target, what = DERIVED[kind]
    target = DERIVED[kind][4]
    events = "flatten($.TRADES[*].EVENTS)[?nonblank(@.AMOUNT) && nonblank(@.EVENT_DATE)]"
    window = lambda n: f"{events}[?days(today(), @.EVENT_DATE) >= 0 && days(today(), @.EVENT_DATE) <= {n}]"   # noqa: E731
    lines = [
        f"sutra: {kind}",
        "version: 1",
        f"description: {q(what + ': live trades, upcoming payments, notional by product type and index, maturities, payments by month.')}",
        f"match: {{ kind: {kind}, priority: 10 }}",
        f"title: {{ pill: {q(label)}, id: $.{id_field}" + (f", with: \"link($.{id_field}, '{target}')\"" if target else "") + " }",
        "strip:",
        "  - { label: Trades, bind: $.TRADE_COUNT, emphasis: true }",
        "  - { label: Notional (all currencies), bind: $.NOTIONAL, fmt: compact }",
        "  - { label: Source systems, bind: \"size($.SOURCE_SYSTEMS)\" }",
        "  - { label: Product types, bind: \"size($.PRODUCT_TYPES)\" }",
        "  - { label: Currencies, bind: \"size($.CURRENCIES)\" }",
        "  - { label: Last maturity, bind: $.LAST_MATURITY, fmt: date }",
        "panels:",
        "  - id: pay7",
        "    kind: metric",
        "    title: Payments, next 7 days",
        f"    value: \"sum({window(7)}, 'AMOUNT')\"",
        "    fmt: signed0",
        "    tone: sign",
        f"    caption: \"${{size({window(7)})}} events\"",
        "    span: 4",
        "  - id: pay30",
        "    kind: metric",
        "    title: Payments, next 30 days",
        f"    value: \"sum({window(30)}, 'AMOUNT')\"",
        "    fmt: signed0",
        "    tone: sign",
        f"    caption: \"${{size({window(30)})}} events\"",
        "    span: 4",
        "  - id: pay90",
        "    kind: metric",
        "    title: Payments, next 90 days",
        f"    value: \"sum({window(90)}, 'AMOUNT')\"",
        "    fmt: signed0",
        "    tone: sign",
        f"    caption: \"${{size({window(90)})}} events\"",
        "    span: 4",
        "  - id: trades",
        "    kind: table",
        "    title: Live trades",
        "    code: TRD",
        "    key: F2",
        "    rows: $.TRADES",
        "    pivot: { rows: [PRODUCT_TYPE], columns: [SOURCE_SYSTEM], values: [{ field: NOTIONAL, agg: sum }], filters: [CURRENCY] }",
        "    columns:",
        "      - { label: Trade, bind: \"link(@.TRADE_ID, 'trade')\" }",
        "      - { label: Source, bind: \"@.SOURCE_SYSTEM\" }",
        "      - { label: Product, bind: \"@.PRODUCT_TYPE\" }",
        f"      - {{ label: Side, bind: {q(side('@.BUY_SELL_CODE'))} }}",
        "      - { label: Currency, bind: \"@.CURRENCY\" }",
        "      - { label: Notional, bind: \"@.NOTIONAL\", fmt: amount0, total: true }",
        "      - { label: Index, bind: \"nonblank(@.INDEX_NAME, '–')\" }",
        "      - { label: Maturity, bind: \"@.MATURITY_DATE\", fmt: date }",
        "      - { label: Status, bind: \"nonblank(@.TRADE_STATUS, '–')\", tone: status }",
        "  - id: upcoming",
        "    kind: ladder",
        "    title: Upcoming payments, next 90 days",
        "    code: PAY",
        "    key: F3",
        f"    rows: \"{window(90)}\"",
        "    empty: No payments in the next 90 days",
        "    columns:",
        "      - { label: Date, bind: \"@.EVENT_DATE\", fmt: date }",
        "      - { label: In days, bind: \"days(today(), @.EVENT_DATE)\" }",
        "      - { label: Type, bind: \"nonblank(@.PAYMENT_TYPE, @.EVENT_TYPE, '–')\" }",
        "      - { label: Leg, bind: \"nonblank(@.LEG_ID, 'deal')\" }",
        "      - { label: Currency, bind: \"@.CURRENCY\" }",
        "      - { label: Amount, bind: \"@.AMOUNT\", fmt: signed0, tone: sign, total: true }",
        "  - id: by-product",
        "    kind: hbar",
        "    title: Notional by product type",
        "    code: PROD",
        "    key: F4",
        "    area: right",
        "    shape: donut",
        "    rows: \"group($.TRADES, 'PRODUCT_TYPE', 'NOTIONAL')\"",
        "    fmt: compact",
        "  - id: by-index",
        "    kind: hbar",
        "    title: Notional by index",
        "    code: IDX",
        "    area: right",
        "    rows: \"group($.TRADES[?nonblank(@.INDEX_NAME)], 'INDEX_NAME', 'NOTIONAL')\"",
        "    fmt: compact",
        "  - id: maturities",
        "    kind: hbar",
        "    title: Maturity ladder (notional by year)",
        "    code: MAT",
        "    key: F5",
        "    area: right",
        "    rows: \"group($.TRADES, 'MATURITY_DATE', 'NOTIONAL', 'year')\"",
        "    fmt: compact",
        "  - id: by-month",
        "    kind: hbar",
        "    title: Payments by month (paid − / received +)",
        "    code: PAYM",
        "    key: F6",
        f"    rows: \"group({events}, 'EVENT_DATE', 'AMOUNT', 'month')\"",
        "    fmt: signed0",
        "    tone: sign",
        "  - { id: built, kind: provenance, title: How this view was built }",
        "keys: { " + (f"F7: \"link($.{id_field}, '{target}')\", " if target else "") + "F9: raw }",
    ]
    return "\n".join(lines) + "\n"


def derived_connector() -> dict:
    """The derived connector's settings: one derived kind per axis, each with its rows (one per member trade) and totals."""
    out: dict = {"refresh": "60s"}
    for kind, (_m, _l, group, id_field, _t, _w) in DERIVED.items():
        out[kind] = {"from": "trade", "group-by": group, "id-field": id_field, "members": "TRADE_IDS", "max-members": 500,
                     "rows-field": "TRADES", "fields": dict(FIELDS), "rows": dict(ROWS)}
    return out
