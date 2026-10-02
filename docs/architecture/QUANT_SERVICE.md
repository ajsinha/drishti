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
# Server-side pricing: the quant service

**Status: proposed, on the roadmap ([IMPLEMENTATION_PLAN.md › Roadmap](IMPLEMENTATION_PLAN.md#roadmap)); the decision is
[ADR-019](adr/019-pricing-is-a-quantlib-service-behind-the-server.md).** Nothing here is built yet.

Drishti shows trades, market data and risk, and Calc ([PYTHON_CALC.md](../guides/PYTHON_CALC.md)) lets a user compute
on them in the browser with numpy, pandas and the small `drishti.quant` library. What Calc cannot do well is
*proper* pricing: building curves from instruments, pricing swaptions or callable bonds, calibrating models. That
needs QuantLib, and QuantLib belongs on a server, not in a browser. This document describes how a pricing service
works with Drishti, how it reaches the screen, and how it would be built.

## Contents

1. [What it is for](#1-what-it-is-for)
2. [The shape](#2-the-shape)
3. [Why a separate service](#3-why-a-separate-service)
4. [What the Drishti server does](#4-what-the-drishti-server-does)
5. [What the quant service does](#5-what-the-quant-service-does)
6. [Packs declare how products are priced](#6-packs-declare-how-products-are-priced)
7. [Market data for a business date](#7-market-data-for-a-business-date)
8. [How it reaches the screen](#8-how-it-reaches-the-screen)
9. [The API](#9-the-api)
10. [Security](#10-security)
11. [Caching and performance](#11-caching-and-performance)
12. [Failure and operations](#12-failure-and-operations)
13. [Testing](#13-testing)
14. [Phases](#14-phases)
15. [Risks and open questions](#15-risks-and-open-questions)

---

## 1. What it is for

| Question | Today | With the service |
|---|---|---|
| What is this swap worth on the curve of that day? | the booked MTM from the source system | an independent model NPV next to the booked MTM, and the difference |
| What are its Greeks by tenor? | the booked DV01, sometimes by tenor | key-rate DV01, vega, gamma, computed consistently |
| What if rates rise 25 bp and vols rise 2 points? | a first-order estimate in Calc (DV01 × shift) | a full reprice under the scenario |
| What is the implied vol of this option, the par rate of this swap? | approximations in `drishti.quant` | QuantLib's calibrated answer |
| What is a book worth under a stress? | not available | an overnight or on-demand batch through the same service |

It is a **valuation and analysis** service for the people using Drishti, not a replacement for the bank's pricing
or risk systems of record: its numbers sit beside the booked ones, clearly labelled as Drishti's model values.

## 2. The shape

```
Browser (Calc, a Valuation panel, the what-if drawer)
   │  the user's session: same permissions, same redaction
   ▼
Console ──▶ Drishti server  /api/v1/quant/...
               │  checks the `quant` power and that the user may open the entity
               │  resolves market data for the business date from its own sources
               │  caps the size and time of a request, records it in the access log
               │  caches results by (trade version, market date, model, scenario)
               ▼
          drishti-quant (internal only, never reached by browsers)
               Python, QuantLib, a small HTTP API; stateless; one or many replicas
               maps the trade document to a QuantLib instrument through the pack's pricer mapping,
               builds the curves and surfaces it was given, prices, returns NPV, Greeks, cashflows
```

Every request a user makes goes through the Drishti server. The service never fetches data itself and never sees a
user's credentials; it is given a trade, market data and a model, and it returns numbers.

## 3. Why a separate service

| Option | For | Against | Verdict |
|---|---|---|---|
| **A separate Python service with QuantLib-Python** | QuantLib publishes ready-made wheels for Linux, macOS and Windows (QuantLib 1.43 today); a crash in native code cannot take the Drishti server down; it scales on its own; the same interface can front ORE or a bank's pricers later | one more process to run | **chosen** ([ADR-019](adr/019-pricing-is-a-quantlib-service-behind-the-server.md)) |
| QuantLib's Java (SWIG) bindings inside the server | one process | native libraries to build for every platform, and a crash or a long calibration inside the server everyone uses | rejected |
| QuantLib compiled to WebAssembly, in Calc | no server | no build exists; tens of MB more to download; a laptop's CPU for calibrations; rebuilt for every Pyodide version | rejected ([PYTHON_CALC.md](../guides/PYTHON_CALC.md)) |
| ORE (Open Source Risk Engine) as the service | portfolio analytics (XVA, simulation) | larger to adopt, XML-configured | later, behind the same API |

## 4. What the Drishti server does

A new module, `drishti-quant-gateway`, in the server:

- **The endpoint** `/api/v1/quant/...` ([section 9](#9-the-api)).
- **Permission:** a new role power, `quant` (like `calc` and `layout`), granted by default to trader, risk, author
  and admin roles, not to viewers; and, as for every read, the user must be allowed to open the entity. Redaction
  applies: a field the role sees masked is not sent to the service, and a price that would need it is refused with
  the reason.
- **The trade:** read through the source router as for a view, so the same document the screen shows is priced.
- **Market data** for the business date ([section 7](#7-market-data-for-a-business-date)).
- **The pack's pricer mapping** for the product ([section 6](#6-packs-declare-how-products-are-priced)).
- **Limits:** at most `drishti.quant.max-concurrent` requests in flight (8), each with a timeout
  (`drishti.quant.timeout`, 10 s; 120 s for batches), a cap on scenario and grid sizes, and a per-user rate.
- **Caching** ([section 11](#11-caching-and-performance)).
- **Health:** the service shows in Admin → Health like a connector (`UP`, `DOWN: …`), with its version and
  QuantLib's.
- **Audit:** each pricing is recorded in the access log (who, what entity, which date, which scenario).

## 5. What the quant service does

A small Python application (`services/drishti-quant/`), built and shipped as its own image (`drishti-quant`):

- **HTTP API** (FastAPI): `POST /price`, `/scenario`, `/curve`, `/implied`, `/health`. Requests carry the trade
  document, the market data and the pricer spec; responses carry numbers and the model used. No state between
  requests, so replicas can be added freely.
- **Adapters**, one per product family, turning a trade document (through the pack's mapping) into a QuantLib
  instrument and engine: vanilla interest-rate swaps (fixed/float, OIS), FX forwards and FX options
  (Garman–Kohlhagen), fixed-rate bonds, equity options (Black–Scholes, and American by finite differences), then
  swaptions (Black, Bachelier), caps and floors, CDS (ISDA standard model).
- **Market builders:** discount and forward curves from the points Drishti sends (or bootstrapped from instruments
  when the pack provides quotes), vol surfaces and smiles, fixings for floating legs.
- **Results:** NPV, the booked comparison is left to Drishti; Greeks (DV01, key-rate DV01 by bucket, delta, gamma,
  vega, theta); projected cashflows; the model, engine and market date used, so every number explains itself.
- **Only the service talks QuantLib.** If the bank already has a pricing library or service, an adapter in this
  service can call it instead, and Drishti does not change.

## 6. Packs declare how products are priced

A pack says, per product, which adapter prices it and where its inputs come from in the trade and the market, in
`pack.yaml` (validated at load; mistakes are problem codes like any pack error):

```yaml
quant:
  enabled: true
  pricers:
    IRS_FIXFLOAT:
      adapter: vanilla-swap
      notional: notional
      legs: legs                          # each leg: payReceive, fixedRate or index, spread, frequency, dayCount
      discount: { curve: "OIS-${currency}" }       # an ir-curve entity id, from the trade's currency
      forward:  { curve: "${legs[1].index}" }      # the floating leg's index curve
    FX_OPTION:
      adapter: fx-option
      pair: currencyPair
      strike: strike
      expiry: expiryDate
      vol: { surface: "FXV-${currencyPair}" }
      domestic: { curve: "OIS-${quoteCurrency}" }
      foreign:  { curve: "OIS-${baseCurrency}" }
  scenarios:                                 # named shifts offered in the what-if drawer
    rates-up-25:   { curves: { "*": { parallel: 25bp } } }
    steepener:     { curves: { "*": { twist: { pivot: 5Y, short: -10bp, long: 15bp } } } }
    vols-up-2:     { surfaces: { "*": { parallel: 0.02 } } }
```

Adding a product is configuration plus, if no adapter fits, a small adapter in the service. No server code changes.

## 7. Market data for a business date

The server assembles market data from its own sources, as of the date the user is looking at, using the same
routing, history and redaction as views: the curves, surfaces and fixings the pricer spec names are read like any
entity (`ir-curve`, `fx-vol-surface`, `rate-fixing` …), for the business date, and sent with the trade. A price for
2026-09-29 therefore uses 2026-09-29's market, the same market the screens show for that date, and the result records
which entities and dates it used (provenance, as on every view). If an input is missing for the date, the request is
refused with the name of the missing entity rather than priced on another day's data.

## 8. How it reaches the screen

| Where | What the user does | What they see |
|---|---|---|
| **Calc** | `await drishti.pricer.price_async("trade", "MX-20000001")`, `scenario_async(…, shift={"USD-OIS": +25})`, `implied_vol_async(…)`, `curve_async(…)` | DataFrames and charts of NPV, Greeks, cashflows; numpy and pandas stay in the browser for analysis |
| **A Valuation panel** | opens a trade; a Sutra binds a panel to the pricer (a `quant:` source on a `kv` or `table` panel) | model NPV beside booked MTM, the difference, Greeks by tenor, model and market date; computed when the view opens, then cached |
| **The what-if drawer** | a key the Sutra assigns (for example F6) on a trade or netting set | sliders and named scenarios from the pack (rates +25 bp, steepener, vols +2); the entity repriced live, shown as a before/after waterfall |
| **Overnight batch** (later) | an operator schedules a book's repricing | results loaded into the lake as a dated kind (`trade-valuation`), so they are searchable, pivotable and comparable over days like anything else |

## 9. The API

Server (what the console and Calc call; JSON):

| Method and path | Body / parameters | Returns |
|---|---|---|
| `POST /api/v1/quant/price/{kind}/{id}` | `asOf`, optional `model`, `measures` (`npv`, `greeks`, `cashflows`) | NPV, Greeks, cashflows, model, market inputs used |
| `POST /api/v1/quant/scenario/{kind}/{id}` | `asOf`, a named scenario or explicit shifts | base and shocked values, and the difference |
| `POST /api/v1/quant/implied/{kind}/{id}` | `asOf`, target price | implied vol or spread |
| `POST /api/v1/quant/curve/{kind}/{id}` | `asOf`, interpolation | zero, discount and forward curves on a grid |
| `GET /api/v1/quant/status` | — | enabled, service health, versions, the user's `quant` power |

Service (internal; the server is its only client): the same operations, with the trade, market data and pricer spec
in the body instead of entity ids.

## 10. Security

- **Only the server reaches the service**: it listens on the internal network, and every request carries a token the
  server signs with `drishti.quant.secret`; anything else is refused.
- **The service holds no credentials and reads no data**: it is given exactly the inputs to price.
- **Permissions are Drishti's**: the `quant` power, the right to open the entity, and redaction, all checked in the
  server before anything is sent.
- **Bounded work**: request and grid sizes, time and concurrency are capped in the server; the service also enforces
  its own timeouts, so a runaway calibration ends rather than holding a worker.

## 11. Caching and performance

- Results are cached in the server by (entity, document generation, business date, model, measures, scenario): a
  trade reopened costs nothing; a new version of the trade or a new market day reprices.
- Typical costs (to be measured in phase 1): a vanilla swap with curves given, tens of milliseconds; with key-rate
  DV01 by bump and reprice, a few hundred; a swaption with a calibrated model, up to a second.
- Batches run on the service's replicas in parallel, bounded so they never starve interactive requests (a separate
  queue and a share of workers).

## 12. Failure and operations

- **Deployment**: one more container in `deploy/compose.yaml` (`drishti-quant`), and `drishti.quant.url`,
  `drishti.quant.secret`, `drishti.quant.enabled` in the server. Without it, Calc's pricer calls and Valuation panels
  say "pricing is not configured" and everything else works as today.
- **Health**: Admin → Health shows the service; when it is down, Valuation panels show the reason in place (not an
  error page) and Calc raises a clear error.
- **Scaling**: add replicas behind the internal load balancer; the service is stateless.
- **Versions**: the service reports its QuantLib version with every result, so a number can always be reproduced.

## 13. Testing

- **The service**: each adapter against QuantLib's published examples and textbook values (the same discipline as
  `drishti.quant`'s tests), and against the sample trades with known inputs.
- **The server**: the gateway's permission, redaction, market-data assembly, caching and limits, with a fake service.
- **End to end**: a scratch server, console and service on the banking samples; a Valuation panel and Calc calls in a
  real browser; the what-if drawer's numbers against the service's.

## 14. Phases

| Phase | Delivers | Done when |
|---|---|---|
| **1. The service** | `drishti-quant` with adapters for vanilla swaps, FX forwards and options, fixed-rate bonds and equity options; curves from the points given; its image | adapters match reference values; the image runs on its own |
| **2. The gateway** | the server endpoints, the `quant` power, market-data assembly for a business date, pack `quant:` mappings, caching, limits, Health, audit | a banking trade prices through the server with the date's market, refused cleanly when a role or an input is missing |
| **3. In the UX** | `drishti.pricer` in Calc; a Valuation panel on banking trades through their Sutras; the pack's mappings for the banking products | a trader opens a swap and sees model NPV beside booked MTM, and reprices it from Calc |
| **4. What-if** | the drawer with named scenarios and sliders, before/after waterfall | shifts reprice live on a trade and a netting set |
| **5. More products and batches** | swaptions, caps and floors, CDS; overnight repricing of a book into the lake | a book's valuations are searchable and comparable over days |

Each phase is tested, documented (this document becomes a guide, with a deep `docs/guides/PRICING.md` for users),
and released on its own.

## 15. Risks and open questions

| Risk or question | Mitigation or decision needed |
|---|---|
| Model values differ from booked values and confuse users | always shown side by side, labelled as Drishti's model, with the model and market used |
| The sample market data is synthetic and not always consistent with booked trades | adapters are tested on reference cases; sample-data differences are expected and explained in the panel |
| Market inputs missing for a date | refused with the missing entity named; never silently priced on another day |
| Exotic products | out of scope until asked for; the adapter interface lets a bank plug in its own pricer |
| Load from many users repricing at once | caching, the concurrency cap and separate batch capacity |
| Which products first | to confirm with the desks: the proposal is phase 1's list |
| Batch results in the lake | to decide in phase 5: a new kind per day, written by the service's batch through the existing loaders |
| Licensing | QuantLib is BSD-licensed, ORE uses a modified BSD licence; both suit a proprietary product, with notices in `THIRD-PARTY-NOTICES.md` |
