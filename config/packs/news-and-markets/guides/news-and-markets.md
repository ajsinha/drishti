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
by the [news connector](../../../../docs/connectors/NEWS.md) from Google News. The topic list is static reference data in `samples/news-topic.jsonl`
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
is code rather than a store. See [Domain packs](../../../../docs/guides/PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which).

## Markets

A sample pack: twenty well-known listed companies, each with a **quote and a price chart** computed on demand by the
[yfinance connector](../../../../docs/connectors/YFINANCE.md) and the **news about the company** computed on demand by the
[news connector](../../../../docs/connectors/NEWS.md). The company list (ticker, name, other names,
exchange, sector, country) is static reference data in `samples/listed-company.jsonl`, written by `config/packs/news-and-markets/tools/make_samples.py`.

!!! warning "Internal demonstrations only; off until you turn it on"
    Yahoo's chart endpoint is **unofficial** and its terms restrict its use: do not use this pack in production or redistribute what it shows. Both connectors are
    disabled as shipped. `DRISHTI_YFINANCE_ENABLED=true` sends each ticker you open to Yahoo; `DRISHTI_NEWS_ENABLED=true` sends each company name to Google.
    Pages that read them also need `drishti.graph.link-budget` above its 40 ms default (for example `2s`), otherwise the first view of a company shows its quote
    and headline panels as pending and the next one shows them.

### Offline: the committed snapshot

With no internet (a demonstration laptop) the pack still works: the repository keeps a real snapshot of every company's quote (a year of
daily bars) and of the companies' and topics' headlines, captured through these same connectors (`drishti.py testdata capture-market`,
[data/testdata/README.md](../../../../data/testdata/README.md#the-market-snapshot-news-and-markets-offline)). `drishti.py lake demo`
builds it into `news/quote` and `news/news`, which `news-store` serves whenever the live connectors are off, and always in **offline
mode** (`DRISHTI_OFFLINE=true`, set by the run configuration **Drishti server (demo lakes)**): the `news` and `yfinance` connectors then
never start, and Admin → Connectors shows them *off (offline mode)*. With offline mode off and the live connectors switched off (as
shipped: `DRISHTI_NEWS_ENABLED` and `DRISHTI_YFINANCE_ENABLED` unset) it is the same: their kinds are read from `news-store`, and Admin →
Connectors shows them *off (served from the snapshot)*. A lake built before the snapshot was committed has no `news/news` or `news/quote`
table: the topic and company pages then have no headlines and a headline page says *news-store has no table for it yet*; build it again
with `drishti.py lake demo --only news-and-markets`. The pages are the same; the quote's *As of* and the headlines'
*Fetched* end in *(snapshot captured <date>)*, and the quote and headline pages show a **Snapshot** note. The data belongs to Yahoo
Finance, Google News and the publishers, and is shown for demonstration only.

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
| `NWMK <GO>` | The pack's overview: its kinds, their mnemonics and how many documents each holds. |

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

This is a sample pack of the kind `drishti.packs.samples` can hide from business users. See [Domain packs](../../../../docs/guides/PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which).

<!-- data-sections: generated by tools/packgen/common/guide_data.py from the contracts and data_retrieval.yaml; edit those, not this block -->
## Data contracts

Every kind of the pack has a data contract: a YAML file beside the pack (`config/packs/news-and-markets/contracts/<kind>.contract.yaml`, checked against `tools/contracts.schema.json`) that says what one record is, its key, how it is stored and dated, and every column. This section is generated from those files; the same files give the contract pages ([docs/contracts/packs/news-and-markets.md](../../../../docs/contracts/packs/news-and-markets.md), with each kind's validation rules and the points still to review). Check them against the pack and its data with `python3 tools/contracts.py check` (or `drishti.py contract check`); show one with `python3 tools/contracts.py show <MNEMONIC>`.

| Command | Kind | Class | Key | Grain | Columns |
|---|---|---|---|---|---|
| `TOPIC` | `news-topic` | fundamental | `TOPIC_ID` | One row per news topic, keyed by TOPIC_ID. | 9 |
| `NEWS` | `news` | fundamental | `NEWS_ID` | One row per headlines, keyed by NEWS_ID. | 29 |
| `CO` | `listed-company` | fundamental | `COMPANY_ID` | One row per listed company, keyed by COMPANY_ID. | 12 |
| `QUOTE` | `quote` | fundamental | `QUOTE_ID` | One row per quote and price history, keyed by QUOTE_ID. | 37 |

*Class*: **fundamental** data is delivered by a source system and stored; **input** is stored data an engine reads; **computed** is worked out by Drishti when asked and never stored by a source; **drishti-owned** is kept by Drishti itself.

### TOPIC: News topic (`news-topic`)

A topic and the news about it: headlines (newest first) with their publishers and links, a count by source, and how the headlines were chosen.

**Class** fundamental · **Key** `TOPIC_ID` · **Dated by** `snapshot` · **Cadence** static · **Stored by** connector `news-store` (plugin `delta`), table `<root>/news/news-topic/` · **Ids** `^TOPIC-`.

**Grain:** One row per news topic, keyed by TOPIC_ID. **Contract:** `contracts/news-topic.contract.yaml` ([full contract page](../../../../docs/contracts/packs/news-and-markets.md#topic-news-topic-news-topic)).

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `TOPIC_ID` | id | key | required |  | `TOPIC-FED` (pattern `^TOPIC-`) |  | The record's id (the business key) |
| `NAME` | text |  | required |  | `Federal Reserve` |  | The topic's name as searched (Federal Reserve, Bank of Japan). |
| `CATEGORY` | text |  | required |  | `Central bank`, `Company`, `Country`, `Theme` |  | What the topic is: Central bank, Theme, Country or Company. |
| `ALIASES` | text list |  | required |  |  |  | Other names of the topic the news connector accepts in a headline (its relevance step). |
| `ALIAS_TEXT` | text |  | required |  | `Fed, FOMC, Powell` |  | Other names the headlines may use for the topic, comma-separated ("Fed, FOMC, Powell"). |
| `EDITION` | text |  | required |  | `default` |  | The Google News edition searched (en-US, de-DE, ja-JP), or default for the connector's own. |
| `NEWS_ID` | id |  | required |  | `NEWS-Federal_Reserve` (pattern `^NEWS-`) | `news` | The topic's headlines with the relevance filter on: a NEWS_ID of the news kind (NEWS-<name>). |
| `NEWS_ALL_ID` | id |  | required |  | `NEWS-Federal_Reserve~all` (pattern `^NEWS-`) | `news` | The topic's headlines without the relevance filter: a NEWS_ID of the news kind (NEWS-<name>~all). |
| `ECONOMY` | id |  | optional |  | `ECON-IND` (pattern `^ECON-`) | `economy` | The economy the topic belongs to: an id of the world-economy pack's economy kind (ECON-<ISO 3166-1 alpha-3>). |

**Links:** `NEWS_ID` → `news`, `NEWS_ALL_ID` → `news`, `ECONOMY` → `economy`.

### NEWS: Headlines (`news`)

The headlines for one name, as the news connector computed them: newest first, filtered for relevance, duplicates removed.

**Class** fundamental · **Key** `NEWS_ID` · **Dated by** `snapshot` · **Cadence** intraday · **Stored by** connector `news` (plugin `news`) · **Ids** `^NEWS-`.

**Grain:** One row per headlines, keyed by NEWS_ID. **Contract:** `contracts/news.contract.yaml` ([full contract page](../../../../docs/contracts/packs/news-and-markets.md#news-headlines-news)).

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `NEWS_ID` | id | key | required |  | `NEWS-Alphabet` (pattern `^NEWS-`) | `news` | The record's id (the business key) |
| `ENTITY` | text |  | required |  | `Alphabet` |  | The company or topic the headlines are about, as searched (Alphabet, Federal Reserve). |
| `ALIASES` | text list |  | required |  |  |  | Other names of the entity; a headline must name the entity or one of these to pass the relevance step. |
| `EDITION` | text |  | required |  | `de-DE`, `en-US`, `ja-JP` |  | The Google News edition the headlines come from: language and country (en-US, de-DE, ja-JP). |
| `RELEVANCE_FILTER` | text |  | required |  | `off`, `on` |  | on when headlines not about ENTITY were dropped (the pipeline's relevance step), off for the ~all record. |
| `COUNT` | integer |  | required |  | `15` |  | How many headlines ITEMS holds: at most the news connector's max-items setting (15 in the snapshot). |
| `FETCHED_AT` | text |  | required |  | `2026-10-18 03:25 UTC (snapshot captured 2026-10-18)`, `2026-10-18 03:26 UTC (snapshot captured 2026-10-18)`, `2026-10-18 03:27 UTC (snapshot captured 2026-10-18)` |  | When the headlines were fetched, as text: date and time in UTC, and in the snapshot "(snapshot captured <date>)". |
| `ITEMS` | record list |  | required |  |  |  | The headlines, newest first, one element each: PUBLISHED, SOURCE (the publisher), TITLE and LINK. |
| `BY_SOURCE` | record list |  | required |  |  |  | How many of the headlines each publisher wrote, one element per publisher: SOURCE and COUNT. |
| `PIPELINE` | record list |  | required |  |  |  | What each step of the news pipeline did, one element per step in order (relevance, duplicates …): STEP, KEPT and DROPPED (headline counts). |
| `FETCHED` | integer |  | required |  | `100` |  | How many headlines the feed returned before the pipeline (relevance, duplicates, limit) filtered them. |
| `PROVENANCE` | record |  | required |  |  |  | Where the headlines come from, one object: FEED (Google News RSS), FETCHED, CACHED_FOR, TERMS and SNAPSHOT. |
| `SNAPSHOT` | text |  | required |  | `Snapshot captured 2026-10-18 03:24 UTC from Google News` |  | Says the document is a committed snapshot: when it was captured and from where (tools/testdata/capture_market.py). |
| `SNAPSHOT_CAPTURED_AT` | timestamp |  | required |  | `2026-10-18T03:24:33Z` |  | When the snapshot was captured, ISO-8601 UTC. |

Nested `ITEMS[]`:

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `PUBLISHED` | text |  | required |  | `2026-10-18 01:37 UTC` |  | When the headline was published, as text (YYYY-MM-DD HH:MM UTC). |
| `SOURCE` | text |  | required |  | `Simply Wall Street` |  | The publisher of the headline. |
| `TITLE` | text |  | required |  | `Alphabet (GOOGL) Plans Toronto Robotaxi Service By 2028` |  | The headline as the publisher wrote it. |
| `LINK` | text |  | required |  | `https://news.google.com/rss/articles/CBMiugFBVV95cUxOWF9ZUU…` |  | The link to the article (a Google News redirect URL). |
| `SNIPPET` | unknown |  | optional |  |  |  | The feed's short summary of the article, when it gives one. |

Nested `BY_SOURCE[]`:

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `SOURCE` | text |  | required |  | `The Motley Fool` |  | A publisher among ITEMS. |
| `COUNT` | integer |  | required |  | `3` |  | How many of ITEMS that publisher has. |

Nested `PIPELINE[]`:

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `DROPPED` | integer |  | required |  | `4` |  | How many headlines the step removed. |
| `KEPT` | integer |  | required |  | `96` |  | How many headlines remained after the step. |
| `STEP` | text |  | required |  | `duplicates`, `limit`, `relevance` |  | The pipeline step, in order: relevance (the title names the entity or an alias), duplicates (the same story or headline from the same publisher kept once, the newest), limit (at most the connector's max-items); sources (deny or allow lists) when set. |

Nested `PROVENANCE`:

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `FEED` | text |  | required |  | `Google News RSS (news.google.com)` |  | The feed the headlines came from (Google News RSS). |
| `FETCHED` | text |  | required |  | `2026-10-18 03:25 UTC`, `2026-10-18 03:26 UTC`, `2026-10-18 03:27 UTC` |  | When the connector fetched the feed, as text (YYYY-MM-DD HH:MM UTC). |
| `CACHED_FOR` | text |  | required |  | `3600 s` |  | How long the connector caches a fetch (its cache TTL), as text. |
| `TERMS` | text |  | required |  | `Headlines and links belong to their publishers; shown for i…` |  | The terms the headlines may be shown under. |
| `SNAPSHOT` | text |  | required |  | `Snapshot captured 2026-10-18 03:24 UTC from Google News; se…` |  | Says the document is served from the committed snapshot in the demo lake, not live. |

**Links:** `NEWS_ID` → `news`.

- Several source systems: the connector news and news-store each serve this kind from a table of their own with the same layout; the storage above is the first one's.

### CO: Listed company (`listed-company`)

A listed company: its live quote and price history (yfinance connector) and its news headlines (news connector), both computed on demand.

**Class** fundamental · **Key** `COMPANY_ID` · **Dated by** `snapshot` · **Cadence** static · **Stored by** connector `news-store` (plugin `delta`), table `<root>/news/listed-company/` · **Ids** `^CO-`.

**Grain:** One row per listed company, keyed by COMPANY_ID. **Contract:** `contracts/listed-company.contract.yaml` ([full contract page](../../../../docs/contracts/packs/news-and-markets.md#co-listed-company-listed-company)).

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `COMPANY_ID` | id | key | required |  | `CO-AAPL` (pattern `^CO-`) |  | The record's id (the business key) |
| `TICKER` | text |  | required |  | `AAPL` |  | The company's stock exchange ticker (AAPL); the quote's and the headlines' symbol. |
| `NAME` | text |  | required |  | `Apple Inc.` |  | The company's name as listed (Apple Inc.). |
| `ALIASES` | text list |  | required |  |  |  | Other names of the company the news connector accepts in a headline (its relevance step); ALIAS_TEXT is the same list as text. |
| `ALIAS_TEXT` | text |  | required |  | `Apple Inc, AAPL, iPhone` |  | Other names the headlines may use for the company, comma-separated ("Apple Inc, AAPL, iPhone"); the news search's aliases. |
| `EXCHANGE` | text |  | required |  | `NYSE`, `Nasdaq` |  | The exchange the company is listed on (Nasdaq, NYSE). |
| `SECTOR` | text |  | required |  | `Technology` |  | The company's sector (Technology, Financials …), a label of the pack. |
| `COUNTRY` | text |  | required |  | `US` |  | The company's home country, ISO 3166-1 alpha-2 (US, GB). |
| `ECONOMY` | id |  | required |  | `ECON-USA` (pattern `^ECON-`) | `economy` | The company's home economy: an id of the world-economy pack's economy kind (ECON-<ISO 3166-1 alpha-3>). |
| `QUOTE_ID` | id |  | required |  | `QUOTE-AAPL` (pattern `^QUOTE-`) | `quote` | The company's quote: a QUOTE_ID of the quote kind (QUOTE-<ticker>). |
| `NEWS_ID` | id |  | required |  | `NEWS-Apple` (pattern `^NEWS-`) | `news` | The company's headlines with the relevance filter on: a NEWS_ID of the news kind (NEWS-<name>). |
| `NEWS_ALL_ID` | id |  | required |  | `NEWS-Apple~all` (pattern `^NEWS-`) | `news` | The company's headlines without the relevance filter: a NEWS_ID of the news kind (NEWS-<name>~all). |

**Links:** `ECONOMY` → `economy`, `QUOTE_ID` → `quote`, `NEWS_ID` → `news`, `NEWS_ALL_ID` → `news`.

### QUOTE: Quote and price history (`quote`)

A quote and recent price history for a ticker, as the yfinance connector computed them.

**Class** fundamental · **Key** `QUOTE_ID` · **Dated by** `snapshot` · **Cadence** intraday · **Stored by** connector `yfinance` (plugin `yfinance`) · **Ids** `^QUOTE-`.

**Grain:** One row per quote and price history, keyed by QUOTE_ID. **Contract:** `contracts/quote.contract.yaml` ([full contract page](../../../../docs/contracts/packs/news-and-markets.md#quote-quote-and-price-history-quote)).

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `QUOTE_ID` | id | key | required |  | `QUOTE-AAPL` (pattern `^QUOTE-`) | `quote` | The record's id (the business key) |
| `SYMBOL` | text |  | required |  | `AAPL` |  | The Yahoo Finance symbol quoted (chart meta symbol). |
| `NAME` | text |  | required |  | `Apple Inc.` |  | The instrument's name (Yahoo's long name, else its short name). |
| `EXCHANGE` | text |  | required |  | `NYSE`, `NasdaqGS` |  | The exchange's name as Yahoo gives it (chart meta fullExchangeName, else exchangeName): NasdaqGS, NYSE. |
| `CURRENCY` | text |  | required |  | `USD` |  | The currency of every price of the quote, ISO 4217 (Yahoo chart meta currency). |
| `INSTRUMENT_TYPE` | text |  | required |  | `EQUITY` |  | Yahoo's instrument type (EQUITY, ETF, INDEX ...). |
| `PRICE` | amount |  | required | currency units of CURRENCY | `343.46` |  | The last traded price (Yahoo chart meta regularMarketPrice, else the last bar's close). |
| `PREVIOUS_CLOSE` | amount |  | required | currency units of CURRENCY | `342.0` |  | The previous session's closing price (Yahoo chart meta). |
| `CHANGE` | amount |  | required | currency units of CURRENCY | `1.4599999999999795` |  | PRICE minus PREVIOUS_CLOSE, gain positive. |
| `CHANGE_PCT` | rate |  | required | percent | `0.4269005847953157` |  | CHANGE as a percentage of PREVIOUS_CLOSE, in percent (0.43 is 0.43%), gain positive. |
| `DAY_LOW` | amount |  | required | currency units of CURRENCY | `340.46` |  | The session's lowest price. |
| `DAY_HIGH` | amount |  | required | currency units of CURRENCY | `347.82` |  | The session's highest price. |
| `FIFTY_TWO_WEEK_LOW` | amount |  | required | currency units of CURRENCY | `243.42` |  | The lowest price over the last 52 weeks (Yahoo fiftyTwoWeekLow). |
| `FIFTY_TWO_WEEK_HIGH` | amount |  | required | currency units of CURRENCY | `348.15` |  | The highest price over the last 52 weeks (Yahoo fiftyTwoWeekHigh). |
| `VOLUME` | amount |  | required | shares | `59852690.0` |  | The session's traded volume (Yahoo regularMarketVolume). |
| `MARKET_CAP` | unknown |  | optional |  |  |  | The market capitalisation when Yahoo's chart endpoint gives one; usually absent. |
| `MARKET_STATE` | text |  | required |  | `CLOSED` |  | The market's state when fetched: Yahoo's marketState as given, else REGULAR or CLOSED from the regular trading period, else UNKNOWN. |
| `AS_OF` | text |  | required |  | `2026-10-16 16:00 America/New_York (snapshot captured 2026-1…` |  | When the quoted price was last traded, as text: local time and the exchange's time zone; in the snapshot it ends with "(snapshot captured <date>)". |
| `HISTORY` | record list |  | required |  |  |  | The daily bars of the last year, oldest first, one element per trading day: DATE, OPEN, HIGH, LOW, CLOSE and VOLUME. |
| `DAY_RANGE` | text |  | required |  | `340.46 – 347.82` |  | DAY_LOW – DAY_HIGH as text. |
| `FIFTY_TWO_WEEK_RANGE` | text |  | required |  | `243.42 – 348.15` |  | FIFTY_TWO_WEEK_LOW – FIFTY_TWO_WEEK_HIGH as text. |
| `HISTORY_RANGE` | text |  | required |  | `1y` |  | The span of HISTORY asked of Yahoo (the connector's range setting, 1y). |
| `INTERVAL` | text |  | required |  | `1d` |  | The spacing of HISTORY's bars (the connector's interval setting, 1d). |
| `PROVENANCE` | record |  | required |  |  |  | Where the quote comes from, one object: FEED (Yahoo Finance's public chart endpoint), FETCHED (ISO-8601 UTC), CACHED_FOR, TERMS and SNAPSHOT. |
| `SNAPSHOT` | text |  | required |  | `Snapshot captured 2026-10-18 03:24 UTC from Yahoo Finance` |  | Says the document is a committed snapshot: when it was captured and from where (tools/testdata/capture_market.py). |
| `SNAPSHOT_CAPTURED_AT` | timestamp |  | required |  | `2026-10-18T03:24:33Z` |  | When the snapshot was captured, ISO-8601 UTC. |

Nested `HISTORY[]`:

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `DATE` | date |  | required |  | `2025-10-17` |  | The bar's date (YYYY-MM-DD for daily bars). |
| `OPEN` | amount |  | required | currency units of CURRENCY | `248.02000427246094` |  | The bar's opening price. |
| `HIGH` | amount |  | required | currency units of CURRENCY | `253.3800048828125` |  | The bar's highest price. |
| `LOW` | amount |  | required | currency units of CURRENCY | `247.27000427246094` |  | The bar's lowest price. |
| `CLOSE` | amount |  | required | currency units of CURRENCY | `252.2899932861328` |  | The bar's closing price. |
| `VOLUME` | amount |  | required | shares | `49147000.0` |  | The bar's traded volume. |

Nested `PROVENANCE`:

| Column | Type | Key | Presence | Unit | Example or values | Link | Meaning |
|---|---|---|---|---|---|---|---|
| `FEED` | text |  | required |  | `Yahoo Finance public chart endpoint (unofficial)` |  | The feed the quote came from (Yahoo Finance's public chart endpoint). |
| `FETCHED` | timestamp |  | required |  | `2026-10-18T03:24:33.511628799Z` |  | When the connector fetched the quote, ISO-8601 UTC. |
| `CACHED_FOR` | text |  | required |  | `3600 s` |  | How long the connector caches a fetch (its cache TTL), as text. |
| `TERMS` | text |  | required |  | `Sample and internal demonstration use only; not for product…` |  | The terms the data may be used under. |
| `SNAPSHOT` | text |  | required |  | `Snapshot captured 2026-10-18 03:24 UTC from Yahoo Finance; …` |  | Says the document is served from the committed snapshot in the demo lake, not live, and that the data belongs to its providers. |

**Links:** `QUOTE_ID` → `quote`.

- Several source systems: the connector yfinance and news-store each serve this kind from a table of their own with the same layout; the storage above is the first one's.

## Data retrieval

Where each kind's data comes from, how it reaches the lake and how to check it. Three origins: **external** data, fetched from a public source by the pack's refresh script (each source, its licence and worked examples on the [External data](../../../../docs/guides/DATA_GUIDE.md#4-external-data-where-it-comes-from-and-how-to-refresh-it) help page); **synthetic** test data (or hand-written reference data), written by a generator with fictitious names; **computed** by a Drishti engine when it is asked, never stored by a source.

| Command | Kind | Origin | How it reaches Drishti |
|---|---|---|---|
| `TOPIC` | `news-topic` | hand-written reference data: `python3 config/packs/news-and-markets/tools/make_samples.py` | `config/packs/news-and-markets/samples/` → `lake demo` → connector `news-store` |
| `NEWS` | `news` | external: Google News RSS headlines (the news connector) (the headlines belong to their publishers; **internal only**) — refreshed by `tools/publicdata/news_and_markets.py` | `data/testdata/news-and-markets/news/` → `lake demo` → connector `news-store`; live through connector `news` (plugin `news`) when it is switched on (off as shipped) |
| `CO` | `listed-company` | hand-written reference data: `python3 config/packs/news-and-markets/tools/make_samples.py` | `config/packs/news-and-markets/samples/` → `lake demo` → connector `news-store` |
| `QUOTE` | `quote` | external: Yahoo Finance quotes and daily history (the yfinance connector) (Yahoo terms: personal use, no redistribution; **internal only**) — refreshed by `tools/publicdata/news_and_markets.py` | `data/testdata/news-and-markets/quote/` → `lake demo` → connector `news-store`; live through connector `yfinance` (plugin `yfinance`) when it is switched on (off as shipped) |

### External sources

| Source | Where | Licence | Internal only | Refresh |
|---|---|---|---|---|
| Google News RSS headlines (the news connector) | https://news.google.com/rss | the headlines belong to their publishers | **yes** | `python3 tools/publicdata/news_and_markets.py refresh news` |
| Yahoo Finance quotes and daily history (the yfinance connector) | https://finance.yahoo.com | Yahoo terms: personal use, no redistribution | **yes** | `python3 tools/publicdata/news_and_markets.py refresh quote` |

**Internal only:** Google News RSS headlines (the news connector); Yahoo Finance quotes and daily history (the yfinance connector). The terms restrict reuse or forbid redistribution: the data is for internal demonstrations only, never written into a committed folder without `--internal-ok`, and must be raised with the owner before any client or external use.

```bash
python3 tools/publicdata/news_and_markets.py status                 # each source: licence, internal only or not, last refreshed (offline)
python3 tools/publicdata/news_and_markets.py refresh --dry-run      # what would be fetched and written; changes nothing
python3 tools/publicdata/news_and_markets.py refresh                # a new snapshot in <data>/publicdata/news-and-markets/testdata (through a scratch server's live connectors)
python3 tools/publicdata/news_and_markets.py refresh --internal-ok  # replace the committed snapshot in data/testdata (internal demos only), then lake demo
```

The same through the wrappers: `tools/publicdata/news-and-markets.sh status` on Linux and macOS, `.\tools\windows\publicdata-news-and-markets.ps1 status` on Windows (every argument is passed on).

### Generators

| Generator | Writes | Check |
|---|---|---|
| `python3 config/packs/news-and-markets/tools/make_samples.py` | hand-written reference data (topics and listed companies) into the pack's samples/ | — |

### Into the lake, checking, offline

- **The lake.** `python3 tools/drishti.py lake demo` reads `data/testdata/testdata.yaml` and writes every dataset (test data, the packs' samples and the committed public-data snapshots) into `<data>/delta/<domain>/<kind>/`, where the pack's Delta connector serves it; a computed kind needs nothing loaded. `lake build` loads your own large JSON exports the same way ([Tools](../../../../docs/guides/TOOLS.md)).
- **Checking.** `python3 tools/contracts.py check` compares every contract with the pack and its data; each generator's `--check` (above) fails when the committed files differ from what it would write; in the console, `<MNEMONIC> <GO>` lists a kind and the pack's code opens its overview with the count of each kind.
- **Offline.** Everything committed works without a network: the test data, the samples and the public-data snapshots are in the repository and `lake demo` reads only them. A refresh script runs offline from its download cache (`--offline`); a connector that reaches the internet (news, yfinance, the public-data feeds) is off as shipped and never starts on a machine configured offline (`drishti.offline.plugins`).

<!-- /data-sections -->
