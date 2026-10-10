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
# The REST connector: one HTTP GET per entity

The `rest` connector reads entities from an HTTP service that already answers one entity as JSON per GET: an
onboarding system's counterparty API, a positions service, a limits service. Drishti copies nothing and keeps nothing:
each time a view needs the entity, the connector sends one request and the answer is the document. This document
explains how a read becomes a request, how answers are interpreted, what each read path costs the service, how to
pair the connector with a dated store for history, what happens when the service fails, how to secure it, and every
setting exactly as the plugin (`plugins/drishti-plugin-rest`, `RestSourcePlugin`) reads it.

For a first setup see [the walk-through at the end of this document](#walk-through-step-by-step); the plugin
reference is in [the configuration examples at the end of this document](#configuration-by-example) and the settings summary in
[CONFIGURATION.md](../admin/CONFIGURATION.md#rest--an-httpjson-service). For stores that keep history see
[DELTA_CONNECTOR.md](DELTA_CONNECTOR.md), [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md) and
[FILE_CONNECTOR.md](FILE_CONNECTOR.md).

## Contents

1. [When to use it](#1-when-to-use-it)
2. [How a read becomes a request](#2-how-a-read-becomes-a-request)
3. [What the service must answer](#3-what-the-service-must-answer)
4. [Configuration](#4-configuration)
5. [Read paths and what each costs](#5-read-paths-and-what-each-costs)
6. [Dates, and pairing with a dated store](#6-dates-and-pairing-with-a-dated-store)
7. [Scale and limits](#7-scale-and-limits)
8. [Timeouts](#8-timeouts)
9. [Failure and recovery](#9-failure-and-recovery)
10. [Security](#10-security)
11. [Diagnosing](#11-diagnosing)
12. [Settings](#12-settings)
13. [Checklist for production](#13-checklist-for-production)

---

## 1. When to use it

| Use it for | Prefer another store for |
|---|---|
| an owning system that already has a per-entity JSON API, and you want its current answer, not a copy | history: a picked business date (Delta Lake, PostgreSQL table mode, files) |
| reference or account data opened one entity at a time (a counterparty, a client, a limit) | kinds users search for, type ahead on, or filter (`TRD where …`): the connector cannot search |
| a first integration before a feed or a database load exists | links back to an entity (*Linked entities*, impact with F8): it has no reverse lookups |
| data whose owner does not allow copies | anything that must tick live: it has no subscriptions |
| a small number of views a minute | a million entities a day listed, aggregated or scanned: it reads one entity per request |

The connector is **undated** and **fetch only**: its manifest declares `SourceCapabilities.FETCH_ONLY` (no live
updates, no reverse lookups, no search, not dated). The router therefore calls only `fetch(ref)` on it; every other
question about its kinds must be answered by some other connector, or goes unanswered.

## 2. How a read becomes a request

For a read of `<kind>/<id>` the connector builds one URL and sends one `GET`:

```
<base-url> + <path>, with {kind} and {id} replaced by the URL-encoded kind and id
```

| Step | Exactly what the code does |
|---|---|
| base URL | `base-url` with every trailing `/` removed (`https://crm.bank.example/api/` becomes `https://crm.bank.example/api`) |
| path | `path` (default `/{kind}/{id}`), with each `{kind}` and `{id}` replaced; the rest of `path` is used as written, so it may hold a fixed query string (`/lookup?type={kind}&key={id}`) |
| encoding | `URLEncoder.encode(value, UTF-8)` with `+` turned into `%20`: a space becomes `%20`, `/` becomes `%2F`, `+` becomes `%2B`, `:` becomes `%3A`, `&` becomes `%26` |
| method and headers | `GET`, `Accept: application/json`, then every `header.<Name>` setting as a request header `<Name>` |
| timeouts | the client's connect timeout and the request timeout are both `timeout-ms` (2000) |
| client | one JDK `HttpClient` per connector, built at start and shared by every concurrent read (thread-safe, used from many virtual threads); its connections are kept alive and reused between requests |

Examples, with `base-url: https://crm.bank.example/api`:

| `path` | Entity | Request |
|---|---|---|
| `/{kind}/{id}` (default) | `counterparty/CP-HARBOURVIEW` | `GET https://crm.bank.example/api/counterparty/CP-HARBOURVIEW` |
| `/{kind}/{id}` | `trade/T 1` | `GET https://crm.bank.example/api/trade/T%201` (from the plugin's test) |
| `/v2/{kind}s/{id}` | `position/POS-77` | `GET https://crm.bank.example/api/v2/positions/POS-77` |
| `/{kind}/{id}.json` | `counterparty/CP-HARBOURVIEW` | `GET https://crm.bank.example/api/counterparty/CP-HARBOURVIEW.json` (a static file server) |
| `/lookup?type={kind}&key={id}` | `book/BOOK RATES/3` | `GET https://crm.bank.example/api/lookup?type=book&key=BOOK%20RATES%2F3` |

Because `/` in an id is encoded as `%2F`, a service whose ids contain slashes must accept the encoded form; many
servers and proxies reject or decode `%2F` in a path by default. Put such ids in the query string instead.

## 3. What the service must answer

### 3.1 Status codes

| Answer | Becomes |
|---|---|
| `404` | *not held here*: the next connector for the kind is asked; nothing is logged as an error (`reads.notHeld` counts it) |
| `400` or above, other than `404` (`401`, `403`, `429`, `500`, `503` …) | a failure: `<source-name> answered HTTP <status> for <kind>/<id>`; the read stops with `DRS-1003 <connector> failed reading <kind>/<id>` and is **not** passed to the next connector |
| below `400` (`200`, also `201`, `203`, `204`, and `3xx`) | the body is parsed as JSON and becomes the document |
| a connection refused, a TLS failure, a DNS failure, a timeout | an exception from the client: `DRS-1003` as above |
| a body that is not JSON | the parser's exception: `DRS-1003` |

Two consequences of the code worth knowing:

- **Redirects are not followed.** The client is built without a redirect policy, which in the JDK means *never*.
  A `301`/`302` is below `400`, so its body (often HTML, or empty) is parsed as the document: HTML fails as
  `DRS-1003`, an empty body yields an empty document (the view shows *No data available*). Point `base-url` at the
  final address, not at one that redirects (for example `http://` to `https://`).
- **An empty body is not "not held".** `200` or `204` with no body parses to an empty document and is *found*. A
  service that has nothing for an id must answer `404`.

The body may be any JSON value; services return an object. It is streamed into Drishti's document tree without an
intermediate copy.

### 3.2 The generation

Each document carries a generation (newer data has a higher number). The connector reads the response header named
by `generation-header` (default `ETag`), removes every `"`, and uses it if what remains is only digits:

| Response header | Generation |
|---|---|
| `ETag: "77"` | `77` (the plugin's test) |
| `X-Version: 42` with `generation-header: X-Version` | `42` |
| `ETag: W/"77"` (a weak ETag) | not digits (`W/77`): the fetch time in milliseconds |
| `ETag: "a1b2c3"`, or no such header | the fetch time in milliseconds |

Give the service a numeric version header if it has one (a row version, a sequence number). Without it every read is
stamped with the time it was fetched, so two reads of an unchanged entity carry different generations.

### 3.3 A complete exchange

```http
GET /api/counterparty/CP-HARBOURVIEW HTTP/1.1
Host: crm.bank.example
Accept: application/json
Authorization: Bearer eyJ…

HTTP/1.1 200 OK
Content-Type: application/json
X-Version: 42

{"counterpartyId": "CP-HARBOURVIEW", "name": "Harbourview Capital LLP", "lei": "5493001KJTIIGC8Y1R12",
 "rating": "A", "sector": "Asset management", "country": "GB", "type": "Fund manager",
 "nettingSets": ["NS-HARBOURVIEW-ISDA"], "parentId": "CP-HARBOURVIEW-HOLDINGS"}
```

What Drishti records (`GET /api/v1/entities/counterparty/CP-HARBOURVIEW/raw`, connector `crm-api`):

```json
{"ref": {"kind": "counterparty", "id": "CP-HARBOURVIEW"},
 "provenance": {"source": "crm-api", "generation": 42, "fetchedAt": "2026-10-01T09:14:03.118Z", "live": false,
                "businessDate": null},
 "data": {"counterpartyId": "CP-HARBOURVIEW", "name": "Harbourview Capital LLP", "…": "…"}}
```

`live` is always `false` and `businessDate` always `null`: the document is the service's current answer.

## 4. Configuration

### 4.1 Site form

The connector file `config/connectors/crm-api.yaml` (see [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtisources--where-data-comes-from) for the server-wide
`drishti.sources.fetch-timeout` and routes):

```yaml
plugin: rest
kinds: [counterparty]                            # only counterparties are asked of this service
settings:
  base-url: ${CRM_URL:https://crm.bank.example/api}   # required; trailing slashes are dropped
  path: /{kind}/{id}                             # {kind} and {id} are URL-encoded
  timeout-ms: '2500'                             # connect timeout and request timeout
  generation-header: X-Version                   # a numeric response header (default ETag)
  header.Authorization: Bearer ${CRM_TOKEN}      # any request header: header.<Name>
  header.X-Client: drishti
```

and the server settings that bear on it, in `application.yaml`: `drishti.sources.fetch-timeout: 3s` (the whole read, every connector tried; default 2s) and
`drishti.sources.routes.counterparty: crm-api` (Live reads ask the service first, section 6). A header name is written as it is in a file, with any characters
(`header.X_Client`); no bracketing is needed.

### 4.2 Pack form

The pack's suggested template, in `config/packs/<pack>/pack.yaml` (keys with dots are written flat); the server writes it to `config/connectors/crm-api.yaml` at the first start
if the site has none:

```yaml
connectors:
  crm-api:
    plugin: rest
    kinds: [counterparty]
    settings:
      base-url: ${CRM_URL:http://localhost:9000/api}
      path: /{kind}/{id}
      timeout-ms: 2500
      generation-header: X-Version
      header.Authorization: Bearer ${CRM_TOKEN:}
routes:
  counterparty: crm-api
```

A site changes the connector by editing its file, which holds the whole definition (the pack's template is not merged into it); a `kinds:` list in the file replaces the pack's list.

### 4.3 The plugin as itself

The shipped `application.yaml` lists the plugin as itself, off:

```yaml
drishti:
  sources:
    plugins:
      rest:                         # an HTTP/JSON service; off until configured
        enabled: ${DRISHTI_REST_ENABLED:false}
        settings:
          base-url: ${DRISHTI_REST_URL:http://localhost:9000/api}
          path: /{kind}/{id}
          source-name: rest
          timeout-ms: 2000
```

`DRISHTI_REST_ENABLED=true` (with `DRISHTI_REST_URL`) starts it under the name `rest`, serving **every** kind unless
`settings.kinds` is set. Prefer named connectors: each has its own name in health, routes and provenance, and you can
run one per service.

### 4.4 Which kinds a connector serves

| Configured | Served |
|---|---|
| connector `kinds: [counterparty]` | `counterparty` only (the connector's list wins over the setting) |
| no connector `kinds`, `settings.kinds: counterparty,client` | those two |
| neither | **every kind**: the connector is a candidate for every read in the server |

A connector that serves every kind is asked whenever the connectors before it do not hold an entity, of any kind: each
such miss is a request to the service (answered `404` at best). And if the service answers anything else for a kind it
does not know (`400`, `500`), that read fails with `DRS-1003` instead of reaching the next connector. **Always give a
REST connector its kinds.**

## 5. Read paths and what each costs

| Read path | Supported | Cost on the service |
|---|---|---|
| open one entity (`CPTY CP-HARBOURVIEW <GO>`, `GET /api/v1/views/{kind}/{id}`) | yes | one `GET` per open, every time; the connector caches nothing |
| the entity's document (`/raw`) | yes | one `GET` |
| linked entities shown in a view (a trade's counterparty, a counterparty's parent) | yes, each through the router | one `GET` per linked entity of a REST-served kind, in parallel, within `drishti.graph.link-budget` (40 ms) before the link shows as *pending* |
| type-ahead under the command line | no (`search: false`) | none; the id is not offered: users type it whole, or another connector (Delta Lake, files) lists the kind's ids |
| structured search (`CPTY where rating = 'A'`), pick lists | no | none; answered only by connectors that hold the kind's columns |
| a picked business date | no (undated) | tried after every dated connector; when it answers, the view says *No data held for 2026-09-29: the current data of crm-api, a source that keeps no dates* |
| reverse lookups (*Linked entities*, impact F8) | no | none. Other connectors may still answer "what refers to `CP-HARBOURVIEW`" (trades in a lake that name it) |
| live push | no | none; views of REST-served entities do not tick (unless a live connector pushes the kind, as a Kafka connector in `ticks` mode does) |
| derived kinds over a REST kind | members are listed by search, which this connector cannot do | the members' documents can be read from the service only if another connector lists their ids |

Opening one view can therefore cost several requests: the entity, plus each linked entity of a kind the connector
serves. Reads for the same entity are not merged: ten users opening the same counterparty send ten requests.

## 6. Dates, and pairing with a dated store

The router (`SourceRouter`) builds a kind's candidates from its route, then the default route (`demo`), then every
other connector that serves it, and re-orders them:

- **For a picked date**, dated connectors first: a lake or table that holds the date answers, and the service is asked
  only when no dated store holds the entity.
- **For Live**, live connectors first (the demo samples, a stream); among the rest the order above is kept, so **the
  route decides** whether the service or a dated store answers Live.

A common arrangement: the service is the truth for *now*, a nightly copy in a lake is the history.

```yaml
drishti:
  sources:
    connectors:
      crm-api:
        plugin: rest
        kinds: [counterparty]
        settings: { base-url: "${CRM_URL}", header.Authorization: "Bearer ${CRM_TOKEN}" }
      reference-store:                                 # the banking-core pack's Delta Lake, dated
        settings: { root: /srv/lake }
    routes:
      counterparty: crm-api
```

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live | `demo` (live) → `crm-api` (the route) → `reference-store` | the samples if they hold the id, else the service; a `404` falls through to the lake's newest date |
| a picked date | `reference-store` (dated) → `crm-api` → `demo` | the lake, for that date |
| a date the lake does not hold | as above | the service's current answer, with the *No data held for <date>* banner |

With `routes: { counterparty: reference-store }` instead, Live reads come from the lake's newest date and the service
is asked only for counterparties the lake does not hold. Switch the samples off (`DRISHTI_DEMO_ENABLED=false`) when
the service holds the same ids as the samples.

## 7. Scale and limits

- **Throughput is the service's.** Every view open, and every linked entity of the kind, is a request. Drishti adds
  the request time to the view: a service answering in 30 ms adds 30 ms to the view; one answering in 3 s exceeds
  the default `fetch-timeout` (2 s). Size the service for the views users open, not for the size of the book.
- **No bulk path.** There is no listing, no batch request and no column read. A million entities a day can be
  *opened* one at a time through this connector, but never searched, typed ahead, listed, aggregated or traced for
  impact through it. For those, load the same data into a dated store ([DELTA_CONNECTOR.md](DELTA_CONNECTOR.md),
  [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md), [FILE_CONNECTOR.md](FILE_CONNECTOR.md)) and keep the service for
  the current answer (section 6).
- **No cache, no memory.** The connector holds a client and its settings; nothing grows with the number of entities.
  Nothing it serves is kept on disk.
- **Concurrency.** Reads run on virtual threads and share one HTTP client; the plugin sets no limit on requests in
  flight, so a burst of views is a burst of requests. Rate limits belong in front of the service (a gateway); a
  `429` from it is a failed read (`DRS-1003`), not a retry.
- **No retries.** A failed request is not repeated; the user sees the failure and opening the view again sends a new
  request.
- **Undated.** No history, no *known at*.
- **One URL pattern per connector.** Services with different paths for different kinds need one connector each
  (`path` holds a single pattern; `{kind}` is the only per-kind part).

## 8. Timeouts

Two timeouts apply to every read, and the shorter wins:

| Timeout | Default | Bounds |
|---|---|---|
| `timeout-ms` (this connector) | `2000` | the connection set-up, and the time until the response arrives; past it the client throws and the read fails with `DRS-1003` |
| `drishti.sources.fetch-timeout` (the server) | `2s` | the **whole** read, across every connector tried in turn; past it the view ends with `DRS-1004 timed out reading <kind>/<id>` |

Keep `timeout-ms` below `fetch-timeout`, with room for the connectors tried before this one: the user then sees which
connector failed (`DRS-1003 crm-api failed reading …`) instead of a bare timeout. To allow a service 4 s, raise both:

```yaml
drishti:
  sources:
    fetch-timeout: 5s
    connectors:
      crm-api:
        settings: { timeout-ms: 4000 }
```

When `fetch-timeout` ends a read first, the HTTP request itself is not cancelled; it finishes or reaches `timeout-ms`
in the background.

## 9. Failure and recovery

The plugin keeps nothing long-lived except the HTTP client, so there is nothing to reconnect: each request connects
(or reuses a kept-alive connection) afresh.

| Health text (exactly as the code returns it) | When |
|---|---|
| `UP` | the connector has started |
| `DOWN: not started` | before `start` has run |

A service that is down **does not change health**: it shows as failed reads. Views say
`DRS-1003 crm-api failed reading counterparty/CP-HARBOURVIEW`, and `GET /api/v1/admin/health` counts them under the
connector's `reads`:

```json
{"name": "crm-api", "status": "UP", "health": "UP", "kinds": ["counterparty"], "live": false, "dated": false,
 "search": false,
 "reads": {"reads": 12, "found": 7, "notHeld": 1, "errors": 4,
           "lastError": "IOException: crm-api answered HTTP 503 for counterparty/CP-HARBOURVIEW", "…": "…"}}
```

`lastError` is the exception's class name and message. Watch `reads.errors`, `lastError` and `lastErrorAt` (or alert on them) to see an outage. When the service comes back,
the next read works; there is no restart and no state to rebuild.

At start, an empty `base-url` stops the connector: the log says `rest plugin needs settings.base-url` and
`GET /api/v1/admin/health` lists it under `failedToStart`; the server runs on without it. A `timeout-ms` that is not a
whole number also fails the start (`NumberFormatException`). The service is not contacted at start, so a service that
is down does not stop the connector from starting.

## 10. Security

- **Credentials** go in request headers: `header.Authorization: "Bearer ${CRM_TOKEN}"`, `header.X-Api-Key:
  "${CRM_KEY}"`. Keep the value in the environment, never in a YAML file. A placeholder whose variable is not set is
  sent as written (`Bearer ${CRM_TOKEN}`), and the service answers `401`; use `${CRM_TOKEN:}` only where an empty
  value is acceptable. Headers are fixed at start: a token that expires needs a restart (or a long-lived service
  token). There is no OAuth client flow in the plugin.
- **Settings are not shown** by health or `/api/v1/sources`.
- **TLS.** An `https://` base URL is checked against the JVM's authorities and the host name, unless the connector's own
  `tls.*` settings say otherwise ([TLS.md](TLS.md)): `tls.ca-file` or `tls.truststore` for a service on a private CA,
  `tls.cert-file` + `tls.key-file` (or `tls.keystore`) when the service asks for a client certificate, `tls.verify-hostname:
  false` to check the chain but not the name. Each connector has its own, so two services can use two CAs. `tls.*` against an
  `http://` URL is a start-up error, never a silent downgrade. The details are in [the TLS section below](#tls-for-the-rest-connector).
  The `-Djavax.net.ssl.*` JVM properties still apply when the connector has no `tls.*` of its own.
- **Proxies.** The client uses the JVM's default proxy selector: `-Dhttps.proxyHost`, `-Dhttps.proxyPort`,
  `-Dhttp.nonProxyHosts`.
- **What leaves the server.** The kind and id of every entity a user opens (and every linked entity of the kind) are
  sent to the service in the URL. Entitlements and redaction are applied by the Drishti server to what it shows; the
  service sees the requests Drishti makes on users' behalf, with the connector's credentials, not the user's.
- **Ids in URLs** are encoded, so an id cannot change the path or add query parameters.

### TLS for the REST connector

| Setting | Meaning |
|---|---|
| `base-url: https://host/api` | TLS on, checked against the JVM's authorities and the host name. |
| `tls.ca-file` / `tls.truststore` (+ `-password`) | Trust a private CA instead (add `tls.trust-jvm-default: true` to also keep the public ones). |
| `tls.cert-file` + `tls.key-file` (+ `tls.key-password`), or `tls.keystore` (+ `tls.keystore-password`) | The client certificate for mutual TLS. |
| `tls.verify-hostname: false` | The chain is verified, the name is not (a warning at every start). |
| `tls.protocols`, `tls.cipher-suites` | Handed to the HTTP client. |
| `tls.insecure-trust-all` | Development only; refused unless `DRISHTI_ALLOW_INSECURE_TLS=true`. |

A complete file, mutual TLS to a service on a private CA (`config/connectors/rest-mtls.yaml`):

```yaml
plugin: rest
kinds: [price]
description: Prices from the pricing API over mutual TLS
settings:
  base-url: https://pricing.example.com/api
  header.Authorization: Bearer ${PRICING_TOKEN}
  tls:
    ca-file: /etc/drishti/tls/ca.pem
    cert-file: /etc/drishti/tls/client.pem
    key-file: /etc/drishti/tls/client.key
```

Check the service the way the connector will meet it:

```
curl --cacert /etc/drishti/tls/ca.pem --cert /etc/drishti/tls/client.pem --key /etc/drishti/tls/client.key https://pricing.example.com/api/price/EURUSD
openssl s_client -connect pricing.example.com:443 -CAfile /etc/drishti/tls/ca.pem -verify_return_error < /dev/null
```

While the connector is up, health reads `UP`; once a certificate has under 30 days left it reads
`UP (TLS certificate CN=drishti (tls.cert-file) expires in 19 days (2026-10-30))`.

Errors, captured from the connector's test against an HTTPS server that requires a client certificate:

| Message | Cause and fix |
|---|---|
| `tls.enabled is true but base-url is not https:// (http://h:80/api)` | Use an `https://` URL. |
| `tls.* is set but base-url is not https:// (http://h:80/api): use an https:// address` | `tls.*` keys with an `http://` URL. |
| `tls.ca-file '/etc/drishti/nope.pem': file not found` | A wrong path; the message names the setting and the file. |
| `javax.net.ssl.SSLHandshakeException: (certificate_unknown) PKIX path building failed: ... unable to find valid certification path to requested target` | The service certificate is not signed by a CA in `tls.ca-file`. |
| `java.io.IOException: HTTP/1.1 header parser received no bytes`, caused by `java.net.SocketException: Connection reset` | The service closed the connection: typically it requires a client certificate and none was configured. |
| `No subject alternative names matching IP address 127.0.0.2 found` | The URL's host is not one the certificate names. |

## 11. Diagnosing

| Symptom | Likely cause | What to do |
|---|---|---|
| `failedToStart: {"crm-api": "rest plugin needs settings.base-url"}` | `base-url` empty or unset | set it (`DRISHTI_REST_URL` for the plugin as itself) |
| `DRS-1003 crm-api failed reading …`, `lastError` `IOException: crm-api answered HTTP 401 …` / `403` | token missing, expired or a placeholder sent as text | check the header value in the server's environment; restart after changing it |
| `lastError` `… answered HTTP 400 …` for kinds the service does not know | the connector has no `kinds` and is asked for every kind | give it `kinds:` |
| `DRS-1003`, `lastError` names `HttpConnectTimeoutException`, `ConnectException`, `UnknownHostException` | the service is unreachable from the server | `curl -i` the URL from the server host; check proxies and firewall |
| `DRS-1003`, `lastError` names `SSLHandshakeException` / `PKIX path building failed` | the service's certificate is not trusted by the JVM | add its CA to the trust store (section 10) |
| `DRS-1003`, `lastError` names a JSON parse error | the service answered HTML (a login page, a redirect, an error page) | `curl -i` the built URL; set `base-url` to the final address |
| the view shows *No data available* | the service answered `2xx`/`3xx` with an empty body | make the service answer `404` for unknown ids |
| `DRS-1004 timed out reading …` | the service is slower than `fetch-timeout` | raise `fetch-timeout` and `timeout-ms` together (section 8), or route the kind to a faster store |
| the view always shows the lake's data, never the service's | the kind's route names the lake | route the kind to the REST connector (section 6) |
| the view shows a sample, not the service | the demo samples are live and hold the same id | `DRISHTI_DEMO_ENABLED=false` |
| type-ahead does not offer the id | the connector cannot search | type the whole id, or load the kind's ids into a dated store |
| *No data held for <date>* (the current data of crm-api) on a picked date | no dated store holds the entity for that date | expected; load history into a dated store if it is needed |
| the same entity shows a new generation on every read | no numeric version header | set `generation-header` to the service's version header |
| `%2F` ids give `404` or `400` | the service or a proxy refuses encoded slashes in paths | put the id in the query string (`path: /lookup?key={id}`) |

To see the request the connector sends, build it by hand: `<base-url without trailing slashes><path with {kind} and
{id} encoded>`, then `curl -i -H 'Accept: application/json' -H 'Authorization: Bearer …' <url>`.

## As a connector file

A connector is a site resource: one YAML file in `config/connectors/`, and the file name is the connector's name. The settings of this document go under `settings:` in that file, with nesting flattened to dotted keys (`layout: {trade: {columns: [...]}}` is `layout.trade.columns`); `${ENV_VAR}` placeholders are resolved when the connector starts, and a credential is only ever an `${ENV_VAR}` or a `file:/path` reference. A pack names the connectors it reads through and may suggest a template; the server writes the template to the file once, at the first start, and the file is then the site's. A complete file:

```yaml
# config/connectors/pricing-api.yaml
plugin: rest
kinds: [price]
description: Prices from the pricing service
settings:
  base-url: https://pricing.example.com/api
  path: /{kind}/{id}
  timeout-ms: '1500'
  header.Authorization: Bearer ${PRICING_TOKEN}
  tls:
    enabled: true
    truststore:
      path: /etc/drishti/tls/ca.p12
      password: ${PRICING_TRUSTSTORE_PASSWORD}
```

The file is applied to the running server within seconds, without a restart, and is edited in the editor of your choice, in **Admin → Connectors** (a form generated from this document's settings, a YAML tab, **Test connection**, history) or with `drishti.py connector apply`. The folder, the format, live reload, precedence and the deprecated `drishti.sources.connectors` form are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md). The same over TLS is `config/connectors.examples/other/pricing-api.yaml`.

## 12. Settings

In the connector file's `settings:`, or for the plugin as itself
(`drishti.sources.plugins.rest.settings`):

| Setting | Default | Meaning |
|---|---|---|
| `base-url` | none (required) | the service root; trailing `/` are removed. Empty: the connector fails to start |
| `path` | `/{kind}/{id}` | appended to `base-url`; `{kind}` and `{id}` are replaced by the URL-encoded kind and id |
| `kinds` | empty (any kind) | comma list of kinds served; a connector's own `kinds:` list takes precedence |
| `source-name` | `rest` (a named connector: its name) | the name in provenance and the error text |
| `timeout-ms` | `2000` | connect timeout and request timeout, in milliseconds |
| `generation-header` | `ETag` | response header holding a numeric version (quotes removed); otherwise the fetch time in milliseconds |
| `header.<Name>` | none | a request header `<Name>` sent with every request |

Server settings that bear on it: `drishti.sources.fetch-timeout` (2 s), `drishti.sources.routes.<kind>`,
`drishti.graph.link-budget` (40 ms). Shipped environment variables for the plugin as itself: `DRISHTI_REST_ENABLED`
(`false`), `DRISHTI_REST_URL` (`http://localhost:9000/api`).

## 13. Checklist for production

1. Run it as a named connector with `kinds:` listing exactly the kinds the service holds.
2. Make the service answer `404` for an unknown id, `200` with a JSON object for a known one, and no redirects at the
   configured address.
3. Give it a numeric version header and name it in `generation-header`.
4. Put the token in the environment (`header.Authorization: "Bearer ${…}"`), with no default; check the first read
   succeeds after each deploy.
5. For `https`, put the service's CA in the server's trust store; for mutual TLS, set the key store.
6. Set `timeout-ms` below `drishti.sources.fetch-timeout`, leaving room for connectors tried before it.
7. Decide who answers Live and who answers picked dates (section 6), set the route, and switch the samples off.
8. If users need type-ahead, search, history or impact for the kind, load it into a dated store as well.
9. Check that the service can carry one request per view open plus one per linked entity, at your busiest hour.
10. Alert on the connector's `reads.errors` and `lastErrorAt`: its health stays `UP` while the service is down.

---

## Appendix: walk-through and worked examples

Moved here from the former connector guides, so that everything about this connector is in one document.

### Walk-through, step by step

This walk-through puts the connector to work on a small case; the sections above are the reference.

#### The situation

Your client-onboarding system has an API that returns one counterparty as JSON:
`GET https://crm.bank.example/api/counterparty/CP-HARBOURVIEW`. You want those counterparties in Drishti
(`CPTY`, from `banking-core`) without copying them anywhere.

#### Configure it

The pack's suggested template (the connector file is in [4.1](#41-site-form)):

```yaml
connectors:
  crm-api:
    plugin: rest
    kinds: [counterparty]
    settings:
      base-url: ${CRM_URL:http://localhost:9000/api}
      path: /{kind}/{id}
      timeout-ms: 3000
      header.Authorization: Bearer ${CRM_TOKEN:}       # flat key with a dot: write it this way in a pack.yaml template
```

#### Try it

Any static file server will do as a stand-in for the API:

```bash
mkdir -p /tmp/crm/api/counterparty
echo '{"counterpartyId": "CP-HARBOURVIEW", "name": "Harbourview Capital LLP", "rating": "A", "country": "GB"}' \
  > /tmp/crm/api/counterparty/CP-HARBOURVIEW.json
(cd /tmp/crm && python3 -m http.server 9000)
```

```bash
curl -s http://localhost:18480/api/v1/entities/counterparty/CP-HARBOURVIEW/raw | jq -c '{provenance, data}'
```

You should see `"source":"crm-api"`, `"live":false`, `"businessDate":null` and the document.

#### Health, and when the service goes down

A service that is down does not change health; it shows as failed reads (`DRS-1003 crm-api failed reading counterparty/CP-HARBOURVIEW`). See [Failure and recovery](#9-failure-and-recovery) and [Diagnosing](#11-diagnosing) for the symptoms and fixes.

### Configuration by example

**What it is for.** An in-house HTTP service that answers one entity as JSON per GET. Undated and fetch-only (no
search, no live updates); use it when the owning system already has an API.

**Configuration.**

```yaml
# application.local.yaml
drishti:
  sources:
    plugins:
      rest:
        enabled: true
        settings:
          base-url: https://positions.bank.example/api      # required; trailing slashes are dropped
          path: /v2/{kind}s/{id}                            # {kind} and {id} are URL-encoded and substituted
          kinds: position,limit                             # serve only these kinds (empty: any kind)
          source-name: positions-api
          timeout-ms: 3000                                  # connect and request timeout
          generation-header: X-Version                      # a numeric response header used as the generation
          header.Authorization: "Bearer ${POSITIONS_TOKEN}" # any request header: header.<Name>
    routes:
      position: rest                                        # running as itself, its route name is "rest"
```

As a pack connector (for example, two services):

```yaml
connectors:
  positions-api:
    plugin: rest
    kinds: [position]
    settings: { base-url: "${POSITIONS_URL:http://localhost:9000/api}", path: "/{kind}/{id}" }
routes:
  position: positions-api
```

**Settings.** See [Settings](#12-settings).

**The data.** `GET https://positions.bank.example/api/v2/positions/POS-77` with `Accept: application/json`; how each status is treated is in [Status codes](#31-status-codes).

**Try it.** Any static server works: lay out `api/position/POS-77.json` and run
`python3 -m http.server 9000` in the parent folder, with `base-url: http://localhost:9000/api` and
`path: /{kind}/{id}.json`, and a mnemonic for `position` in a pack.

**What the user sees.** `<mnemonic> POS-77 <GO>`; provenance `positions-api`, not live, no business date. Health is described in [Failure and recovery](#9-failure-and-recovery).
