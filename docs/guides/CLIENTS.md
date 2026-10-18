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
# Reading Drishti from scripts, notebooks and Excel

Everything you can open in the terminal you can also read from code, as yourself, with a **personal API token**.
A token reads what you may read (your roles and packs, at the time of each call) and, unless you gave it a **write scope**,
**never changes anything**: any request other than `GET` with a read-only token is refused (`403 DRS-5002 API tokens only
read`), except the `POST`s that only read (`/search/pivot/**`, `/command`, the allow-list
`drishti.security.token-read-posts`, which also lets a token ask a BI model, `POST /api/v1/bi/mq`). Tools and CI that must change things get a token with the scope for
that job (the table in section 1); see "Personal API tokens" in [CLI_GUIDE.md](CLI_GUIDE.md) and below.

## 1. Make a token

1. Open **My account → API tokens**.
2. Give it a name that says what uses it (`Risk notebook`, `Desk P&L workbook`) and, if you like, a lifetime in days
   (1–366; blank for no expiry). Tick a **scope** only if the token must change things (the page explains each; the
   list is `drishti.security.token-scopes`). A token with a write scope must expire (90 days at most).
3. Press **Create token**. You see the token once, for example `drk_7QhK2mPq9xZa_4kq…` (`drk_`, a 12-character id, `_`,
   a 43-character secret). **Copy it now**: Drishti keeps only a hash of the secret and cannot show it again.
4. The list shows each token's id, when it was created, when it expires, when it was last used, and **Revoke**.

| Scope | Lets the token | And still needs |
|---|---|---|
| `read` (always) | read what its owner may read | |
| `design:write` | create, edit, check, propose and share Designs; save Sutras as proposals | the author role |
| `design:approve` | approve or reject proposed Sutras | the approver or admin role |
| `packs:admin` | load, unload, deploy and roll back packs; create, change, test and delete connectors; Retry now | the admin role |
| `loads:write` | announce that a batch of data has landed ([DATA_LOADS.md](DATA_LOADS.md)) | the owner's roles open the kind |
| `bi:write` | create, edit, propose, publish and delete BI items; restore versions ([BI_GUIDE.md](BI_GUIDE.md)) | the `bi-author` power and a workspace role |
| `bi:approve` | approve or reject proposed BI items, endorse, import a bundle | the approve power or `bi-steward` |
| `bi:admin` | change a workspace and the BI settings | the workspace admin role or `bi-admin` |
| `ai:use`, `ai:admin` | call the configured models and MCP tools; send a test call to a model | the admin role for `ai:admin` |

Send it in the `Authorization` header:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "https://drishti.bank.example:18480/api/v1/entities/trade/MX-20000001/raw" | jq .data.QUANTITY
```

You should see the trade's quantity: on the demo lakes `242000000` (the MUREX interest-rate swap's notional; the trades carry no MTM,
that is in the market-risk pack's trade valuations, `TVAL`).

Keep tokens like passwords: in an environment variable or a secrets store, never in a shared file. If one leaks,
revoke it: it stops at once. A token also stops when its owner is disabled, and when it expires. Administrators see
and revoke everyone's tokens in **Admin → Tokens**; every creation and revocation is in the audit log
(`token-created`, `token-revoked`).

## 2. Python

`clients/python/drishti_client.py` is one file that uses Python's standard library only: copy it next to your
notebook, or put its folder on `PYTHONPATH`.

```python
from drishti_client import Drishti

d = Drishti("https://drishti.bank.example:18480", token="drk_…")   # or set DRISHTI_URL and DRISHTI_TOKEN

d.field("trade", "MX-20000001", "QUANTITY")            # 242000000.0
d.field("trade", "MX-20000001", "LEGS[0].NOTIONAL")    # a path into the document: 242000000.0
doc = d.document("trade", "MX-20000001")               # the whole document (masked fields stay masked)

rows = d.search("TRD PRODUCT_TYPE=REPO")            # a pick list: one dict per entity, with the pack's key columns (96 repos)
# [{'kind': 'trade', 'id': 'CLY-3000265', 'title': 'CLY-3000265', 'Product type': 'REPO', 'Source system': 'CALYPSO', …, 'Quantity': 180000000, …}, …]

d.search("TRD where QUANTITY > 1bn order by QUANTITY desc limit 20", as_of="2026-10-14")   # a past business date
d.diff("trade", "MX-20000022", "2026-10-07", "2026-10-16")                                    # what changed between two dates
df = d.to_pandas("CPTY RATING=BBB")                 # a pandas DataFrame, if pandas is installed: 3 rows, columns kind, id, title, Rating, Name, …
```

A refusal raises `DrishtiError` with the server's code: `e.code == "DRS-5002"` (not yours to open), `"DRS-1001"` (no
source holds it), `"DRS-5010"` (the token is unknown, revoked or expired).

From the shell, the same client writes CSV:

```bash
export DRISHTI_URL=https://drishti.bank.example:18480 DRISHTI_TOKEN=drk_…
python3 clients/python/drishti_client.py search "TRD PRODUCT_TYPE=REPO" > repos.csv
python3 clients/python/drishti_client.py field trade MX-20000001 QUANTITY          # 242000000.0
python3 clients/python/drishti_client.py --as-of 2026-10-14 document trade MX-20000001
```

## 3. Excel

Any search or pick list is also CSV at `GET /api/v1/search/csv?q=…`: a header row (`kind`, `id`, `title`, then the
columns) and one row per entity, values unformatted. Text that a spreadsheet would run as a formula (starting with
`=`, `+`, `-` or `@`) is prefixed with `'`.

**Power Query** (Data → Get Data → From Other Sources → Blank Query → Advanced Editor):

```
let
    Url    = "https://drishti.bank.example:18480/api/v1/search/csv",
    Token  = "drk_…",
    Source = Csv.Document(Web.Contents(Url, [Query = [q = "TRD PRODUCT_TYPE=REPO"],
                                             Headers = [Authorization = "Bearer " & Token]]),
                          [Delimiter = ",", Encoding = 65001, QuoteStyle = QuoteStyle.Csv]),
    Table  = Table.PromoteHeaders(Source, [PromoteAllScalars = true])
in
    Table
```

Press **Close & Load**: the sheet fills with the 96 repos of the demo lakes and their key columns (`kind,id,title,Product type,Source
system,Trade status,Trade date,Buy sell code,Currency,Quantity,Maturity date,Portfolio,Counterparty`); **Refresh** reads them again.
For another query change `q`; for a past business date add `Headers = [Authorization = …, #"X-Drishti-As-Of" = "2026-10-14"]`.
When Excel asks how to connect, choose **Anonymous**: the token in the header is the credential. Better than writing
the token into the query: keep it in a named cell or a Power Query parameter only you can see.

## 4. Any other language

It is plain HTTP and JSON; the [API guide](API_GUIDE.md) lists every endpoint. The ones scripts use most:

| Read | `GET` |
|---|---|
| a document | `/api/v1/entities/{kind}/{id}/raw` |
| a view (strip, panels, provenance) | `/api/v1/views/{kind}/{id}` |
| a pick list or search | `/api/v1/search?q=…` (JSON) or `/api/v1/search/csv?q=…` (CSV) |
| what changed between dates | `/api/v1/history/{kind}/{id}/diff?from=…&to=…` |
| a pack's kinds | `/api/v1/packs/{code}/overview` |
| the BI catalogue, an item, a dataset or report | `/api/v1/bi/catalogue`, `/api/v1/bi/items/{id}`, `/api/v1/bi/datasets/{id}`, `/api/v1/bi/reports/{id}` |

A BI question is a model query, a `POST` with the fields, measures and filters in the body (`/api/v1/bi/mq`; the 2.1 dataset query
`/api/v1/bi/datasets/{id}/query` answers `410` since 2.2); it only reads, so a read-only token may call it, and the answer carries the
owner's row rules and masks ([BI_MODEL_SPEC.md](../architecture/BI_MODEL_SPEC.md), [API_GUIDE.md](API_GUIDE.md)):

```bash
curl -s -X POST -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' "$DRISHTI_URL/api/v1/bi/mq" \
  -d '{"mq":1,"model":"trades-basic","define":{"N":"count(trade)"},"select":{"fields":["trade.SOURCE_SYSTEM"],"measures":["N"]},"time":{"as-of":"2026-10-16"}}'
# "rows":[["CALYPSO",5000],["ENDUR",5000],["MUREX",5000]]
```

Add `X-Drishti-As-Of: 2026-10-14` for a past business date and `X-Drishti-Known-At: 2026-10-14T14:30:00Z` for the
data as known at an instant (stores with time travel only; others answer `DRS-1007`). For an id with a `/` (or `\`),
put `~` in its place and the id in the query: `/api/v1/entities/trade/~/raw?id=sl%2Fash-6`; the Python client does
this by itself.
