#!/usr/bin/env python3
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

"""Writes the reference data of the two sample packs built on the on-demand connectors, from the lists below:

  samples/news-topic.jsonl              the topics (companies, countries, central banks, themes), one a line
  samples/listed-company.jsonl          about twenty well-known listed companies
  samples/catalog.jsonl                 their ids, titles and subtitles, one a line

and the aliases block of the news connector template in pack.yaml (between the BEGIN and END markers), so the connector
knows every other name of every entity it is asked about. Run: python3 config/packs/news-and-markets/tools/make_samples.py
The data is static reference data written by hand: no market data, no news. Attribute names are UPPER_SNAKE (TOPIC_ID, NEWS_ID)."""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE.parents[2] / "tools" / "packgen" / "common"))
import packsamples  # noqa: E402  (samples/<kind>.jsonl, one document a line)
# ISO 3166 alpha-2 -> alpha-3: a country topic or a company links to its economy (the world-economy pack's ECON-<alpha-3>)
ISO3 = {"US": "USA", "GB": "GBR", "DE": "DEU", "JP": "JPN", "IN": "IND", "BR": "BRA"}

# (ticker, display name, query name, aliases, exchange, sector, country)
COMPANIES = [
    ("AAPL", "Apple Inc.", "Apple", ["Apple Inc", "AAPL", "iPhone"], "Nasdaq", "Technology", "US"),
    ("MSFT", "Microsoft Corporation", "Microsoft", ["MSFT"], "Nasdaq", "Technology", "US"),
    ("GOOGL", "Alphabet Inc.", "Alphabet", ["Google", "GOOGL"], "Nasdaq", "Communication Services", "US"),
    ("AMZN", "Amazon.com, Inc.", "Amazon", ["AMZN", "AWS"], "Nasdaq", "Consumer Discretionary", "US"),
    ("NVDA", "NVIDIA Corporation", "NVIDIA", ["Nvidia", "NVDA"], "Nasdaq", "Technology", "US"),
    ("TSLA", "Tesla, Inc.", "Tesla", ["TSLA", "Elon Musk"], "Nasdaq", "Consumer Discretionary", "US"),
    ("META", "Meta Platforms, Inc.", "Meta Platforms", ["Meta", "Facebook", "Instagram"], "Nasdaq", "Communication Services", "US"),
    ("NFLX", "Netflix, Inc.", "Netflix", ["NFLX"], "Nasdaq", "Communication Services", "US"),
    ("JPM", "JPMorgan Chase & Co.", "JPMorgan Chase", ["JPMorgan", "JP Morgan", "Jamie Dimon"], "NYSE", "Financials", "US"),
    ("BAC", "Bank of America Corporation", "Bank of America", ["BofA", "BAC"], "NYSE", "Financials", "US"),
    ("GS", "The Goldman Sachs Group, Inc.", "Goldman Sachs", ["Goldman"], "NYSE", "Financials", "US"),
    ("XOM", "Exxon Mobil Corporation", "Exxon Mobil", ["Exxon", "ExxonMobil"], "NYSE", "Energy", "US"),
    ("CVX", "Chevron Corporation", "Chevron", ["CVX"], "NYSE", "Energy", "US"),
    ("SHEL", "Shell plc", "Shell", ["Shell plc"], "NYSE", "Energy", "GB"),
    ("MRK", "Merck & Co., Inc.", "Merck", ["Merck & Co", "MRK"], "NYSE", "Health Care", "US"),
    ("PFE", "Pfizer Inc.", "Pfizer", ["PFE"], "NYSE", "Health Care", "US"),
    ("WMT", "Walmart Inc.", "Walmart", ["WMT"], "NYSE", "Consumer Staples", "US"),
    ("KO", "The Coca-Cola Company", "Coca-Cola", ["Coke", "Coca Cola"], "NYSE", "Consumer Staples", "US"),
    ("DIS", "The Walt Disney Company", "Disney", ["Walt Disney", "DIS"], "NYSE", "Communication Services", "US"),
    ("BA", "The Boeing Company", "Boeing", ["BA"], "NYSE", "Industrials", "US"),
]

# (slug, name, category, aliases, language, country)
TOPICS = [
    ("FED", "Federal Reserve", "Central bank", ["Fed", "FOMC", "Powell"], None, None),
    ("ECB", "European Central Bank", "Central bank", ["ECB", "Lagarde"], None, None),
    ("BOE", "Bank of England", "Central bank", ["BoE"], None, None),
    ("BOJ", "Bank of Japan", "Central bank", ["BoJ"], None, None),
    ("INFLATION", "inflation", "Theme", ["CPI", "consumer prices"], None, None),
    ("RATES", "interest rates", "Theme", ["rate cut", "rate hike", "rates"], None, None),
    ("OIL", "oil prices", "Theme", ["crude", "Brent", "oil"], None, None),
    ("BITCOIN", "bitcoin", "Theme", ["crypto", "cryptocurrency"], None, None),
    ("AI", "artificial intelligence", "Theme", ["AI", "machine learning"], None, None),
    ("CLIMATE", "climate change", "Theme", ["global warming", "emissions", "climate"], None, None),
    ("INDIA", "India", "Country", ["Indian", "New Delhi"], None, "IN"),
    ("GERMANY", "Germany", "Country", ["German", "Berlin"], None, "DE"),
    ("BRAZIL", "Brazil", "Country", ["Brazilian", "Brasilia"], None, "BR"),
    ("DEUTSCHE-BANK", "Deutsche Bank", "Company", ["Deutsche"], "de", "DE"),
    ("TOYOTA", "Toyota", "Company", ["トヨタ", "Toyota Motor"], "ja", "JP"),
]


def news_name(name):
    return "NEWS-" + name.replace(" ", "_")


DOCS: dict[str, dict[str, dict]] = {}


def write(directory, id_, doc, catalog, title, subtitle, kind):
    doc["_meta"] = {"source": "reference", "generation": 1, "live": False}
    DOCS.setdefault(kind, {})[id_] = doc
    catalog.append({"kind": kind, "id": id_, "title": title, "subtitle": subtitle})


def main():
    catalog = []
    for slug, name, category, aliases, lang, country in TOPICS:
        locale = f"@{lang}-{country}" if lang else ""
        nid = news_name(name) + locale
        write(HERE / "samples" / "news-topic", f"TOPIC-{slug}",
              {"TOPIC_ID": f"TOPIC-{slug}", "NAME": name, "CATEGORY": category, "ALIASES": aliases, "ALIAS_TEXT": ", ".join(aliases),
               "EDITION": f"{lang}-{country}" if lang else "default", "NEWS_ID": nid, "NEWS_ALL_ID": nid + "~all",
               **({"ECONOMY": f"ECON-{ISO3[country]}"} if country else {})},
              catalog, f"TOPIC-{slug}", f"{category} · {name} · {', '.join(aliases)}", "news-topic")
    for ticker, name, query, aliases, exchange, sector, country in COMPANIES:
        write(HERE / "samples" / "listed-company", f"CO-{ticker}",
              {"COMPANY_ID": f"CO-{ticker}", "TICKER": ticker, "NAME": name, "ALIASES": aliases, "ALIAS_TEXT": ", ".join(aliases), "EXCHANGE": exchange,
               "SECTOR": sector, "COUNTRY": country, "ECONOMY": f"ECON-{ISO3[country]}", "QUOTE_ID": f"QUOTE-{ticker}", "NEWS_ID": news_name(query),
               "NEWS_ALL_ID": news_name(query) + "~all"},
              catalog, f"CO-{ticker}", f"{sector} · {name} ({exchange}: {ticker}) · {', '.join(aliases)}", "listed-company")
    out = HERE / "samples"
    for old in sorted(out.rglob("*.json"), reverse=True):     # the older one-file-a-document layout, and catalog.json (now .jsonl)
        old.unlink()
    for d in (x for x in out.iterdir() if x.is_dir()):
        if not any(d.iterdir()):
            d.rmdir()
    for name, text in packsamples.files(DOCS, catalog, packsamples.part_mb(sys.argv)).items():
        (out / name).write_text(text, encoding="utf-8")

    lines = []
    for _, _, query, aliases, *_ in COMPANIES:
        lines.append(f"      aliases.{query.replace(' ', '_')}: {', '.join(aliases)}")
    for _, name, _, aliases, *_ in TOPICS:
        lines.append(f"      aliases.{name.replace(' ', '_')}: {', '.join(aliases)}")
    pack = HERE / "pack.yaml"
    text = pack.read_text(encoding="utf-8")
    block = "      # BEGIN aliases (written by tools/make_samples.py)\n" + "\n".join(lines) + "\n      # END aliases"
    text = re.sub(r"      # BEGIN aliases.*?# END aliases", lambda _: block, text, flags=re.S)
    pack.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
