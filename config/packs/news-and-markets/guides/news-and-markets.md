<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# The news-and-markets pack (sample)

One sample pack in two parts: **news** (topics and their headlines) and **markets** (listed companies, quotes and price history).
A country topic or a company links to its country's economy when the `world-economy` pack is on (`economy` field), and a `core` counterparty
links to its own headlines (`newsId`), so `CPTY CP-NORTHBRIDGE` shows a *Headlines* link here.

## News

A sample pack: fifteen topics (central banks, countries, companies and themes), and for each the news about it, **computed when you open the page**
by the [news connector](../../../../docs/connectors/NEWS.md) from Google News. The topic list is static reference data in `samples/news-topic/`
(written by `tools/make_samples.py`); the headlines are live.

!!! warning "Off until you turn it on, and names leave your network when you do"
    The connector is disabled as shipped. Turn it on with `DRISHTI_NEWS_ENABLED=true` (or Admin, Connectors, `news`). From then on the name of every topic
    you open is sent to `news.google.com`. Pages that read the headlines also need `drishti.graph.link-budget` above its 40 ms default
    (for example `2s`), otherwise the first view of a topic shows its headline panels as pending and the next one (within ten minutes) shows them.

### Kinds

| Mnemonic | Opens | Identifiers |
|---|---|---|
| `TOPIC` | a topic: its page shows the headlines | `TOPIC-FED`, `TOPIC-ECB`, `TOPIC-BOE`, `TOPIC-BOJ`, `TOPIC-INFLATION`, `TOPIC-RATES`, `TOPIC-OIL`, `TOPIC-BITCOIN`, `TOPIC-AI`, `TOPIC-CLIMATE`, `TOPIC-INDIA`, `TOPIC-GERMANY`, `TOPIC-BRAZIL`, `TOPIC-DEUTSCHE-BANK`, `TOPIC-TOYOTA` |
| `NEWS` | the headlines for any name, not only the topics | `NEWS-<name with underscores>`, for example `NEWS-Bitcoin` |

### Finding things

| Command | Does |
|---|---|
| `TOPIC TOPIC-FED <GO>` | Opens the Federal Reserve topic. |
| `TOPIC fed <GO>` | A pick list by name or alias: *Fed*, *FOMC* and *Powell* all find the Federal Reserve. |
| `TOPIC category=Country <GO>` | Lists the country topics. |
| `FORM news-and-markets <GO>` | The search form: id, name, other names, category, edition. |
| `NEWS NEWS-Bitcoin <GO>` | The headlines for any name; underscores stand for spaces. |

### What you will see

A topic page has the headlines newest first (published, source, headline, and the article's address as a link that opens in a new tab), a bar per
source, **how the headlines were chosen** (the relevance, duplicates and limit steps and how many each dropped), and **everything the feed returned**
for the name with no relevance filter, so the two can be compared. A topic with an edition (`TOPIC-DEUTSCHE-BANK`: `de-DE`, `TOPIC-TOYOTA`: `ja-JP`)
reads that country's Google News. Where a topic has other names, the relevance filter counts them (the connector template carries them as
`aliases.*`, written from the same list as the samples).

While the connector is off the headline panels are empty and say `waiting for NEWS-...`; the page's own note says how to turn it on.

### Changing it

- **Topics:** edit the list in `tools/make_samples.py` and run `python3 config/packs/news-and-markets/tools/make_samples.py`; it rewrites `samples/` and the
  alias block of the connector template in `pack.yaml`. The connector file `config/connectors/news.yaml` is written once from the template and is yours
  after that; add `aliases.<Name_with_underscores>` lines to it for new topics.
- **Filters:** `relevance`, `allow-sources`, `deny-sources`, `max-items`, `cache-ttl-seconds` and the rest in the connector file; see [NEWS.md](../../../../docs/connectors/NEWS.md#6-settings).
- **Another pack's entity:** give it a field holding `NEWS-<name>` and a panel with `source: "link($.newsId, 'news')"`; the companies here do so.

### Who can see it

Role `news-analyst`: kinds `news-topic` and `news`.

This is a sample pack: it is hidden from business users when `drishti.packs.samples` is `developers`, and it demonstrates a connector that
is code rather than a store. See [Domain packs](../../../../docs/guides/PACKS.md).

## Markets

A sample pack: twenty well-known listed companies, each with a **quote and a price chart** computed on demand by the
[yfinance connector](../../../../docs/connectors/YFINANCE.md) and the **news about the company** computed on demand by the
[news connector](../../../../docs/connectors/NEWS.md). The company list (ticker, name, other names,
exchange, sector, country) is static reference data in `samples/listed-company/`, written by `config/packs/news-and-markets/tools/make_samples.py`.

!!! warning "Internal demonstrations only; off until you turn it on"
    Yahoo's chart endpoint is **unofficial** and its terms restrict its use: do not use this pack in production or redistribute what it shows. Both connectors are
    disabled as shipped. `DRISHTI_YFINANCE_ENABLED=true` sends each ticker you open to Yahoo; `DRISHTI_NEWS_ENABLED=true` sends each company name to Google.
    Pages that read them also need `drishti.graph.link-budget` above its 40 ms default (for example `2s`), otherwise the first view of a company shows its quote
    and headline panels as pending and the next one shows them.

### Kinds

| Mnemonic | Opens | Identifiers |
|---|---|---|
| `CO` | a listed company | `CO-AAPL`, `CO-MSFT`, `CO-GOOGL`, `CO-AMZN`, `CO-NVDA`, `CO-TSLA`, `CO-META`, `CO-NFLX`, `CO-JPM`, `CO-BAC`, `CO-GS`, `CO-XOM`, `CO-CVX`, `CO-SHEL`, `CO-MRK`, `CO-PFE`, `CO-WMT`, `CO-KO`, `CO-DIS`, `CO-BA` |
| `QUOTE` | the quote and price history of any ticker | `QUOTE-<symbol>`, for example `QUOTE-AAPL`, `QUOTE-^GSPC` |
| `NEWS`, `TOPIC` | the news part | see [News](#news) above |

### Finding things

| Command | Does |
|---|---|
| `CO CO-AAPL <GO>` | Opens Apple: quote, chart, headlines. |
| `CO google <GO>` | A pick list by name or alias: *Google* finds Alphabet. |
| `CO sector=Energy <GO>` | Lists the energy companies. |
| `FORM news-and-markets <GO>` | The search form: id, ticker, name, other names, exchange, sector, country. |
| `QUOTE QUOTE-MSFT <GO>` | The quote and the table of bars for any ticker. |
| `MKTW <GO>` | The pack's overview: its kinds, their mnemonics and how many documents each holds. |

### What you will see

A company page has a strip (ticker, exchange, sector, country, other names, a link to the quote), the **Quote** (price, currency, change in money and percent,
whether the market is open, the day and 52-week ranges, volume, market cap when Yahoo gives it, as-of time), a **closing-price line** for the last six months,
the **headlines** newest first with links that open the article in a new tab, and a bar per source. `F7` opens the quote, `F8` the full headline view with the
steps that chose them. The quote view adds the daily bars as a table.

With a connector off, its panels are empty and say `waiting for QUOTE-AAPL` or `waiting for NEWS-Apple`; the page's own note says how to turn them on. With
Yahoo or Google unreachable the panels read `source failed`-style errors, and Admin, Connectors shows the connector `DOWN` with the reason; an unknown
ticker is simply not found.

### Changing it

- **Companies:** edit the list in `config/packs/news-and-markets/tools/make_samples.py` and run it (it also rewrites the aliases of the news connector template).
- **History:** `range` and `interval` in `config/connectors/yfinance.yaml` (for example `1y` and `1wk`); caching and retries in the same file; see [YFINANCE.md](../../../../docs/connectors/YFINANCE.md#6-settings).
- **Percent format:** the pack defines `pc2` (a value already in percent, with a sign and ` %`).

### Who can see it

Role `market-analyst`: kinds `listed-company`, `quote`, `news-topic` and `news` .

This is a sample pack of the kind `drishti.packs.samples` can hide from business users. See [Domain packs](../../../../docs/guides/PACKS.md).
