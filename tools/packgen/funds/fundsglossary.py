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

"""Every field of the funds pack: what it means, its unit and where it comes from (the SEC table and column, or how it is
computed). make.py writes it as the pack's config/about.yaml glossary, which the contracts (tools/contracts.py) and the About drawer
read, and docs/guides/PACK_DEVELOPER_GUIDE.md Part II lists it.

    FIELDS[kind][path] = (term, meaning, unit or "", source)        path: A or A.B (a list's element fields: HOLDINGS.PCT)
"""
from __future__ import annotations

NP, NC, RR = "N-PORT", "N-CEN", "risk/return summary"
PCT = "% of net assets"

HOLDING = {   # a holding row: HOLDINGS and DERIVATIVES share it
    "NAME": ("Issuer", "The issuer's name as the fund filed it.", "", f"{NP} FUND_REPORTED_HOLDING.ISSUER_NAME"),
    "TITLE": ("Title", "The title of the security or contract.", "", f"{NP} FUND_REPORTED_HOLDING.ISSUER_TITLE"),
    "TICKER": ("Ticker", "The security's ticker where the fund gave one, written as us-equities writes it (BRK/B is BRK-B).", "", f"{NP} IDENTIFIERS.IDENTIFIER_TICKER"),
    "CUSIP": ("CUSIP", "The security's CUSIP (none for N/A or zeros).", "", f"{NP} FUND_REPORTED_HOLDING.ISSUER_CUSIP"),
    "ISIN": ("ISIN", "The security's ISIN where the fund gave one.", "", f"{NP} IDENTIFIERS.IDENTIFIER_ISIN"),
    "LEI": ("Issuer LEI", "The issuer's Legal Entity Identifier; opens its GLEIF record (gleif pack).", "", f"{NP} FUND_REPORTED_HOLDING.ISSUER_LEI"),
    "ASSET": ("Asset category", "N-PORT's asset category: EC common equity, EP preferred, DBT debt, ABS-MBS, ABS-ASBS, ABS-CBDO, ABS-O, DCO, DCR, DE, DFE, DIR, DO derivatives, SN, LON, RA repo, STIV, RE, COMM, OTHER.", "", f"{NP} FUND_REPORTED_HOLDING.ASSET_CAT"),
    "ISSUER_TYPE": ("Issuer type", "CORP, UST, USGA, USGSE, MUN, NUSS (non-US sovereign), PF (private fund), RF (registered fund), OTHER.", "", f"{NP} FUND_REPORTED_HOLDING.ISSUER_TYPE"),
    "COUNTRY": ("Country", "The country of investment or issuer (ISO 3166).", "", f"{NP} FUND_REPORTED_HOLDING.INVESTMENT_COUNTRY"),
    "CURRENCY": ("Currency", "The currency the holding is denominated in.", "", f"{NP} FUND_REPORTED_HOLDING.CURRENCY_CODE"),
    "VALUE": ("Value", "The holding's value as reported, in US dollars.", "USD", f"{NP} FUND_REPORTED_HOLDING.CURRENCY_VALUE"),
    "PCT": ("Weight", "The holding's value over the fund's net assets.", PCT, f"{NP} FUND_REPORTED_HOLDING.PERCENTAGE"),
    "BALANCE": ("Balance", "Shares, principal amount or contracts held, in UNIT.", "", f"{NP} FUND_REPORTED_HOLDING.BALANCE"),
    "UNIT": ("Unit", "NS shares, PA principal amount, NC contracts, OU other units.", "", f"{NP} FUND_REPORTED_HOLDING.UNIT"),
    "PAYOFF": ("Payoff", "Long, Short or N/A.", "", f"{NP} FUND_REPORTED_HOLDING.PAYOFF_PROFILE"),
    "DERIVATIVE": ("Derivative category", "FWD, FUT, SWP, OPT, SWO, WAR or OTH for a derivative; empty otherwise.", "", f"{NP} FUND_REPORTED_HOLDING.DERIVATIVE_CAT"),
    "TYPE": ("Derivative type", "The derivative category in words (Future, Swap, Option ...).", "", "computed from DERIVATIVE_CAT"),
    "CPTY": ("Counterparty", "The derivative's or repo's counterparty ('(cleared)' for a centrally cleared repo).", "", f"{NP} DERIVATIVE_COUNTERPARTY, REPURCHASE_COUNTERPARTY"),
    "CPTY_LEI": ("Counterparty LEI", "The counterparty's LEI; opens its GLEIF record.", "", f"{NP} DERIVATIVE_COUNTERPARTY_LEI, REPURCHASE_COUNTERPARTY.LEI"),
    "NOTIONAL": ("Notional", "A derivative's notional (a forward's amount bought), as filed.", "", f"{NP} FUT_FWD_NONFOREIGNCUR_CONTRACT, NONFOREIGN_EXCHANGE_SWAP, FWD_FOREIGNCUR_CONTRACT_SWAP, SWAPTION_OPTION_WARNT_DERIV"),
    "EXPIRY": ("Expiry", "A derivative's expiry, termination or settlement date; a repo's maturity.", "", f"{NP} the derivative tables, REPURCHASE_AGREEMENT.MATURITY_DATE"),
    "UNREALIZED": ("Unrealised appreciation", "A derivative's unrealised appreciation (negative: depreciation).", "USD", f"{NP} the derivative tables, UNREALIZED_APPRECIATION"),
    "REFERENCE": ("Reference index", "The index or basket a derivative references.", "", f"{NP} DESC_REF_INDEX_BASKET.INDEX_NAME"),
    "COMPANY": ("us-equities company", "The us-equities company the holding is (by ticker, else by the issuer's LEI for an equity); opens USEQ.", "", "computed: the committed us-equities companies"),
    "FUND": ("Fund of the pack", "The pack's fund the holding is (a fund of funds holding another fund); opens FUND.", "", "computed: the share class tickers"),
    "SECTOR": ("SIC division", "The SIC division of the us-equities company.", "", "us-equities us-company.SECTOR (SEC EDGAR)"),
    "ALSO_IN": ("Also in", "How many other funds of the pack hold the same company.", "funds", "computed from every fund's holdings"),
}
HOLDER = {
    "FUND": ("Fund", "The fund's ticker; opens FUND.", "", "computed"),
    "FUND_NAME": ("Fund name", "The fund's name.", "", f"{NP} FUND_REPORTED_INFO.SERIES_NAME"),
    "TYPE": ("Type", "The fund's type.", "", f"{NC} FUND_REPORTED_INFO.IS_ETF, the Nasdaq Trader directory"),
    "PCT": ("Weight in the fund", "The company's weight in the fund (every line of it added).", PCT, f"{NP} FUND_REPORTED_HOLDING.PERCENTAGE"),
    "VALUE": ("Value", "The value the fund holds.", "USD", f"{NP} FUND_REPORTED_HOLDING.CURRENCY_VALUE"),
    "SHARES": ("Shares", "The shares the fund holds (balances in shares only).", "shares", f"{NP} FUND_REPORTED_HOLDING.BALANCE"),
    "REPORT_DATE": ("Report date", "The date the fund's report is as of.", "", f"{NP} SUBMISSION.REPORT_DATE"),
    "NET_ASSETS": ("Fund net assets", "The fund's net assets.", "USD", f"{NP} FUND_REPORTED_INFO.NET_ASSETS"),
}
GROUP = {
    "KEY": ("Key", "The code grouped by (an asset category, issuer type, country or SIC division).", "", "computed"),
    "LABEL": ("Label", "The code in words; Other (n) gathers the smallest.", "", "computed"),
    "PCT": ("Weight", "The holdings' weight added up.", PCT, f"computed from {NP} PERCENTAGE"),
    "VALUE": ("Value", "The holdings' value added up.", "USD", f"computed from {NP} CURRENCY_VALUE"),
    "COUNT": ("Holdings", "The number of holdings in the group.", "holdings", "computed"),
}
PROV = {
    "PROVENANCE.SOURCE": ("Source", "Where the data comes from.", "", "written by tools/packgen/funds/fundsbuild.py"),
    "PROVENANCE.LICENCE": ("Licence", "The source's licence (public domain).", "", "written by tools/packgen/funds/fundsbuild.py"),
    "PROVENANCE.RETRIEVED_AT": ("Retrieved", "The date the data sets were downloaded.", "", "the download cache"),
    "PROVENANCE.NOTE": ("Note", "What the document holds and from which filings.", "", "written by tools/packgen/funds/fundsbuild.py"),
}

FUND = {
    "TICKER": ("Ticker", "The fund's ticker: its ETF share class where it has one, else its oldest share class; a unit investment trust's listed ticker.", "", "SEC company_tickers_mf.json, N-CEN SECURITY_EXCHANGE, the Nasdaq Trader directory, company_tickers_exchange.json"),
    "NAME": ("Name", "The series name as filed (upper-case names title-cased).", "", f"{NP} FUND_REPORTED_INFO.SERIES_NAME"),
    "TYPE": ("Type", "ETF, Mutual fund, Mutual fund with an ETF share class, or ETF (unit investment trust).", "", f"{NC} FUND_REPORTED_INFO.IS_ETF, ETF.tsv, the Nasdaq Trader directory"),
    "FUND_KEY": ("Fund key", "The SEC series id, or CIK<cik> for a fund without one (a unit investment trust).", "", f"{NP} FUND_REPORTED_INFO.SERIES_ID, REGISTRANT.CIK"),
    "SERIES_ID": ("SEC series", "The SEC series id (S and nine digits).", "", f"{NP} FUND_REPORTED_INFO.SERIES_ID"),
    "CIK": ("CIK", "The registrant's SEC Central Index Key (ten digits).", "", f"{NP} REGISTRANT.CIK"),
    "REGISTRANT": ("Registrant", "The trust or company the series belongs to.", "", f"{NP} REGISTRANT.REGISTRANT_NAME"),
    "LEI": ("Fund LEI", "The fund's (series') LEI; opens its GLEIF record.", "", f"{NP} FUND_REPORTED_INFO.SERIES_LEI"),
    "REGISTRANT_LEI": ("Registrant LEI", "The registrant's LEI.", "", f"{NP} REGISTRANT.LEI"),
    "TICKERS": ("Share class tickers", "Every share class ticker, the fund's own first; the type-ahead matches any of them (FUND VTSAX opens VTI).", "", "SEC company_tickers_mf.json"),
    "CLASSES": ("Share classes", "Each share class: id, ticker, name, expense ratio and three-month return.", "", f"company_tickers_mf.json, {NC} SHARES_OUTSTANDING, {RR}, {NP} MONTHLY_TOTAL_RETURN"),
    "CLASSES.CLASS_ID": ("Class id", "The SEC class id (C and nine digits).", "", "company_tickers_mf.json classId"),
    "CLASSES.TICKER": ("Class ticker", "The share class's ticker.", "", "company_tickers_mf.json symbol"),
    "CLASSES.CLASS_NAME": ("Class name", "The class's name (Admiral Shares, Class A ...).", "", f"{NC} SHARES_OUTSTANDING.CLASS_NAME"),
    "CLASSES.EXPENSE_RATIO": ("Class expense ratio", "The class's net expense ratio (gross where no net one is filed).", "%", f"{RR} NetExpensesOverAssets, ExpensesOverAssets"),
    "CLASSES.RETURN_3M": ("Class return, three months", "The class's three monthly returns compounded.", "%", f"{NP} MONTHLY_TOTAL_RETURN"),
    "CLASS_COUNT": ("Share classes", "How many share classes the fund has.", "classes", "company_tickers_mf.json"),
    "PRIMARY_CLASS": ("Class of the ticker", "The class id of TICKER; its fees and returns are the page's.", "", "company_tickers_mf.json"),
    "IS_ETF": ("ETF", "True when the fund is, or has, an exchange-traded share class.", "", f"{NC} IS_ETF, ETF.tsv, SECURITY_EXCHANGE; the Nasdaq Trader directory"),
    "IS_INDEX": ("Index fund", "True when the fund says it is an index fund, or its name names a tracked index.", "", f"{NC} FUND_REPORTED_INFO.IS_INDEX"),
    "IS_FUND_OF_FUNDS": ("Fund of funds", "True when the fund invests in other funds.", "", f"{NC} FUND_REPORTED_INFO.IS_FUND_OF_FUND"),
    "IS_TARGET_DATE": ("Target date", "True for a target-date fund.", "", f"{NC} FUND_REPORTED_INFO.IS_TARGET_DATE"),
    "IS_MASTER_FEEDER": ("Master-feeder", "True when the fund is part of a master-feeder structure.", "", f"{NC} FUND_REPORTED_INFO.IS_MASTER_FEEDER"),
    "INDEX_ID": ("Tracked index", "The us-equities index the fund tracks (SP500, NDX, DJIA), matched by its name; opens IDX.", "", "computed from the name (N-CEN says whether a fund is an index fund, not which index)"),
    "EXCHANGE": ("Exchange", "Where the ETF share class lists.", "", "the Nasdaq Trader directory; N-CEN SECURITY_EXCHANGE"),
    "CREATION_UNIT": ("Creation unit", "Shares in one creation unit of the ETF.", "shares", f"{NC} ETF.NUM_SHARES_PER_CREATION_UNIT"),
    "AP_COUNT": ("Authorised participants", "How many authorised participants the ETF reports.", "firms", f"{NC} AUTHORIZED_PARTICIPANT"),
    "AUTHORISED_PARTICIPANTS": ("Authorised participants", "The ten most active authorised participants by purchases and redemptions.", "", f"{NC} AUTHORIZED_PARTICIPANT"),
    "AUTHORISED_PARTICIPANTS.NAME": ("Participant", "The authorised participant.", "", f"{NC} AUTHORIZED_PARTICIPANT.PARTICIPANT_NAME"),
    "AUTHORISED_PARTICIPANTS.LEI": ("Participant LEI", "Its LEI.", "", f"{NC} AUTHORIZED_PARTICIPANT.PARTICIPANT_LEI"),
    "AUTHORISED_PARTICIPANTS.PURCHASES": ("Purchases", "Creation units it bought over the year, by value.", "USD", f"{NC} AUTHORIZED_PARTICIPANT.PURCHASE_VALUE"),
    "AUTHORISED_PARTICIPANTS.REDEMPTIONS": ("Redemptions", "Creation units it redeemed over the year, by value.", "USD", f"{NC} AUTHORIZED_PARTICIPANT.REDEEM_VALUE"),
    "ADVISER": ("Adviser", "The fund's investment adviser.", "", f"{NC} ADVISER.ADVISER_NAME"),
    "ADVISER_LEI": ("Adviser LEI", "The adviser's LEI; opens its GLEIF record.", "", f"{NC} ADVISER.ADVISER_LEI"),
    "SUB_ADVISERS": ("Sub-advisers", "Up to six sub-advisers.", "", f"{NC} ADVISER (sub-advisers)"),
    "CUSTODIAN": ("Custodian", "The fund's custodian (not a sub-custodian).", "", f"{NC} CUSTODIAN.CUSTODIAN_NAME"),
    "CUSTODIAN_LEI": ("Custodian LEI", "The custodian's LEI.", "", f"{NC} CUSTODIAN.CUSTODIAN_LEI"),
    "ADMINISTRATOR": ("Administrator", "The fund's administrator (not a sub-administrator).", "", f"{NC} ADMIN.ADMIN_NAME"),
    "TRANSFER_AGENT": ("Transfer agent", "The fund's transfer agent.", "", f"{NC} TRANSFER_AGENT.TRANSFERAGENT_NAME"),
    "AUDITOR": ("Auditor", "The registrant's independent public accountant.", "", f"{NC} PUBLIC_ACCOUNTANT.PUB_ACCOUNTANT_NAME"),
    "AUDITOR_LEI": ("Auditor LEI", "The accountant's LEI.", "", f"{NC} PUBLIC_ACCOUNTANT.PUB_ACCOUNTANT_LEI"),
    "LENDS_SECURITIES": ("Lends securities", "True when the fund lent securities in the year.", "", f"{NC} FUND_REPORTED_INFO.DID_LEND_SECURITIES"),
    "NCEN_PERIOD": ("Census period", "The fiscal year end of the N-CEN report used.", "", f"{NC} SUBMISSION.REPORT_ENDING_PERIOD"),
    "NCEN_ACCESSION": ("N-CEN accession", "The N-CEN filing used.", "", f"{NC} SUBMISSION.ACCESSION_NUMBER"),
    "GLEIF_RELATIONSHIPS": ("GLEIF relationships", "The fund's relationships in GLEIF: managed by, sub-fund of (its umbrella), feeder to.", "", "GLEIF golden copy level 2 (rr.csv)"),
    "GLEIF_RELATIONSHIPS.TYPE": ("Relationship", "Managed by, Sub-fund of or Feeder to.", "", "GLEIF rr.csv Relationship.RelationshipType"),
    "GLEIF_RELATIONSHIPS.LEI": ("Related LEI", "The manager's, umbrella's or master fund's LEI.", "", "GLEIF rr.csv Relationship.EndNode.NodeID"),
    "GLEIF_RELATIONSHIPS.STATUS": ("Status", "ACTIVE or INACTIVE.", "", "GLEIF rr.csv Relationship.RelationshipStatus"),
    "RANK": ("Rank", "The fund's rank by net assets among the funds with a ticker.", "", "computed"),
    "REPORT_DATE": ("Report date", "The date the N-PORT report is as of.", "", f"{NP} SUBMISSION.REPORT_DATE"),
    "FILED": ("Filed", "The date the N-PORT report was filed.", "", f"{NP} SUBMISSION.FILING_DATE"),
    "ACCESSION": ("N-PORT accession", "The N-PORT filing used.", "", f"{NP} SUBMISSION.ACCESSION_NUMBER"),
    "NPORT_URL": ("N-PORT on EDGAR", "The filing's folder on EDGAR.", "", "computed from CIK and ACCESSION"),
    "TOTAL_ASSETS": ("Total assets", "Total assets.", "USD", f"{NP} FUND_REPORTED_INFO.TOTAL_ASSETS"),
    "NET_ASSETS": ("Net assets", "Net assets: what the 200 are chosen by.", "USD", f"{NP} FUND_REPORTED_INFO.NET_ASSETS"),
    "EXPENSE_RATIO": ("Expense ratio", "The net expense ratio of the fund's share class (gross where no net one is filed).", "%", f"{RR} NetExpensesOverAssets, ExpensesOverAssets"),
    "GROSS_EXPENSE_RATIO": ("Gross expense ratio", "Total annual operating expenses before waivers.", "%", f"{RR} ExpensesOverAssets"),
    "MANAGEMENT_FEE": ("Management fee", "The management fee.", "%", f"{RR} ManagementFeesOverAssets"),
    "TURNOVER": ("Turnover", "Portfolio turnover in the last fiscal year.", "%", f"{RR} PortfolioTurnoverRate"),
    "PROSPECTUS_FILED": ("Prospectus filed", "The date of the risk/return summary used.", "", f"{RR} sub.tsv filed"),
    "ANNUAL_RETURNS": ("Calendar-year returns", "Up to ten calendar-year total returns of the share class (the prospectus bar chart).", "", f"{RR} AnnualReturnPercent, AnnlRtrPct"),
    "ANNUAL_RETURNS.YEAR": ("Year", "The calendar year.", "", f"{RR} num.tsv ddate"),
    "ANNUAL_RETURNS.RETURN": ("Return", "The year's total return.", "%", f"{RR} AnnualReturnPercent, AnnlRtrPct"),
    "RETURN_LAST_YEAR": ("Last calendar year", "The latest calendar-year return filed.", "%", f"{RR} AnnualReturnPercent, AnnlRtrPct"),
    "RETURN_YEAR": ("Its year", "The year of RETURN_LAST_YEAR.", "", f"{RR}"),
    "RETURN_3M": ("Return, three months", "The share class's three monthly returns of the report compounded.", "%", f"{NP} MONTHLY_TOTAL_RETURN"),
    "FLOWS": ("Flows", "Each of the report's three months: sales, reinvestments, redemptions, net flow and the class's return.", "", f"{NP} FUND_REPORTED_INFO SALES_FLOW_MON1-3 ..."),
    "FLOWS.MONTH": ("Month", "The month (yyyy-mm), the third being the report's.", "", "computed from REPORT_DATE"),
    "FLOWS.SALES": ("Sales", "Shares sold (subscriptions), by value.", "USD", f"{NP} FUND_REPORTED_INFO.SALES_FLOW_MONn"),
    "FLOWS.REINVESTMENT": ("Reinvestment", "Distributions reinvested.", "USD", f"{NP} FUND_REPORTED_INFO.REINVESTMENT_FLOW_MONn"),
    "FLOWS.REDEMPTIONS": ("Redemptions", "Shares redeemed, by value.", "USD", f"{NP} FUND_REPORTED_INFO.REDEMPTION_FLOW_MONn"),
    "FLOWS.NET_FLOW": ("Net flow", "Sales plus reinvestment less redemptions.", "USD", "computed"),
    "FLOWS.RETURN": ("Return", "The share class's total return in the month.", "%", f"{NP} MONTHLY_TOTAL_RETURN.MONTHLY_TOTAL_RETURNn"),
    "SALES": ("Sales, three months", "The three months' sales.", "USD", f"{NP} SALES_FLOW_MON1-3"),
    "REDEMPTIONS": ("Redemptions, three months", "The three months' redemptions.", "USD", f"{NP} REDEMPTION_FLOW_MON1-3"),
    "REINVESTMENT": ("Reinvestment, three months", "The three months' reinvested distributions.", "USD", f"{NP} REINVESTMENT_FLOW_MON1-3"),
    "NET_FLOW": ("Net flow, three months", "Sales plus reinvestment less redemptions over the three months.", "USD", "computed"),
    "HOLDING_COUNT": ("Holdings", "Every holding the fund reported.", "holdings", f"{NP} FUND_REPORTED_HOLDING"),
    "HOLDINGS_SHOWN": ("Holdings kept", "Holdings kept in HOLDINGS (the 600 largest by value).", "holdings", "computed"),
    "HOLDINGS_REST_COUNT": ("Other holdings", "Holdings not kept.", "holdings", "computed"),
    "HOLDINGS_REST_PCT": ("Their weight", "The weight of the holdings not kept.", PCT, "computed"),
    "HOLDINGS_REST_VALUE": ("Their value", "The value of the holdings not kept.", "USD", "computed"),
    "TOP10_PCT": ("Top 10", "The weight of the ten largest holdings.", PCT, "computed"),
    "TOP50_PCT": ("Top 50", "The weight of the fifty largest holdings.", PCT, "computed"),
    "HHI": ("Herfindahl index", "The sum of the squared weights (as fractions): 1 is one holding, near 0 is many small ones.", "", "computed"),
    "LARGEST": ("Largest holding", "The largest holding by value.", "", "computed"),
    "LARGEST_PCT": ("Its weight", "The largest holding's weight.", PCT, "computed"),
    "WITH_LEI": ("Holdings with an LEI", "Holdings whose issuer LEI was filed.", "holdings", "computed"),
    "EQUITY_PCT": ("Equities", "The weight of common equity (asset category EC).", PCT, "computed"),
    "US_COMPANY_PCT": ("us-equities companies", "The weight of the holdings that are us-equities companies.", PCT, "computed"),
    "US_COMPANIES": ("us-equities companies held", "How many us-equities companies the fund holds.", "companies", "computed"),
    "BY_ASSET": ("By asset type", "The weight by N-PORT asset category.", "", "computed"),
    "BY_ISSUER_TYPE": ("By issuer type", "The weight by issuer type.", "", "computed"),
    "BY_COUNTRY": ("By country", "The weight by country, the largest ten.", "", "computed"),
    "BY_SECTOR": ("By SIC division", "The weight of the us-equities companies held, by SIC division.", "", "computed"),
    "DERIVATIVE_COUNT": ("Derivative positions", "How many derivatives the fund holds.", "positions", "computed"),
    "DERIVATIVES_NOTIONAL": ("Derivatives notional", "The notionals added up, by size.", "USD", "computed"),
    "DERIVATIVES": ("Derivatives", "The fifty largest derivative positions by notional, with their counterparties.", "", f"{NP} part C.11"),
    "COUNTERPARTIES": ("Counterparties", "The counterparties of the derivatives and repos, by notional: positions, notional, value.", "", f"{NP} DERIVATIVE_COUNTERPARTY, REPURCHASE_COUNTERPARTY"),
    "COUNTERPARTIES.NAME": ("Counterparty", "The counterparty.", "", f"{NP} DERIVATIVE_COUNTERPARTY_NAME"),
    "COUNTERPARTIES.LEI": ("Its LEI", "The counterparty's LEI; opens its GLEIF record.", "", f"{NP} DERIVATIVE_COUNTERPARTY_LEI"),
    "COUNTERPARTIES.POSITIONS": ("Positions", "Positions with the counterparty.", "positions", "computed"),
    "COUNTERPARTIES.NOTIONAL": ("Notional", "Their notionals added up, by size.", "USD", "computed"),
    "COUNTERPARTIES.VALUE": ("Value", "Their values added up.", "USD", "computed"),
    "COUNTERPARTY_COUNT": ("Counterparties", "How many counterparties.", "counterparties", "computed"),
    "HOLDINGS": ("Holdings", "The 600 largest holdings by value, each with its identifiers and links.", "", f"{NP} FUND_REPORTED_HOLDING, IDENTIFIERS"),
    "MOST_HELD_COMPANY": ("Largest company held", "The largest us-equities company the fund holds.", "", "computed"),
    "MOST_HELD_NAME": ("Its name", "That company's name.", "", "us-equities"),
    "ALSO_HELD_BY": ("Who else holds it", "The ten other funds holding that company the most, by value.", "", "computed from fund-holder"),
    "ACTIVE_SHARE": ("Active share", "Half the sum of the differences between the fund's and the index's weights (VS_INDEX).", "%", "computed"),
    "VIEWS": ("Tabs", "The page's tabs: Look-through holdings, and the fund against its index when there is one.", "", "computed"),
    "VIEWS.VIEW": ("Tab", "LOOK_THROUGH or VS_INDEX.", "", "computed"),
    "VIEWS.TITLE": ("Tab title", "The tab's title.", "", "computed"),
    "VS_INDEX": ("Against its index", "The fund against its tracked index (or the S&P 500 for reference); index weights computed from SEC data.", "", "computed"),
    "VS_INDEX.INDEX_ID": ("Index", "The us-equities index.", "", "us-equities index-membership"),
    "VS_INDEX.INDEX_NAME": ("Index name", "Its name.", "", "us-equities index-membership"),
    "VS_INDEX.BASIS": ("Basis", "tracks (the fund tracks it) or reference (an equity fund compared with the S&P 500).", "", "computed"),
    "VS_INDEX.WEIGHTING": ("Index weights", "market capitalisation computed from SEC data (capped as the Nasdaq-100 caps it), share price for the Dow, or equal weights.", "", "computed"),
    "VS_INDEX.MEMBERS": ("Listed members", "The index's members as the us-equities pack lists them.", "companies", "us-equities index-membership (the holdings of SPY, QQQ and DIA)"),
    "VS_INDEX.MEMBERS_WEIGHTED": ("Members weighted", "Members with a market capitalisation the filings support.", "companies", "computed"),
    "VS_INDEX.MEMBERS_NOT_WEIGHTED": ("Members not weighted", "Members left out of the weights, by ticker.", "", "computed"),
    "VS_INDEX.MEMBERS_HELD": ("Members held", "Listed members the fund holds.", "companies", "computed"),
    "VS_INDEX.FUND_IN_INDEX_PCT": ("In the listed members", "The fund's weight in the listed members.", PCT, "computed"),
    "VS_INDEX.NOT_IN_INDEX_PCT": ("Elsewhere", "The rest: other companies, cash, futures.", PCT, "computed"),
    "VS_INDEX.ACTIVE_SHARE": ("Active share", "Half the sum of the absolute weight differences over the members weighted.", "%", "computed"),
    "VS_INDEX.LARGEST_DIFF_COMPANY": ("Largest difference", "The company with the largest difference.", "", "computed"),
    "VS_INDEX.LARGEST_DIFF_NAME": ("Its name", "Its name.", "", "us-equities"),
    "VS_INDEX.LARGEST_DIFF": ("Difference", "Fund weight less index weight.", "% points", "computed"),
    "VS_INDEX.AS_OF": ("As of", "The fund's report date.", "", f"{NP} SUBMISSION.REPORT_DATE"),
    "VS_INDEX.ROWS": ("Weights", "The 25 largest weights in either, side by side.", "", "computed"),
    "VS_INDEX.ROWS.COMPANY": ("Company", "The us-equities company.", "", "us-equities"),
    "VS_INDEX.ROWS.NAME": ("Name", "Its name.", "", "us-equities"),
    "VS_INDEX.ROWS.SECTOR": ("SIC division", "Its SIC division.", "", "us-equities"),
    "VS_INDEX.ROWS.FUND_PCT": ("Fund weight", "The company's weight in the fund over the members weighted.", "%", "computed"),
    "VS_INDEX.ROWS.INDEX_PCT": ("Index weight", "Its computed market-cap weight in the index.", "%", "computed: SEC shares outstanding times the price N-PORT implies"),
    "VS_INDEX.ROWS.DIFF": ("Difference", "Fund less index.", "% points", "computed"),
    "VS_INDEX.SECTORS": ("Tilt", "Fund and index weights by SIC division.", "", "computed"),
    "VS_INDEX.SECTORS.SECTOR": ("SIC division", "The division.", "", "us-equities"),
    "VS_INDEX.SECTORS.FUND_PCT": ("Fund", "The fund's weight in it.", "%", "computed"),
    "VS_INDEX.SECTORS.INDEX_PCT": ("Index", "The index's weight in it.", "%", "computed"),
    "VS_INDEX.SECTORS.TILT": ("Tilt", "Fund less index.", "% points", "computed"),
    **PROV,
}
FUND.update({f"HOLDINGS.{k}": v for k, v in HOLDING.items() if k != "TYPE"})
FUND.update({f"DERIVATIVES.{k}": v for k, v in HOLDING.items()
             if k in ("NAME", "TITLE", "DERIVATIVE", "TYPE", "PAYOFF", "CURRENCY", "VALUE", "PCT", "CPTY", "CPTY_LEI", "NOTIONAL", "EXPIRY",
                      "UNREALIZED", "REFERENCE")})
for prefix in ("BY_ASSET", "BY_ISSUER_TYPE", "BY_COUNTRY", "BY_SECTOR"):
    FUND.update({f"{prefix}.{k}": v for k, v in GROUP.items()})
FUND.update({f"ALSO_HELD_BY.{k}": v for k, v in HOLDER.items()})

FUND_HOLDER = {
    "TICKER": ("Ticker", "The us-equities company's ticker.", "", "us-equities us-company.TICKER"),
    "COMPANY": ("Company", "The company; opens USEQ.", "", "us-equities"),
    "NAME": ("Name", "The company's name.", "", "us-equities us-company.NAME"),
    "SECTOR": ("SIC division", "Its SIC division.", "", "us-equities us-company.SECTOR"),
    "FUNDS": ("Funds", "How many of the pack's funds hold it.", "funds", "computed from every fund's holdings"),
    "ETFS": ("of which ETFs", "How many of them are ETFs.", "funds", "computed"),
    "TOTAL_VALUE": ("Value held", "The value the funds hold together.", "USD", f"computed from {NP} CURRENCY_VALUE"),
    "TOTAL_SHARES": ("Shares held", "The shares the funds hold together.", "shares", f"computed from {NP} BALANCE"),
    "SHARES_OUTSTANDING": ("Shares outstanding", "The company's shares outstanding (10-K cover).", "shares", "us-equities us-company.SHARES_OUTSTANDING (SEC XBRL dei)"),
    "PCT_OF_SHARES": ("Share of its shares", "Shares held over shares outstanding; empty where the filed count cannot be compared.", "%", "computed"),
    "AVG_WEIGHT": ("Average weight", "Its average weight in the funds holding it.", PCT, "computed"),
    "MAX_WEIGHT": ("Largest weight", "Its largest weight in a fund.", PCT, "computed"),
    "TOP_HOLDER": ("Largest holder", "The fund holding the most, by value.", "", "computed"),
    "HOLDERS": ("Holders", "Every fund holding it, by value (at most 250).", "", "computed"),
    "TOP_WEIGHTS": ("Largest weights", "The ten funds where it weighs the most.", "", "computed"),
    "TOP_WEIGHTS.FUND": ("Fund", "The fund's ticker.", "", "computed"),
    "TOP_WEIGHTS.PCT": ("Weight", "The company's weight in it.", PCT, "computed"),
    **{f"HOLDERS.{k}": v for k, v in HOLDER.items()},
    **PROV,
}

INDEX_FUNDS = {
    "INDEX": ("Index", "The us-equities index code (SP500, NDX, DJIA).", "", "us-equities index-membership.INDEX"),
    "NAME": ("Name", "The index's name.", "", "us-equities index-membership.NAME"),
    "FUNDS": ("Funds", "How many of the pack's funds track it.", "funds", "computed"),
    "ETFS": ("of which ETFs", "How many of them are ETFs.", "funds", "computed"),
    "TOTAL_NET_ASSETS": ("Net assets together", "Their net assets added up.", "USD", f"computed from {NP} NET_ASSETS"),
    "LARGEST": ("Largest", "The largest tracking fund.", "", "computed"),
    "ROWS": ("Funds", "The tracking funds by net assets.", "", "computed"),
    "ROWS.FUND": ("Fund", "The fund's ticker; opens FUND.", "", "computed"),
    "ROWS.NAME": ("Name", "Its name.", "", f"{NP} SERIES_NAME"),
    "ROWS.TYPE": ("Type", "Its type.", "", f"{NC} IS_ETF"),
    "ROWS.IS_ETF": ("ETF", "True for an ETF.", "", f"{NC} IS_ETF"),
    "ROWS.NET_ASSETS": ("Net assets", "Its net assets.", "USD", f"{NP} NET_ASSETS"),
    "ROWS.EXPENSE_RATIO": ("Expense ratio", "Its net expense ratio.", "%", f"{RR} NetExpensesOverAssets"),
    "ROWS.MEMBERS_HELD": ("Members held", "Listed members it holds.", "companies", "computed"),
    "ROWS.ACTIVE_SHARE": ("Active share", "Against the computed index weights.", "%", "computed"),
    "ROWS.REPORT_DATE": ("Report date", "Its N-PORT report date.", "", f"{NP} REPORT_DATE"),
    **PROV,
}

TYPES = {"ETF": "An exchange-traded fund", "Mutual fund": "An open-end fund with no exchange-traded class",
         "Mutual fund with an ETF share class": "An open-end fund one of whose classes is exchange-traded (Vanguard's)",
         "ETF (unit investment trust)": "An ETF organised as a unit investment trust (SPY, DIA)"}
UNITS = {"NS": "Number of shares", "PA": "Principal amount", "NC": "Number of contracts", "OU": "Other units"}
PAYOFFS = {"Long": "Long", "Short": "Short", "N/A": "Not applicable"}
DERIVS = {"FWD": "Forward", "FUT": "Future", "SWP": "Swap", "OPT": "Option", "SWO": "Swaption", "WAR": "Warrant", "OTH": "Other"}
VALUES = {
    "fund": {"TYPE": TYPES, "HOLDINGS.UNIT": UNITS, "HOLDINGS.PAYOFF": PAYOFFS, "HOLDINGS.DERIVATIVE": DERIVS,
             "DERIVATIVES.DERIVATIVE": DERIVS, "DERIVATIVES.PAYOFF": PAYOFFS, "DERIVATIVES.TYPE": {v: v for v in DERIVS.values()},
             "ALSO_HELD_BY.TYPE": TYPES, "VIEWS.VIEW": {"LOOK_THROUGH": "Look-through holdings", "VS_INDEX": "The fund against its index"},
             "VS_INDEX.BASIS": {"tracks": "The index the fund tracks", "reference": "The S&P 500 as a reference"},
             "GLEIF_RELATIONSHIPS.TYPE": {"Managed by": "IS_FUND-MANAGED_BY", "Sub-fund of": "IS_SUBFUND_OF", "Feeder to": "IS_FEEDER_TO"}},
    "fund-holder": {"HOLDERS.TYPE": TYPES},
    "index-funds": {"ROWS.TYPE": TYPES},
}

FIELDS = {"fund": FUND, "fund-holder": FUND_HOLDER, "index-funds": INDEX_FUNDS}
TITLES = {"fund": ("Fund or ETF", "${$.NAME} (${$.TICKER}) is a ${$.TYPE} with ${fmt($.NET_ASSETS, 'compact')} USD of net assets and "
                   "${fmt($.HOLDING_COUNT, 'amount0')} holdings on ${$.REPORT_DATE}, as filed on Form N-PORT."),
          "fund-holder": ("Funds holding a company", "${$.FUNDS} of the pack's funds hold ${$.NAME}, ${fmt($.TOTAL_VALUE, 'compact')} USD together."),
          "index-funds": ("Funds tracking an index", "${$.FUNDS} of the pack's funds track the ${$.NAME}, ${fmt($.TOTAL_NET_ASSETS, 'compact')} USD of net assets together.")}


def about_yaml(header: str) -> str:
    """The pack's config/about.yaml: each kind's title, about sentence and glossary (meaning with its SEC source, unit)."""
    import yaml
    kinds = {}
    for kind, fields in FIELDS.items():
        title, about = TITLES[kind]
        gl = {}
        for path, (term, means, unit, source) in fields.items():
            g = {"term": term, "means": f"{means} Source: {source}."}
            if unit:
                g["unit"] = unit
            if path in VALUES.get(kind, {}):
                g["values"] = dict(VALUES[kind][path])
            gl[path] = g
        kinds[kind] = {"title": title, "about": about, "guide": "funds", "glossary": gl}
    body = yaml.safe_dump({"about": 1, "kinds": kinds}, sort_keys=False, width=140, allow_unicode=True)
    return header + "# Generated by tools/packgen/funds/make.py from tools/packgen/funds/fundsglossary.py. Edit the glossary, not this file.\n" + body
