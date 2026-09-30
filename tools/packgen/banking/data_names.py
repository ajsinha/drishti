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

"""The fictitious universe the banking samples are drawn from: counterparties, issuers, equities, commodities and
the public benchmarks (currencies, indices, rate indices) they trade on. Firms are invented; benchmarks are real."""
from __future__ import annotations

from datetime import date

AS_OF = date(2026, 9, 30)
TRADES_PER_PRODUCT = 6

CCY = {  # currency: (OIS zero at 1Y %, 10Y %, calendar, RFR, govt curve name)
    "USD": (3.70, 3.80, "USNY", "SOFR", "UST"), "EUR": (1.95, 2.51, "EUTA", "ESTR", "Bund"), "GBP": (3.85, 4.05, "GBLO", "SONIA", "Gilt"),
    "JPY": (0.55, 1.35, "JPTO", "TONA", "JGB"), "CHF": (0.30, 0.85, "EUTA", "SARON", "Swiss Conf."),
    "AUD": (3.60, 4.20, "GBLO", "AONIA", "ACGB"), "CAD": (2.75, 3.20, "USNY", "CORRA", "GoC")}
FX = {"EURUSD": 1.1740, "GBPUSD": 1.3480, "USDJPY": 148.20, "USDCHF": 0.7960, "AUDUSD": 0.6610, "USDCAD": 1.3890,
      "EURGBP": 0.8710, "EURJPY": 173.99, "USDCNH": 7.1280, "USDINR": 88.70, "USDBRL": 5.3200, "USDKRW": 1398.0}
FX_VOL = {"EURUSD": 7.2, "GBPUSD": 7.9, "USDJPY": 9.8, "USDCHF": 7.6, "AUDUSD": 9.9, "USDCAD": 5.9, "EURGBP": 5.4,
          "EURJPY": 9.1, "USDCNH": 4.3, "USDINR": 4.6, "USDBRL": 14.2, "USDKRW": 8.8}

# counterparty short code: (name, type, sector, country, rating, parent group)
COUNTERPARTIES = {
    "NORTHBRIDGE": ("Northbridge Capital LLP", "Hedge fund", "Asset management", "GB", "BB+", "NORTHBRIDGE"),
    "HARBORPT": ("Harbor Point Bank", "Bank", "Banking", "US", "A", "HARBORPT"),
    "ALDERSHOT": ("Aldershot Pension Trust", "Pension fund", "Pensions", "GB", "AA-", "ALDERSHOT"),
    "KESTREL": ("Kestrel Global Macro Fund", "Hedge fund", "Asset management", "KY", "BBB-", "KESTREL"),
    "MERIDIAN": ("Meridian Life Assurance", "Insurer", "Insurance", "US", "A+", "MERIDIAN"),
    "SOLARIS": ("Solaris Energy Corp.", "Corporate", "Utilities", "US", "BBB", "SOLARIS"),
    "RHEINTAL": ("Rheintal Landesbank", "Bank", "Banking", "DE", "A-", "RHEINTAL"),
    "BRAMBLE": ("Bramble Asset Management", "Asset manager", "Asset management", "GB", "A-", "BRAMBLE"),
    "TOKAI": ("Tokai Mutual Trust Bank", "Bank", "Banking", "JP", "A", "TOKAI"),
    "CASCADIA": ("Cascadia Airlines Inc.", "Corporate", "Airlines", "CA", "BB", "CASCADIA"),
    "VERDANT": ("Verdant Agri Holdings", "Corporate", "Agriculture", "BR", "BB-", "VERDANT"),
    "AURORA": ("Aurora Sovereign Wealth Fund", "Sovereign fund", "Government", "NO", "AAA", "AURORA"),
    "PINNACLE": ("Pinnacle Credit Partners", "Hedge fund", "Asset management", "US", "BBB", "PINNACLE"),
    "HALCYON": ("Halcyon Shipping plc", "Corporate", "Shipping", "GR", "B+", "HALCYON"),
    "LUMEN": ("Lumen Semiconductor AG", "Corporate", "Technology", "CH", "A", "LUMEN"),
    "MERIDIAN-RE": ("Meridian Reinsurance Ltd", "Insurer", "Insurance", "BM", "A", "MERIDIAN"),
    "SUMMIT": ("Summit Clearing LLC", "Clearing broker", "Financial services", "US", "A-", "SUMMIT"),
    "CORAL": ("Coral Bay Municipal Authority", "Municipality", "Government", "US", "AA", "CORAL"),
}
GROUPS = {"NORTHBRIDGE": "Northbridge Capital Holdings", "HARBORPT": "Harbor Point Financial Corp.", "ALDERSHOT": "Aldershot Pension Trust",
          "KESTREL": "Kestrel Capital Group", "MERIDIAN": "Meridian Financial Group", "SOLARIS": "Solaris Energy Corp.",
          "RHEINTAL": "Rheintal Sparkassen-Verband", "BRAMBLE": "Bramble Holdings plc", "TOKAI": "Tokai Financial Group",
          "CASCADIA": "Cascadia Airlines Inc.", "VERDANT": "Verdant Agri Holdings", "AURORA": "Aurora Sovereign Wealth Fund",
          "PINNACLE": "Pinnacle Partners LP", "HALCYON": "Halcyon Maritime Group", "LUMEN": "Lumen Holding AG",
          "SUMMIT": "Summit Holdings Inc.", "CORAL": "Coral Bay Municipal Authority"}

# issuer short: (name, sector, country, rating, 5Y spread bp, is sovereign)
ISSUERS = {
    "UST": ("United States Treasury", "Sovereign", "US", "AA+", 12, True), "BUND": ("Federal Republic of Germany", "Sovereign", "DE", "AAA", 9, True),
    "GILT": ("United Kingdom Debt Management Office", "Sovereign", "GB", "AA", 18, True), "JGB": ("Japan Ministry of Finance", "Sovereign", "JP", "A+", 21, True),
    "SOLARIS": ("Solaris Energy Corp.", "Utilities", "US", "BBB", 118, False), "LUMEN": ("Lumen Semiconductor AG", "Technology", "CH", "A", 64, False),
    "CASCADIA": ("Cascadia Airlines Inc.", "Airlines", "CA", "BB", 295, False), "HALCYON": ("Halcyon Shipping plc", "Shipping", "GR", "B+", 455, False),
    "VERDANT": ("Verdant Agri Holdings", "Agriculture", "BR", "BB-", 340, False), "NOVATEK": ("Novatek Devices Inc.", "Technology", "US", "A+", 52, False),
    "ORBITAL": ("Orbital Telecom SA", "Telecoms", "FR", "BBB-", 165, False), "GRANITE": ("Granite Building Materials plc", "Materials", "GB", "BBB", 132, False),
    "HELIX": ("Helix Pharmaceuticals Inc.", "Healthcare", "US", "A-", 78, False), "KAIZEN": ("Kaizen Motors Corp.", "Autos", "JP", "A", 70, False),
    "RHEINTAL": ("Rheintal Landesbank", "Banking", "DE", "A-", 88, False), "MERIDIAN": ("Meridian Financial Group", "Insurance", "US", "A", 72, False),
}
# equity ticker: (name, issuer short, exchange, currency, price, sector, beta, index)
EQUITIES = {
    "NVTK": ("Novatek Devices Inc.", "NOVATEK", "NASDAQ", "USD", 214.30, "Technology", 1.28, "NDX"),
    "HLXP": ("Helix Pharmaceuticals Inc.", "HELIX", "NYSE", "USD", 96.45, "Healthcare", 0.74, "SPX"),
    "SLRS": ("Solaris Energy Corp.", "SOLARIS", "NYSE", "USD", 58.12, "Utilities", 0.52, "SPX"),
    "CSCA": ("Cascadia Airlines Inc.", "CASCADIA", "TSX", "CAD", 21.84, "Airlines", 1.46, None),
    "LUMN": ("Lumen Semiconductor AG", "LUMEN", "SIX", "CHF", 312.50, "Technology", 1.19, None),
    "ORBT": ("Orbital Telecom SA", "ORBITAL", "Euronext Paris", "EUR", 17.36, "Telecoms", 0.81, "SX5E"),
    "GRNT": ("Granite Building Materials plc", "GRANITE", "LSE", "GBP", 7.42, "Materials", 1.05, "UKX"),
    "KZNM": ("Kaizen Motors Corp.", "KAIZEN", "TSE", "JPY", 2841.0, "Autos", 1.02, "NKY"),
    "RHNB": ("Rheintal Landesbank", "RHEINTAL", "Xetra", "EUR", 24.90, "Banking", 1.33, "SX5E"),
    "MRDN": ("Meridian Financial Group", "MERIDIAN", "NYSE", "USD", 71.05, "Insurance", 0.96, "SPX"),
}
INDICES = {"SPX": ("S&P 500", "USD", 6640.0, 503), "NDX": ("Nasdaq-100", "USD", 24530.0, 101), "SX5E": ("EURO STOXX 50", "EUR", 5460.0, 50),
           "UKX": ("FTSE 100", "GBP", 9320.0, 100), "NKY": ("Nikkei 225", "JPY", 44930.0, 225)}
INFLATION = {"USCPI": ("US CPI-U NSA", "USD", 2.9, "3 months"), "HICPX": ("Euro HICP ex-tobacco", "EUR", 2.1, "3 months"),
             "UKRPI": ("UK RPI", "GBP", 3.9, "2 months"), "JPCPI": ("Japan CPI ex-fresh food", "JPY", 2.4, "3 months")}
# commodity: (name, unit, exchange, sector, front price, vol %)
COMMODITIES = {"WTI": ("WTI crude oil", "USD/bbl", "NYMEX", "Energy", 71.15, 31.0), "BRENT": ("Brent crude oil", "USD/bbl", "ICE", "Energy", 74.60, 29.0),
               "NG": ("Henry Hub natural gas", "USD/MMBtu", "NYMEX", "Energy", 3.12, 55.0), "GOLD": ("Gold", "USD/oz", "COMEX", "Metals", 3815.0, 16.0),
               "SILVER": ("Silver", "USD/oz", "COMEX", "Metals", 46.20, 27.0), "COPPER": ("Copper", "USD/lb", "COMEX", "Metals", 4.86, 22.0),
               "CORN": ("Corn", "USc/bu", "CBOT", "Agriculture", 418.5, 24.0), "SOY": ("Soybeans", "USc/bu", "CBOT", "Agriculture", 1012.0, 19.0)}
CORRELATIONS = {"CORR-G10FX": ("G10 FX", ["EURUSD", "GBPUSD", "USDJPY", "AUDUSD"]), "CORR-US-EQ": ("US equities", ["NVTK", "HLXP", "SLRS", "MRDN"]),
                "CORR-EU-EQ": ("European equities", ["ORBT", "RHNB", "GRNT", "LUMN"])}

LEGAL_ENTITIES = {"LE-NY": ("Drishti Bank Securities Inc.", "US", "SEC / CFTC / FINRA"), "LE-LDN": ("Drishti Bank plc", "GB", "PRA / FCA"),
                  "LE-FRA": ("Drishti Bank Europe AG", "DE", "ECB / BaFin"), "LE-TKY": ("Drishti Securities Japan K.K.", "JP", "JFSA")}
# desk: (name, legal entity, asset classes, head)
DESKS = {"DESK-RATES": ("Rates · Swaps and options", "LE-NY", ["Rates"], "A. Shah"),
         "DESK-FX": ("G10 and EM FX", "LE-LDN", ["FX"], "L. Moreau"),
         "DESK-CREDIT": ("Credit trading", "LE-NY", ["Credit"], "D. Whitfield"),
         "DESK-EQD": ("Equity derivatives", "LE-LDN", ["Equity"], "P. Lindqvist"),
         "DESK-COMM": ("Commodities", "LE-NY", ["Commodity"], "T. Brennan"),
         "DESK-INFL": ("Inflation", "LE-LDN", ["Inflation"], "H. Okoro"),
         "DESK-FI": ("Bonds and securitised", "LE-NY", ["Fixed income"], "R. Castillo"),
         "DESK-SFT": ("Repo and securities lending", "LE-FRA", ["Securities financing"], "K. Brandt"),
         "DESK-MM": ("Money markets", "LE-NY", ["Money market"], "S. Iyer"),
         "DESK-STRUCT": ("Structured products", "LE-TKY", ["Structured"], "Y. Tanaka")}
TRADERS = ["A. Shah", "M. Okafor", "E. Novak", "L. Moreau", "C. Dubois", "D. Whitfield", "J. Weiss", "P. Lindqvist", "F. Rossi",
           "T. Brennan", "G. Mendes", "H. Okoro", "B. Adeyemi", "R. Castillo", "N. Farouk", "K. Brandt", "I. Schulz", "S. Iyer",
           "V. Rao", "Y. Tanaka", "M. Sato"]
CCPS = {"CCP-LCH": ("LCH Ltd", "LCH SwapClear PAIRS (5-day, 99.7% ES)", 5.1e8), "CCP-CME": ("CME Clearing", "SPAN 2", 7.2e8),
        "CCP-EUREX": ("Eurex Clearing AG", "Prisma", 3.4e8), "CCP-ICE": ("ICE Clear Europe", "IRM 2", 2.6e8),
        "CCP-JSCC": ("Japan Securities Clearing Corp.", "JSCC IRS VaR", 1.2e8)}
CALENDARS = {"CAL-USNY": "New York", "CAL-GBLO": "London", "CAL-EUTA": "TARGET2", "CAL-JPTO": "Tokyo"}
