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
# The news-watch pack (sample)

A sample pack: fifteen topics (central banks, countries, companies and themes), and for each the news about it, **computed when you open the page**
by the [news connector](../../../../docs/connectors/NEWS.md) from Google News. The topic list is static reference data in `samples/news-topic/`
(written by `tools/make_samples.py`); the headlines are live.

!!! warning "Off until you turn it on, and names leave your network when you do"
    The connector is disabled as shipped. Turn it on with `DRISHTI_NEWS_ENABLED=true` (or Admin, Connectors, `news`). From then on the name of every topic
    you open is sent to `news.google.com`. Pages that read the headlines also need `drishti.graph.link-budget` above its 40 ms default
    (for example `2s`), otherwise the first view of a topic shows its headline panels as pending and the next one (within ten minutes) shows them.

## Kinds

| Mnemonic | Opens | Identifiers |
|---|---|---|
| `TOPIC` | a topic: its page shows the headlines | `TOPIC-FED`, `TOPIC-ECB`, `TOPIC-BOE`, `TOPIC-BOJ`, `TOPIC-INFLATION`, `TOPIC-RATES`, `TOPIC-OIL`, `TOPIC-BITCOIN`, `TOPIC-AI`, `TOPIC-CLIMATE`, `TOPIC-INDIA`, `TOPIC-GERMANY`, `TOPIC-BRAZIL`, `TOPIC-DEUTSCHE-BANK`, `TOPIC-TOYOTA` |
| `NEWS` | the headlines for any name, not only the topics | `NEWS-<name with underscores>`, for example `NEWS-Bitcoin` |

## Finding things

| Command | Does |
|---|---|
| `TOPIC TOPIC-FED <GO>` | Opens the Federal Reserve topic. |
| `TOPIC fed <GO>` | A pick list by name or alias: *Fed*, *FOMC* and *Powell* all find the Federal Reserve. |
| `TOPIC category=Country <GO>` | Lists the country topics. |
| `FORM news-watch <GO>` | The search form: id, name, other names, category, edition. |
| `NEWS NEWS-Bitcoin <GO>` | The headlines for any name; underscores stand for spaces. |

## What you will see

A topic page has the headlines newest first (published, source, headline, and the article's address as a link that opens in a new tab), a bar per
source, **how the headlines were chosen** (the relevance, duplicates and limit steps and how many each dropped), and **everything the feed returned**
for the name with no relevance filter, so the two can be compared. A topic with an edition (`TOPIC-DEUTSCHE-BANK`: `de-DE`, `TOPIC-TOYOTA`: `ja-JP`)
reads that country's Google News. Where a topic has other names, the relevance filter counts them (the connector template carries them as
`aliases.*`, written from the same list as the samples).

While the connector is off the headline panels are empty and say `waiting for NEWS-...`; the page's own note says how to turn it on.

## Changing it

- **Topics:** edit the list in `tools/make_samples.py` and run `python3 config/packs/news-watch/tools/make_samples.py`; it rewrites `samples/` and the
  alias block of the connector template in `pack.yaml`. The connector file `config/connectors/news.yaml` is written once from the template and is yours
  after that; add `aliases.<Name_with_underscores>` lines to it for new topics.
- **Filters:** `relevance`, `allow-sources`, `deny-sources`, `max-items`, `cache-ttl-seconds` and the rest in the connector file; see [NEWS.md](../../../../docs/connectors/NEWS.md#6-settings).
- **Another pack's entity:** give it a field holding `NEWS-<name>` and a panel with `source: "link($.newsId, 'news')"`; the `market-watch` pack does so for companies.

## Who can see it

Role `news-analyst`: kinds `news-topic` and `news`.

This is a sample pack: it is hidden from business users when `drishti.packs.samples` is `developers`, and it demonstrates a connector that
is code rather than a store. See [Domain packs](../../../../docs/guides/PACKS.md).
