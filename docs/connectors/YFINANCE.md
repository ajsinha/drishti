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
# The yfinance connector: quotes and price history for a ticker, computed on demand

The `yfinance` plugin (module `plugins/drishti-plugin-yfinance`) serves a quote and recent price history for a ticker symbol. Like
[the news connector](NEWS.md) it is **code, not a store**: nothing is held, and each document is computed when it is asked for, from
Yahoo Finance's public chart endpoint (no API key). Read [Connectors that compute data on demand](CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand)
for the pattern.

> **Samples and internal demonstrations only.** The endpoint (`query1.finance.yahoo.com/v8/finance/chart`) is **unofficial**: it is not
> a supported API, it can change or disappear without notice, and Yahoo's terms restrict its use. Do not run this connector in
> production, do not redistribute what it returns, and do not build anything that depends on it. For real market data use a licensed
> feed through one of the supported connectors. It is **off as shipped**; the ticker you ask about leaves your network to Yahoo when it is on.

## Contents

1. [What it does](#1-what-it-does)
2. [Turning it on](#2-turning-it-on)
3. [Ids](#3-ids)
4. [What a document looks like](#4-what-a-document-looks-like)
5. [How the figures are worked out](#5-how-the-figures-are-worked-out)
6. [Settings](#6-settings)
7. [Health, errors and retries](#7-health-errors-and-retries)
8. [Using it from a pack](#8-using-it-from-a-pack)
9. [How it is built](#9-how-it-is-built)
10. [Tests](#10-tests)

## 1. What it does

`QUOTE-AAPL` returns the last price, the change from the previous close in money and percent, the currency, the market state, the
day's and the 52-week range, the volume, the market capitalisation when Yahoo gives one, and the closing prices (with open, high,
low and volume) for the configured range at the configured interval. The console lays it out with the `quote` Sutra of the
`market-watch` pack: a strip, a closing-price line, the ranges and a table of bars.

## 2. Turning it on

A pack that uses it (`market-watch`) carries a template; at first start the server writes `config/connectors/yfinance.yaml` from it with
`enabled: ${DRISHTI_YFINANCE_ENABLED:false}`.

```
DRISHTI_YFINANCE_ENABLED=true        # or enabled: true in config/connectors/yfinance.yaml, or Enable in Admin, Connectors
```

Enabling and disabling the file is live; a new plugin jar needs a restart (the plugin ships inside `drishti-server`). An annotated copy
is [`config/connectors.examples/other/yfinance.yaml`](../../config/connectors.examples/other/yfinance.yaml). To use a proxy or a mirror, set
`base-url`; for HTTPS on a private CA give the shared [`tls.*`](TLS.md) settings.

## 3. Ids

`QUOTE-<symbol>`, the symbol being 1 to 15 characters of letters, digits and `. ^ = -` (so `QUOTE-BRK-B`, `QUOTE-^GSPC`, `QUOTE-EURUSD=X`
work). It is upper-cased. Anything else is "not held" and is never sent to Yahoo. A symbol Yahoo does not know (HTTP 404, "No data
found") is also "not held", and that answer is cached for the TTL so a page does not ask again at once.

## 4. What a document looks like

Captured from the connector reading the local fake the tests use, `GET /api/v1/entities/quote/QUOTE-AAPL/raw` (the history is shortened):

```json
{
  "ref": { "kind": "quote", "id": "QUOTE-AAPL" },
  "provenance": { "source": "yfinance", "generation": 1791705297975, "fetchedAt": "2026-10-11T07:54:57.975654276Z", "live": false, "businessDate": null },
  "data": {
    "quoteId": "QUOTE-AAPL", "symbol": "AAPL", "name": "AAPL Corporation", "exchange": "NasdaqGS", "currency": "USD", "instrumentType": "EQUITY",
    "price": 204.3, "previousClose": 202.6, "change": 1.7, "changePct": 0.8390918065153096,
    "dayLow": 201.9, "dayHigh": 205.4, "fiftyTwoWeekLow": 140.1, "fiftyTwoWeekHigh": 230.5, "volume": 51234567.0, "marketCap": null,
    "marketState": "UNKNOWN", "asOf": "2026-10-20 10:13 America/New_York",
    "history": [ { "date": "2026-09-21", "open": 182.0, "high": 183.0, "low": 181.0, "close": 182.0, "volume": 1000.0 },
                 { "date": "2026-09-22", "open": 183.7, "high": 184.7, "low": 182.7, "close": 183.7, "volume": 2000.0 }, "..." ],
    "dayRange": "201.90 – 205.40", "fiftyTwoWeekRange": "140.10 – 230.50", "historyRange": "6mo", "interval": "1d",
    "provenance": { "feed": "Yahoo Finance public chart endpoint (unofficial)", "fetched": "2026-10-11T07:54:57.975654276Z", "cachedFor": "60 s",
                    "terms": "Sample and internal demonstration use only; not for production or redistribution" }
  }
}
```

`marketCap` is `null` when Yahoo's chart answer does not carry it (it usually does not). Every field of the quote is optional except the price.

## 5. How the figures are worked out

| Field | From |
|---|---|
| `price` | `meta.regularMarketPrice`, else the last close of the history |
| `previousClose` | `meta.previousClose` when present; else, for daily bars, the close before the last bar; else `meta.chartPreviousClose` (which, for a long range, is the close before the range began, hence the order) |
| `change`, `changePct` | `price - previousClose`, and that over `previousClose` as a percent (`+0.84` means 0.84 %) |
| `marketState` | `meta.marketState` when given; else `REGULAR` when now lies in the regular trading period of `meta.currentTradingPeriod`, `CLOSED` when not, `UNKNOWN` if the answer has no period |
| `history` | the timestamps and `indicators.quote[0]` arrays; a bar without a close is skipped; dates are in the exchange's time zone (`gmtoffset`): `yyyy-MM-dd` for daily and longer bars, with `HH:mm` for intraday |
| `dayRange`, `fiftyTwoWeekRange` | the low and high as text, `188.90 – 192.30` |

A number that is not a number, or a field of the wrong type, is read as missing; an answer that is not a chart (`{"hello":"world"}`) or is
not JSON is an error, never a half-empty quote.

## 6. Settings

| Setting | Default | Meaning |
|---|---|---|
| `base-url` | `https://query1.finance.yahoo.com/v8/finance/chart` | the endpoint, without the symbol |
| `range` | `6mo` | history span: `1d 5d 1mo 3mo 6mo 1y 2y 5y 10y ytd max` |
| `interval` | `1d` | bar size: `1m 2m 5m 15m 30m 60m 90m 1h 1d 5d 1wk 1mo 3mo` (Yahoo limits intraday bars to short ranges) |
| `user-agent` | `Mozilla/5.0 (compatible; drishti-yfinance-connector)` | the `User-Agent` header; Yahoo refuses an empty one |
| `id-prefix` | `QUOTE-` | prefix of ids |
| `kind` | `quote` | the kind served |
| `timeout-seconds` | `10` | one HTTP attempt (1 to 120) |
| `cache-ttl-seconds` | `60` | how long a symbol's answer is reused; `0` never reuses |
| `cache-max-symbols` | `100` | symbols cached (least recently used leave first) |
| `max-bytes` | `2000000` | the largest response accepted |
| `retries` | `2` | repeats of a failed request (0 to 10) |
| `retry-initial`, `retry-max`, `retry-factor`, `retry-jitter` | `400ms`, `4s`, `2`, `0.2` | the shared backoff shape between repeats |
| `source-name` | `yfinance` | the name shown as the document's source |
| `tls.*` | none | the shared [TLS settings](TLS.md) |
| `proxy.*` | none | the outbound HTTP proxy ([TLS.md (proxy)](TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers)): `proxy.url`, `proxy.username`, `proxy.password` (`${ENV}`), `proxy.no-proxy`, `proxy.enabled`, or `proxy: none` |

A bad value (a range or interval not in the lists, a non-HTTP address, a number out of range) fails at start with the setting's name.

## Outbound proxy

The settings and rules are shared by every HTTP-based connector: [TLS.md (proxy)](TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers). A `proxy:` block in `settings:` (or `proxy: none`) overrides the site default `drishti.http.proxy.*`; the password is an `${ENV}` placeholder, never a literal.

Yahoo's chart endpoint is reached through the proxy when one applies. Health ends `via proxy host:port (source)`. Tested in `YFinanceProxyTest`
against a fake proxy and the fake Yahoo server.

## 7. Health, errors and retries

Start fetches nothing and never fails because the web is unreachable. Health is `UP` until a call fails, then
`DOWN: <reason> (tried again on the next request)`, and `UP` after the next success.

| Situation | What happens |
|---|---|
| connection failure, timeout, HTTP 429 (Yahoo's rate limit), HTTP 5xx | repeated up to `retries` times with backoff and jitter; then the read fails, or serves the older cached answer if one is held |
| HTTP 404 or Yahoo's `Not Found` error | the symbol is not held (health stays `UP`) |
| any other 4xx (403) | fails at once |
| response over `max-bytes` | fails at once, naming the limit |
| not JSON, or JSON of another shape | fails: `the response is not readable JSON` or `is not a Yahoo chart answer` |

As with the news connector, a page that reads the quote through `source: link(...)` shows it as pending on the first view if Yahoo takes longer than
`drishti.graph.link-budget` (40 ms by default); see [NEWS.md, section 7](NEWS.md#7-health-errors-and-retries).

## 8. Using it from a pack

```yaml
connectors:
  yfinance: { plugin: yfinance, enabled: "${DRISHTI_YFINANCE_ENABLED:false}", kinds: [quote], settings: { range: 6mo, interval: 1d } }
routes: { quote: yfinance }
```

```yaml
# a company's document holds  "quoteId": "QUOTE-AAPL";  its Sutra:
  - { id: quote, kind: kv, source: "link($.quoteId, 'quote')", columns: [ { label: Price, bind: $.price, fmt: price2 }, ... ] }
  - { id: history, kind: line, source: "link($.quoteId, 'quote')", rows: $.history, x: date, y: close, fmt: price2 }
```

See the [`market-watch` sample pack](../guides/PACKS.md#sample-packs).

## 9. How it is built

`plugins/drishti-plugin-yfinance/src/main/java/com/ash/drishti/plugin/yfinance/`: `YFinanceSourcePlugin` (the SPI, the cache, the single flight per
symbol), `YFinanceSettings`, `HttpFetcher` (bounded GET with retry; carries the HTTP status so a 404 can mean "unknown symbol"),
`ChartParser` (defensive JSON), `BoundedCache`. The module is Spring-free. It is the same shape as [the news connector](NEWS.md#9-how-it-is-built).

## 10. Tests

No test calls Yahoo. `FakeYahooServer` serves, by symbol: a quote with five daily bars (one without a close), Yahoo's not-found error, text that
is not JSON, JSON of another shape, an oversized body, a slow response, two 503s then success, a 429 then success, and a 403.
`YFinanceSourcePluginTest` (8 tests) checks the parsing and the derived figures, unknown symbols, the three bad-body cases, timeout and retry,
the cache (hit, TTL 0, the bound), bad settings and the contract of an on-demand source. The browser test
(`drishti-console/tests/test_news_browser.py`) opens a company page over the real server with both fakes behind it.
