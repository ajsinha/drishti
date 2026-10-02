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
# ADR-019: Pricing is a QuantLib service behind the Drishti server

| Status | Date | Decider |
|---|---|---|
| Proposed (on the roadmap) | 2026-10-01 | Ashutosh Sinha |

## Context
Users want proper pricing on what Drishti shows: a model NPV beside the booked MTM, Greeks, scenarios, implied vols.
Calc runs Python in the browser with numpy and scipy and a small analytics library (`drishti.quant`), which covers
screen-sized analysis but not curve building, calibration or the wider product range QuantLib handles. QuantLib has
no WebAssembly build, and running it in a browser would mean a large download and a laptop's CPU for calibrations.

## Decision
1. **Pricing runs in a separate service, `drishti-quant`**: Python with QuantLib's published wheels and a small
   HTTP API, stateless, scaled by replicas, reachable only from the Drishti server.
2. **The Drishti server is the only client**: it checks a new `quant` role power and the user's right to open the
   entity, applies redaction, assembles market data for the business date from its own sources, caches results by
   (entity version, date, model, scenario), caps work, and records each pricing in the access log.
3. **Packs declare how products are priced** (`quant:` in `pack.yaml`: product → adapter, and where each input comes
   from in the trade and the market), so a new product is configuration plus, at most, an adapter in the service.
4. **The UX reaches it through the server**: `drishti.pricer` in Calc, a Valuation panel bound by Sutras, and a
   what-if drawer; later, overnight batches into the lake.

Design: [QUANT_SERVICE.md](../QUANT_SERVICE.md).

## Consequences
- One more container to deploy; without it, pricing is reported as not configured and nothing else changes.
- A crash or a long calibration in native QuantLib cannot affect the Drishti server.
- Every result names its model, market inputs and QuantLib version, so it can be reproduced.
- The same interface can later front ORE or a bank's own pricers.

## Alternatives considered
- **QuantLib's Java bindings inside the server**: one process, but native builds per platform and native crashes in
  the shared server.
- **QuantLib in WebAssembly in Calc**: no build exists; tens of MB more per user; rebuilt for every Pyodide version.
- **Only `drishti.quant` in the browser**: kept for analysis, but it is not a pricing library.
