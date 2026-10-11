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
# The market-watch pack (sample)

A sample pack: twenty well-known listed companies, each with a **quote and a price chart** computed on demand by the
[yfinance connector](../../../../docs/connectors/YFINANCE.md) and the **news about the company** computed on demand by the
[news connector](../../../../docs/connectors/NEWS.md). It extends the `news-watch` pack for the `news` kind. The company list (ticker, name, other names,
exchange, sector, country) is static reference data in `samples/listed-company/`, written by `config/packs/news-watch/tools/make_samples.py`.

!!! warning "Internal demonstrations only; off until you turn it on"
    Yahoo's chart endpoint is **unofficial** and its terms restrict its use: do not use this pack in production or redistribute what it shows. Both connectors are
    disabled as shipped. `DRISHTI_YFINANCE_ENABLED=true` sends each ticker you open to Yahoo; `DRISHTI_NEWS_ENABLED=true` sends each company name to Google.
    Pages that read them also need `drishti.graph.link-budget` above its 40 ms default (for example `2s`), otherwise the first view of a company shows its quote
    and headline panels as pending and the next one shows them.

## Kinds

| Mnemonic | Opens | Identifiers |
|---|---|---|
| `CO` | a listed company | `CO-AAPL`, `CO-MSFT`, `CO-GOOGL`, `CO-AMZN`, `CO-NVDA`, `CO-TSLA`, `CO-META`, `CO-NFLX`, `CO-JPM`, `CO-BAC`, `CO-GS`, `CO-XOM`, `CO-CVX`, `CO-SHEL`, `CO-MRK`, `CO-PFE`, `CO-WMT`, `CO-KO`, `CO-DIS`, `CO-BA` |
| `QUOTE` | the quote and price history of any ticker | `QUOTE-<symbol>`, for example `QUOTE-AAPL`, `QUOTE-^GSPC` |
| `NEWS`, `TOPIC` | from `news-watch` | see [that pack's guide](../../news-watch/guides/news-watch.md) |

## Finding things

| Command | Does |
|---|---|
| `CO CO-AAPL <GO>` | Opens Apple: quote, chart, headlines. |
| `CO google <GO>` | A pick list by name or alias: *Google* finds Alphabet. |
| `CO sector=Energy <GO>` | Lists the energy companies. |
| `FORM market-watch <GO>` | The search form: id, ticker, name, other names, exchange, sector, country. |
| `QUOTE QUOTE-MSFT <GO>` | The quote and the table of bars for any ticker. |
| `MKTW <GO>` | The pack's overview: its kinds, their mnemonics and how many documents each holds. |

## What you will see

A company page has a strip (ticker, exchange, sector, country, other names, a link to the quote), the **Quote** (price, currency, change in money and percent,
whether the market is open, the day and 52-week ranges, volume, market cap when Yahoo gives it, as-of time), a **closing-price line** for the last six months,
the **headlines** newest first with links that open the article in a new tab, and a bar per source. `F7` opens the quote, `F8` the full headline view with the
steps that chose them. The quote view adds the daily bars as a table.

With a connector off, its panels are empty and say `waiting for QUOTE-AAPL` or `waiting for NEWS-Apple`; the page's own note says how to turn them on. With
Yahoo or Google unreachable the panels read `source failed`-style errors, and Admin, Connectors shows the connector `DOWN` with the reason; an unknown
ticker is simply not found.

## Changing it

- **Companies:** edit the list in `config/packs/news-watch/tools/make_samples.py` and run it (it also rewrites the aliases of the news connector template).
- **History:** `range` and `interval` in `config/connectors/yfinance.yaml` (for example `1y` and `1wk`); caching and retries in the same file; see [YFINANCE.md](../../../../docs/connectors/YFINANCE.md#6-settings).
- **Percent format:** the pack defines `pc2` (a value already in percent, with a sign and ` %`).

## Who can see it

Role `market-analyst`: kinds `listed-company`, `quote`, `news-topic` and `news` (the last two come from `news-watch`).

This is a sample pack of the kind `drishti.packs.samples` can hide from business users. See [Domain packs](../../../../docs/guides/PACKS.md).
