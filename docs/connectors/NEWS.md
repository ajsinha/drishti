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
# The news connector: a connector that is code, not a store

Most connectors read a store: a lake, a database, a queue. The `news` plugin (module `plugins/drishti-plugin-news`) holds
nothing. Its documents are **computed when they are asked for**: the entity id carries a name, the connector searches Google News
for it, reads the answer safely, filters and tidies it, and returns the headlines as an ordinary entity that the console lays out
like any other. It is the teaching example for [connectors that compute data on demand](CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand);
its sibling [YFINANCE.md](YFINANCE.md) does the same for market quotes. The sample pack
[news-and-markets](../guides/PACK_DEVELOPER_GUIDE.md#sample-packs) is built on them.

> **Privacy: names leave your network.** Every time a headline list is not in the cache, the connector sends the entity's name
> (and the language and country of the edition) to `news.google.com`. For a company or a country that is harmless; for a client,
> a counterparty under review or an internal project name it may not be. The connector is therefore **off as shipped**
> (`enabled: false`); switching it on is a decision for the site. Google's terms govern the feed, and the headlines and links belong
> to their publishers. There is no API key and nothing is stored on disk.

## Contents

1. [What it does](#1-what-it-does)
2. [Turning it on](#2-turning-it-on)
3. [Ids: asking for a name](#3-ids-asking-for-a-name)
4. [The logic, step by step](#4-the-logic-step-by-step)
5. [What a document looks like](#5-what-a-document-looks-like)
6. [Settings](#6-settings)
7. [Health, errors and retries](#7-health-errors-and-retries)
8. [Using it from a pack](#8-using-it-from-a-pack)
9. [How it is built](#9-how-it-is-built)
10. [Tests](#10-tests)

## 1. What it does

| You ask for | You get |
|---|---|
| `news NEWS-Federal_Reserve` | the newest headlines that name the Federal Reserve (or *Fed*, *FOMC*, ...), newest first, each with publisher, time, link and a short snippet; a count by publisher; and the list of steps that chose them |
| `news NEWS-Toyota@ja-JP` | the same from the Japanese edition |
| `news NEWS-Federal_Reserve~all` | everything the feed returned, without the relevance filter (the publisher lists and de-duplication still apply) |

Nothing is listed or indexed ahead of time, so `NEWS <GO>` has no list to show; `NEWS NEWS-<any name>` works for any name. Names that
have been asked for since the server started can be found with the command-line suggestions.

## 2. Turning it on

The connector is a site resource like any other: one file, `config/connectors/news.yaml`. It does not exist until a pack that uses it
(`news-and-markets`) is loaded; the server then writes it **once** from the pack's template, with
`enabled: ${DRISHTI_NEWS_ENABLED:false}`. To use it:

```
DRISHTI_NEWS_ENABLED=true            # or set  enabled: true  in config/connectors/news.yaml, or press Enable in Admin, Connectors
```

Enabling or disabling the file is live (no restart). **A new plugin jar needs a restart**, as for every plugin: the plugin is part of
`drishti-server`, so it is there from the release that ships it. An annotated example is in
[`config/connectors.examples/other/news.yaml`](../../config/connectors.examples/other/news.yaml).

Behind a proxy or a mirror, set `base-url` to it; if it is HTTPS on a private CA, give the shared [`tls.*`](TLS.md) settings.

## 3. Ids: asking for a name

```
NEWS-<name>[@<language>-<COUNTRY>][~all]
```

| Part | Meaning |
|---|---|
| `NEWS-` | the id prefix (setting `id-prefix`) |
| `<name>` | the entity name; underscores stand for spaces: `NEWS-Bank_of_England` |
| `@de-DE` | optional: another edition (language, country) for this read, instead of `language` and `country` |
| `~all` | optional: no relevance filter for this read |

A name can be 120 characters at most and holds no control characters; anything else is "not held". The id is what a pack's document
holds (`NEWS_ID: NEWS-Federal_Reserve`), so a page can read the headlines with `source: "link($.NEWS_ID, 'news')"`.

## 4. The logic, step by step

The code that does this is `NewsPipeline`; every step reports what it kept, and the document carries the report (`pipeline`), so a
reader can see why a headline is not there.

| Step | What it does | Setting |
|---|---|---|
| fetch | `GET <base-url>?q=<query>&hl=<language>&gl=<COUNTRY>&ceid=<COUNTRY>:<language>`, the query being `query-template` with `{name}` replaced | `base-url`, `language`, `country`, `query-template` |
| parse | RSS 2.0 into items: title (Google's trailing ` - Publisher` removed), publisher, link (http or https only, so `javascript:` is dropped), time (RFC 1123), snippet (tags and entities removed, a repeat of the title dropped) | `max-bytes` |
| relevance | keep an item only if its **title** names the entity or one of its aliases, as whole words, ignoring case and accents | `relevance` (`title` or `off`), `aliases.<Name>` |
| sources | drop a publisher on the deny list; if there is an allow list, keep only those | `deny-sources`, `allow-sources` |
| order | newest first; items without a usable date last | |
| duplicates | the same address, or the same normalised headline from the same publisher, is kept once (the newest) | |
| limit | at most this many | `max-items` |

The result of the web call, before any step, is cached per name and edition for `cache-ttl-seconds`, in a cache of at most
`cache-max-entities` names (least recently used leave first). The relevance setting applies on every read, so `NEWS-x` and `NEWS-x~all`
share one web call. Concurrent reads of one name wait for a single call. If the web fails but an older answer is held, the older answer
is served and health says why.

## 5. What a document looks like

Captured from the connector reading the local fake used by the tests (a canned RSS with five items: a duplicate and one that does not
name the entity), `GET /api/v1/entities/news/NEWS-Federal_Reserve/raw`:

```json
{
  "ref": { "kind": "news", "id": "NEWS-Federal_Reserve" },
  "provenance": { "source": "news", "generation": 1791705297924, "fetchedAt": "2026-10-11T07:54:57.924352637Z", "live": false, "businessDate": null },
  "data": {
    "NEWS_ID": "NEWS-Federal_Reserve",
    "ENTITY": "Federal Reserve",
    "ALIASES": ["Fed", "FOMC", "Powell"],
    "EDITION": "en-US",
    "RELEVANCE_FILTER": "on",
    "COUNT": 3,
    "FETCHED_AT": "2026-10-11 07:54 UTC",
    "ITEMS": [
      { "PUBLISHED": "2026-10-09 18:45 UTC", "SOURCE": "Reuters", "TITLE": "Federal Reserve announces record quarter", "LINK": "https://news.example/a1", "SNIPPET": "" },
      { "PUBLISHED": "2026-10-08 07:15 UTC", "SOURCE": "Bloomberg", "TITLE": "Why Federal Reserve shares moved on Thursday", "LINK": "https://news.example/b1", "SNIPPET": "" },
      { "PUBLISHED": "2026-10-07 12:00 UTC", "SOURCE": "Financial Times", "TITLE": "Analysts react to Federal Reserve guidance", "LINK": "https://news.example/c1", "SNIPPET": "" }
    ],
    "BY_SOURCE": [ { "SOURCE": "Reuters", "COUNT": 1 }, { "SOURCE": "Bloomberg", "COUNT": 1 }, { "SOURCE": "Financial Times", "COUNT": 1 } ],
    "PIPELINE": [ { "STEP": "relevance", "KEPT": 4, "DROPPED": 1 }, { "STEP": "duplicates", "KEPT": 3, "DROPPED": 1 }, { "STEP": "limit", "KEPT": 3, "DROPPED": 0 } ],
    "FETCHED": 5,
    "PROVENANCE": { "FEED": "Google News RSS (127.0.0.1)", "FETCHED": "2026-10-11 07:54 UTC", "CACHED_FOR": "600 s",
                    "TERMS": "Headlines and links belong to their publishers; shown for information" }
  }
}
```

(The `pipeline` lists only the steps that ran: `sources` appears when a publisher list is set.)

## 6. Settings

All in the connector file's `settings:`. Durations are whole seconds.

Every setting of `news`, with its type, default and meaning, is in the plugin's generated README: [plugins/drishti-plugin-news/README.md, Settings](../../plugins/drishti-plugin-news/README.md#settings) (from the settings catalogue Admin → Connectors builds its form from). The shared `tls.*` settings are in [TLS.md](TLS.md).

Read beside them, and not in that table:

| Setting | Default | Meaning |
|---|---|---|
| `aliases.<Name_with_underscores>` | none | comma list of other names that count as naming the entity: `aliases.Federal_Reserve: Fed, FOMC` |

A bad value fails at start with the setting's name in the message. The settings form in Admin, Connectors lists these.

## Outbound proxy

The settings and rules are shared by every HTTP-based connector: [TLS.md (proxy)](TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers). A `proxy:` block in `settings:` (or `proxy: none`) overrides the site default `drishti.http.proxy.*`; the password is an `${ENV}` placeholder, never a literal.

Google News is reached through the proxy when one applies; the first request of a name and every retry use it. Health ends `via proxy host:port (source)`.
Tested in `NewsProxyTest` (through the proxy with a login, no-proxy bypass, the site default, `proxy: none`) against a fake proxy and the fake news server.

## 7. Health, errors and retries

Start never fails because the web is unreachable, and nothing is fetched at start (there is no entity yet). Health is `UP` until a call
fails, then `DOWN: <reason> (tried again on the next request)`, and `UP` again at the next success.

| Situation | What happens |
|---|---|
| connection failure, timeout, HTTP 429, HTTP 5xx | repeated up to `retries` times with the shared retry shape (`drishti.retry.*`: exponential backoff with jitter; here `retry-initial`, `retry-max`, `retry-factor`, `retry-jitter`); then the read fails, or serves the older cached answer if there is one |
| any other HTTP 4xx (403, 404) | fails at once: waiting will not change it |
| response over `max-bytes` | fails at once, naming the limit |
| not RSS (HTML error page, broken XML) | fails: `the response is not a readable RSS feed` |
| RSS with a DOCTYPE or entities | refused: the parser never resolves a DOCTYPE, external entity or include (no XXE) |
| a name nothing is written about | an empty list: a valid document with `count: 0` |

A failed read never returns "not held": it throws, so a view says the source failed rather than that there is no news.

**Linked panels and the link budget.** A page that reads headlines through `source: link(...)` waits for them at most
`drishti.graph.link-budget` (40 ms by default). A web call takes longer, so the first view of a name shows its news panels as *pending*
and the next view (within `cache-ttl-seconds`) shows the headlines. For a site that uses this sample pack, set the budget to a
second or two (`drishti.graph.link-budget: 2s`, see [PERFORMANCE.md](../admin/PERFORMANCE.md)). While the connector is off the
panels stay empty with the reason (`waiting for NEWS-...`), and the page's own note says why.

## 8. Using it from a pack

A pack names the connector, routes the kind, and gives the entity the id of its news:

```yaml
connectors:
  news: { plugin: news, enabled: "${DRISHTI_NEWS_ENABLED:false}", kinds: [news], settings: { relevance: title, aliases.Federal_Reserve: "Fed, FOMC" } }
routes: { news: news }
```

```yaml
# the entity's document:  {"TOPIC_ID": "TOPIC-FED", "NAME": "Federal Reserve", "NEWS_ID": "NEWS-Federal_Reserve", ...}
# its Sutra:
  - id: headlines
    kind: table
    source: "link($.NEWS_ID, 'news')"
    rows: $.ITEMS
    columns:
      - { label: Published, bind: "@.PUBLISHED" }
      - { label: Source, bind: "@.SOURCE" }
      - { label: Headline, bind: "@.TITLE" }
      - { label: Link, bind: "@.LINK" }       # a web address in a cell is shown as a link that opens in a new tab
```

The shipped example is the `news-and-markets` pack: fifteen topics (central banks, countries, companies, themes) and twenty listed companies
with a quote, a price chart and these headlines; it is a sample pack, see [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#sample-packs).

**Offline.** In offline mode (`DRISHTI_OFFLINE=true`, [CONFIGURATION](../admin/CONFIGURATION.md#drishtioffline--offline-mode-no-internet))
this connector never starts, whatever its file says, and shows *off (offline mode)* in Admin → Connectors. A pack that must work without
internet keeps a snapshot of the documents in a store that serves the same kind: the `news-and-markets` pack's `news-store` serves
`news` from a snapshot captured through this connector (`drishti.py testdata capture-market`; [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#news-and-markets-and-world-economy-offline)).

## 9. How it is built

Everything is in `plugins/drishti-plugin-news/src/main/java/com/ash/drishti/plugin/news/`; the SPI stays Spring-free (the module depends
on `drishti-api`, Jackson and `drishti-common` for the shared retry policy).

| Class | Job |
|---|---|
| `NewsSourcePlugin` | the `SourcePlugin`: parses the id, asks the cache, builds the document, reports health and cache statistics |
| `NewsSettings` | every setting, validated once at start; nothing is a constant in the code |
| `HttpFetcher` | one bounded GET with the retry loop; reads at most `max-bytes` |
| `RssParser` | RSS to `NewsItem`s with XXE switched off |
| `NewsPipeline` | relevance, sources, order, duplicates, limit, each reporting its counts |
| `BoundedCache` | the least-recently-used map with a fixed capacity |

The pattern, in the developer guide's words: nothing is stored, so `fetch` does the work; it must be safe to call from many virtual
threads at once (the cache has one lock held only for map operations, and a call in flight is shared); it must never hold a thread longer
than its timeout; and it reports through `health()` and `cacheStats()`. See
[Connectors that compute data on demand](CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand).

## 10. Tests

No test calls Google. `FakeNewsServer` is a local HTTP server that serves canned RSS and, by the name asked for, a feed with an XXE
attempt, an oversized body, a slow response, two 503s then success, a 429 then success, a 403 and text that is not XML.
`NewsSourcePluginTest` (15 tests) checks the filtering, de-duplication, sorting and normalisation, the aliases, the lists, the request
shape, the cache (hits, TTL 0, the bound), each failure above, bad settings and the contract of an on-demand source (not dated, `UP`,
`lastUpdate`). `drishti-console/tests/test_news_browser.py` opens a company page and a topic page in Chromium over the real server with
the fakes behind it, and the same pages with the connectors off.
