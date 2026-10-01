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
A token reads only what you may read (your roles and packs, at the time of each call) and **never changes anything**:
any request other than `GET` with a token is refused (`403 DRS-5002 API tokens only read`).

## 1. Make a token

1. Open **My account → API tokens**.
2. Give it a name that says what uses it (`Risk notebook`, `Desk P&L workbook`) and, if you like, a lifetime in days
   (1–366; blank for no expiry).
3. Press **Create token**. You see the token once, for example `drk_7QhK2mPq9xZa_4kq…` (`drk_`, a 12-character id, `_`,
   a 43-character secret). **Copy it now**: Drishti keeps only a hash of the secret and cannot show it again.
4. The list shows each token's id, when it was created, when it expires, when it was last used, and **Revoke**.

Send it in the `Authorization` header:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "https://drishti.bank.example:18480/api/v1/entities/trade/T-10001/raw" | jq .data.mtm
```

You should see the trade's MTM, for example `1875863`.

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

d.field("trade", "T-10001", "mtm")                 # 1875863.0
d.field("trade", "T-10001", "legs[0].rate")        # a path into the document
doc = d.document("trade", "T-10001")               # the whole document (masked fields stay masked)

rows = d.search("TRD productType=Revolver")         # a pick list: one dict per entity, with the pack's key columns
# [{'kind': 'trade', 'id': 'T-10295', 'title': …, 'Product type': 'REVOLVER', 'Notional': 202000000, …}, …]

d.search("TRD where notional > 250m order by mtm desc limit 20", as_of="2026-09-29")   # a past business date
d.diff("trade", "T-10001", "2026-09-25", "2026-09-30")                                # what changed between two dates
df = d.to_pandas("CPTY rating=BBB")                 # a pandas DataFrame, if pandas is installed
```

A refusal raises `DrishtiError` with the server's code: `e.code == "DRS-5002"` (not yours to open), `"DRS-1001"` (no
source holds it), `"DRS-5010"` (the token is unknown, revoked or expired).

From the shell, the same client writes CSV:

```bash
export DRISHTI_URL=https://drishti.bank.example:18480 DRISHTI_TOKEN=drk_…
python3 clients/python/drishti_client.py search "TRD productType=Revolver" > revolvers.csv
python3 clients/python/drishti_client.py field trade T-10001 mtm
python3 clients/python/drishti_client.py --as-of 2026-09-29 document trade T-10001
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
    Source = Csv.Document(Web.Contents(Url, [Query = [q = "TRD productType=Revolver"],
                                             Headers = [Authorization = "Bearer " & Token]]),
                          [Delimiter = ",", Encoding = 65001, QuoteStyle = QuoteStyle.Csv]),
    Table  = Table.PromoteHeaders(Source, [PromoteAllScalars = true])
in
    Table
```

Press **Close & Load**: the sheet fills with the six revolvers and their key columns; **Refresh** reads them again.
For another query change `q`; for a past business date add `Headers = [Authorization = …, #"X-Drishti-As-Of" = "2026-09-29"]`.
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

Add `X-Drishti-As-Of: 2026-09-29` for a past business date and `X-Drishti-Known-At: 2026-09-29T14:30:00Z` for the
data as known at an instant.
