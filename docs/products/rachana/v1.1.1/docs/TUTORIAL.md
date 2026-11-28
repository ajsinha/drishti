<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary. All rights reserved. -->
# Tutorial

One continuous walk-through in four parts: a dashboard from a CSV file, a typed library, a live table with approvals, and a live board in a Python app. Each part stands on the previous one's setup but can be read alone. Reference: [WRITING_SUTRAS.md](WRITING_SUTRAS.md) and [IN_YOUR_APP.md](IN_YOUR_APP.md).

## Part 1: your first dashboard

You will make a sales dashboard from a CSV file: write a Sutra, check it, preview it, add a library fragment in Studio, and build a
static site that any web server can serve. About 20 minutes. Guides behind each step: [the language](WRITING_SUTRAS.md#the-rachana-language-level-12),
[CLI and SDKs](IN_YOUR_APP.md#command-line-and-sdks), [Studio](WRITING_SUTRAS.md#rachana-studio).

### Prepare your environment

| You need | Check |
|---|---|
| JDK 21 (to build the compiler bundles) | `java -version` |
| Node 20 or later | `node --version` |
| Python 3 | `python3 --version` |

The bundles are committed (`python/src/drishti_rachana/bundle/`), and the `rachana` command uses them when `dist/` is not built, so this step is optional: build them from the repository root (a few minutes; they land in `js/packages/rachana/dist/`) after a change to the Java core:

```bash
python3 tools/js/build_js.py
```

In IntelliJ IDEA, PyCharm or VS Code the same build is the launcher **Rachana JS runtime bundle** ([DEVELOPING.md](DEVELOPING.md#build-and-run-in-the-ide)).
Below, `rachana` stands for `node <repository>/js/packages/rachana-cli/bin/rachana.js`; on Linux or macOS:

```bash
alias rachana="node $PWD/js/packages/rachana-cli/bin/rachana.js"
```

On Windows (PowerShell): `function rachana { node C:\path\to\drishti-rachana\js\packages\rachana-cli\bin\rachana.js @args }`.

### Step 1: a starter project

From the command line, in a folder of your choice:

```text
$ rachana new my-dash
created my-dash (4 files). Next: cd my-dash && rachana lint
$ cd my-dash
$ rachana lint
1 file(s), 0 failing problem(s)
```

The folder holds `sutras/overview.sutra.yaml` (two figures and a note, declaring the newest level, `rachana: 1.2`), `data/sales.csv`,
`rachana.yaml` and a `README.md`.

### Step 2: your data

Copy the example's sales table over the starter's (120 rows: `region, product, month, units, revenue`; synthetic figures):

```bash
cp <repository>/examples/static-sales/data/sales.csv data/sales.csv
```

A `table: sales` entry in a Sutra reads `data/sales.csv`; the first line names the columns.

### Step 3: write the Sutra

Replace `sutras/overview.sutra.yaml` with:

```yaml
rachana: 1.2
sutra: overview
version: 1
title: { id: "'Sales overview'" }
data:
  sales: { table: sales, key: [region, product, month] }
controls:
  - { id: region, kind: dropdown, label: Region, options: ["North", "South", "East", "West", "Central"], default: first }
panels:
  - { id: revenue, kind: metric, label: Revenue, value: "sum($.sales, 'revenue')", fmt: amount0, span: 4 }
  - { id: units, kind: metric, label: Units, value: "sum($.sales, 'units')", fmt: amount0, span: 4 }
  - { id: region-rev, kind: metric, label: "Revenue, selected region", value: "sum($.sales[?@.region == ctl.region], 'revenue')", fmt: amount0, span: 4 }
  - { id: by-region, kind: hbar, title: Revenue by region, rows: "group($.sales, 'region', 'revenue')", label: label, value: value, fmt: amount0, span: 6 }
  - { id: trend, kind: line, title: Revenue by month, rows: "group($.sales, 'month', 'revenue')", x: label, y: value, fmt: amount0, span: 6 }
```

Three figures, one of them following the **Region** dropdown (`ctl.region`), a bar per region and a line over the months. `group`
returns rows of `label`, `value` and `count`.

### Step 4: check it

```text
$ rachana lint
1 file(s), 0 failing problem(s)
```

Make a mistake on purpose: change `sum($.sales, 'units')` to `summ($.sales, 'units')` and lint again:

```text
$ rachana lint
sutras/overview.sutra.yaml:11:5: error RCH-2101: expression 'summ($.sales, 'units')': unknown function 'summ'; known: abs, accrued, ...
1 file(s), 1 failing problem(s)
```

The exit status is 1, so the same command stops a CI job (`--format sarif` for code-scanning tools). Put `sum` back.

**In the UI:** start the preview and open the address it prints. Choose another region in the dropdown: the third figure changes.

```text
$ rachana preview sutras/overview.sutra.yaml --data data --port 4400
serving http://127.0.0.1:4400/page/overview  (Ctrl+C to stop)
```

### Step 5: add a library fragment

The `core` library holds ready-made screens ([its page](../libraries/core/README.md)). Add a ranking of products.

**In the UI (Studio):**

```text
$ rachana studio sutras --data data --port 4500
studio http://127.0.0.1:4500/  (core 1.0.0, finance 1.0.0; Ctrl+C to stop)
```

1. Open `http://127.0.0.1:4500/`, choose `overview.sutra.yaml` in **Sutra**.
2. In **Fragments**, search `ranked` and choose **ranked-bars** (core).
3. In **Fragment properties**, set the title to `Revenue by product`, choose the table `sales` and map `label` to `product` and
   `value` to `revenue`; press **Insert into Sutra**. The `use:` block appears in the source, `libraries: [core@1]` is added, and
   the preview shows the new panel.
4. A ranking should have one bar per product, so in the source change the `rows:` line to the grouped rows (below). **Changes** shows
   the diff; press **Save** (Ctrl+S).

**From the command line (or any editor):** add `libraries: [core@1]` after `version: 1`, and at the end of `panels`:

```yaml
  - use: core/ranked-bars@1
    as: products
    with:
      title: Revenue by product
      rows: "group($.sales, 'product', 'revenue')"
```

`rows` is the fragment's table parameter: the grouped rows already have the columns the fragment names (`label`, `value`), so no
mapping is needed. To bind a table whose columns have other names, map them: `rows: { from: $.sales, columns: { label: product,
value: revenue } }` ([fragments as functions](WRITING_SUTRAS.md#7-fragments-and-fragments-as-functions)). Lint again: `1 file(s), 0
failing problem(s)`.

### Step 6: build the site

```text
$ rachana build --out site
built site: 1 Sutra(s), 1 table file(s)
$ cd site && python3 -m http.server 8000
```

Open `http://127.0.0.1:8000/overview.html`. The `site/` folder holds `overview.html`, `index.html`, the Sutra, `data/sales.csv` with
`data/index.json`, the runtime and the Drishti Elements painter: no backend, any static web server (a browser does not fetch from
`file://`). The page filters, sorts and pages the table itself.

### Next (part 1)

- Serve the same screens with sign-in, databases and live updates: [Edit a live table with approvals](TUTORIAL.md#part-3-edit-a-live-table-with-approvals).
- Package your own fragments: [Build and use a library](TUTORIAL.md#part-2-build-and-use-a-library).
- The finished three-page example with every kind of `data:` entry: [examples/static-sales](../examples/static-sales/README.md).

## Part 2: build and use a library

You will write a typed fragment, package it as a signed library, pin it in a lock file and use it from a Sutra, with your own column
names mapped onto the fragment's. Guides: [Libraries and `rachana lib`](WRITING_SUTRAS.md#libraries-and-rachana-lib) and
[fragments as functions](WRITING_SUTRAS.md#7-fragments-and-fragments-as-functions).

### Prepare your environment (part 2)

Do the preparation of [Your first dashboard](TUTORIAL.md#prepare-your-environment) (bundles built, `rachana` alias). The library
commands also need the Java modules, built once from the repository root, and the launcher variable:

```bash
cd java && ./mvnw clean package -DskipTests && cd ..
export RACHANA_CLASSPATH="$(ls $PWD/java/rachana-{cli,tools,core,api}/target/rachana-*.jar | grep -v -e sources -e tests | tr '\n' ':')"
```

Windows: [the Java launcher](IN_YOUR_APP.md#the-java-launcher). Work in a new folder `shop-project` with
`data/sales.csv` copied from `examples/static-sales/data/`.

### Step 1: the library's manifest

`rachana_libs/shop/shop.rachana-lib.yaml`:

```yaml
rachana: 1
library: shop
version: 1.0.0
title: Shop
summary: Screens for a small shop.
publisher: { name: "Example Shop" }
licence: LicenseRef-Example
scope: public
profiles: []
depends: []
needs: { language: "1.2", contract: "1.0" }
categories:
  - { id: tables, title: Tables }
```

### Step 2: a typed fragment

`rachana_libs/shop/fragments/top-rows.v1.fragment.yaml` (`v1` is the major):

```yaml
rachana: 1.2
fragment: top-rows
version: 1
doc:
  summary: The largest rows of a table by one column.
  description: >-
    A short ranked list. Use it for a top-N beside a chart; for every row with search and totals use core/data-table.
  category: tables
params:
  rows:  { type: table, required: true, doc: The rows, columns: { name: { type: text, doc: "The row's name" }, value: { type: number, doc: The figure } } }
  limit: { type: integer, default: 5, min: 1, max: 50, doc: How many rows }
  fmt:   { type: format, default: amount0, doc: How the figure is written }
emits:
  - { name: pick, payload: { name: text }, doc: A row was chosen }
panels:
  - id: list
    kind: table
    rows: "take(sort(arg.rows, @.value, true), arg.limit)"
    columns: [ { label: Name, bind: "@.name" }, { label: Value, bind: "@.value", fmt: "%{fmt}" } ]
    emits: [ { name: pick, on: row-click, payload: { name: "@.name" } } ]
```

The fragment knows only its own names: a table with columns `name` and `value`, read as `arg.rows`; `fmt` is a name parameter placed
as `%{fmt}`. It returns one event, `pick`.

### Step 3: an example and its golden

Every fragment ships an example Sutra. `rachana_libs/shop/examples/top-rows.sutra.yaml`:

```yaml
rachana: 1.2
sutra: best-products
version: 1
libraries: [shop@1]
title: { id: "'Best products'" }
data:
  sales: { rows: [ { product: Kettle, revenue: 120 }, { product: Toaster, revenue: 80 } ] }
panels:
  - use: shop/top-rows@1
    as: best
    with:
      rows: { from: $.sales, columns: { name: product, value: revenue } }
      limit: 8
  - { id: picked, kind: markdown, text: "Picked: ${@best.pick.name}" }
```

Build the library (writes `exports` into the manifest and `MANIFEST.sha384`), record the example's ViewModel as its golden, and build
again so the golden is in the manifest:

```text
$ rachana lib build rachana_libs/shop
built rachana_libs/shop
$ java -cp "$RACHANA_CLASSPATH" <repository>/tools/libraries/LibCheck.java rachana_libs --update
ok   shop/top-rows
1 passed, 0 failed
$ rachana lib build rachana_libs/shop
built rachana_libs/shop
```

### Step 4: the quality gates

```text
$ rachana lib gate rachana_libs/shop
pass LG1 Library valid
pass LG2 Frozen fragments
     . nothing released yet: no FROZEN.sha384
pass LG3 Definition of done
     . top-rows: 1 use in its example; two are required, a minimal and a full one
...
pass LG8 Budgets
```

A failing gate names what is missing (a `doc.description`, a golden); the lines marked `.` are notes, failures only with `--strict`.

### Step 5: sign, pack, verify, lock

```text
$ rachana lib keygen
public  ed25519:10c4...0db
private 2593...cb8   (keep it secret)
$ rachana lib sign rachana_libs/shop ed25519:10c4...0db 2593...cb8
signed rachana_libs/shop
$ rachana lib pack rachana_libs/shop --out dist --require-signed --publisher ed25519:10c4...0db
dist/shop-1.0.0.rlib
$ rachana lib verify dist/shop-1.0.0.rlib --require-signed --publisher ed25519:10c4...0db
ok dist/shop-1.0.0.rlib
$ rachana lib lock dist/shop-1.0.0.rlib --lock rachana.lock
wrote rachana.lock (1 added)
```

Your keys differ; keep the private seed out of the repository. `rachana.lock` pins the exact version and the SHA-384 of the archive:

```yaml
lock: 1
libraries:
  shop: { version: 1.0.0, sha384: "77f9...dd74", source: "release:shop-1.0.0.rlib", licence: LicenseRef-Example }
```

### Step 6: use it from a Sutra

`sutras/best.sutra.yaml`, over the real sales table whose columns are `product` and `revenue`:

```yaml
rachana: 1.2
sutra: best-products
version: 1
libraries: [shop@1]
title: { id: "'Best products'" }
data:
  sales: { table: sales, key: [region, product, month] }
panels:
  - use: shop/top-rows@1
    as: best
    with:
      rows: { from: $.sales, columns: { name: product, value: revenue } }   # the fragment's column: your column
      limit: 8
  - { id: picked, kind: markdown, text: "Picked: ${@best.pick.name}" }
```

```text
$ rachana lint
2 file(s), 0 failing problem(s)
$ rachana build --out site
```

`rachana lint` and `build` run from the project folder read `rachana_libs/` there; another folder is `--libs <path>` or
`RACHANA_LIBS`. Map a column the fragment does not have, or forget a required one, and lint names it (`RCH-2114`, `RCH-2115`).

**In the UI:** `rachana studio sutras --data data` lists **top-rows** (shop) in **Fragments** beside the core and finance fragments;
its form offers your `sales` table and maps `name` and `value` onto its columns.

### Next (part 2)

- The libraries shipped with Rachana: [core](../libraries/core/README.md), [finance](../libraries/finance/README.md).

## Part 3: edit a live table with approvals

You will run the Rachana server over a CSV file, declare the table live and editable, let one user change a quantity, and have a
second user approve the change before it is written back to the file. Guides: [Server](IN_YOUR_APP.md#the-rachana-server),
[Live tables, editing and approvals](IN_YOUR_APP.md#live-tables-editing-and-approvals), [Data Protocol](IN_YOUR_APP.md#the-rachana-data-protocol).

### Prepare your environment (part 3)

JDK 21, Python 3 and `curl`. From the repository root, build the runtime bundle first, then the jars (the server jar carries the bundle):

```bash
python3 tools/js/build_js.py
cd java && ./mvnw clean package -DskipTests && cd ..
export SERVER_JAR="$PWD/$(ls java/rachana-server/target/rachana-server-*-all.jar)"
```

In the IDE: **Rachana JS runtime bundle**, then **Rachana build all** ([DEVELOPING.md](DEVELOPING.md#build-and-run-in-the-ide)).
Work in a new folder `orders-demo`; every path below is inside it.

### Step 1: the table

`project/data/orders.csv`:

```text
ID,V,QTY,BY_USER
A-1,1,10,system
A-2,1,25,system
```

`V` is the row's version and `BY_USER` who changed it last; the server maintains both when it writes a change.

### Step 2: two users

```text
$ java -jar "$SERVER_JAR" hash-password
tutorial-pass                      (typed)
pbkdf2-sha256$210000$l3bJ...87JQ
```

`users.yaml`, with that hash for both (a development password; never reuse it):

```yaml
users:
  alice: { password: "pbkdf2-sha256$210000$...", roles: [sales] }
  carol: { password: "pbkdf2-sha256$210000$...", roles: [sales, checker] }
```

### Step 3: the server configuration

`rachana-server.yaml`:

```yaml
rachana-server: 1
server: { bind: 127.0.0.1, port: 8095 }
project: { dir: "${config}/project", watch: true }
tables:
  orders:
    file: "${project}/data/orders.csv"
    key: ID
    refresh: 1s                                               # read again every second; changes go to readers as patches
    write: { journal: "${config}/data/orders.applied.jsonl" } # each applied change id, recorded once
auth: { mode: users, users: "${config}/users.yaml" }
access:
  default: signed-in
  tables:
    orders: { read: [sales], edit: { roles: [sales], columns: [QTY] }, approve: [checker] }
edits:
  journal: "${config}/data/outbox.jsonl"                      # the outbox: a change is journalled before it is acknowledged
  hold: [orders]                                              # four eyes: every change waits for an approver
```

### Step 4: declare the table live and editable

`project/orders.live.yaml`:

```yaml
rachana: 1
live-table: orders
title: Orders
snapshot: orders
stream: orders
key: ID
version: V
changed-by: BY_USER
tray: true
columns:
  - { name: ID, label: Order }
  - { name: V, type: integer }
  - { name: QTY, type: integer, label: Quantity }
  - { name: BY_USER, label: Changed by }
edit:
  fields:
    - { id: qty, bind: QTY, type: number, min: 0 }
```

### Step 5: the screen

`project/sutras/orders.sutra.yaml`: the core library's live grid and the tray of pending changes.

```yaml
rachana: 1.2
sutra: orders
version: 1
libraries: [core@1]
title: { id: "'Orders'" }
data:
  orders:  { table: orders, key: ID, live: true }
  pending: { table: orders.changes, key: change, live: true }
panels:
  - use: core/live-table@1
    with:
      title: Orders
      rows: $.orders
      order: V
      edit: cells
      columns:
        - { label: Order, bind: "@.ID" }
        - { label: Quantity, bind: "@.QTY", fmt: amount0 }
        - { label: Changed by, bind: "@.BY_USER" }
  - use: core/held-changes@1
    with:
      changes: $.pending
```

Check both files: `cd project && rachana lint` answers `2 file(s), 0 failing problem(s)` (the `rachana` alias of
[Your first dashboard](TUTORIAL.md#prepare-your-environment)).

### Step 6: start the server

```text
$ java -jar "$SERVER_JAR" check --config "$PWD/rachana-server.yaml"
config /.../orders-demo/rachana-server.yaml
project /.../orders-demo/project (level 1.2, watched)
table orders: file /.../orders-demo/project/data/orders.csv, every 1000 ms
auth users /.../orders-demo/users.yaml
$ java -jar "$SERVER_JAR" serve --config "$PWD/rachana-server.yaml"
rachana-server listening on http://127.0.0.1:8095/
```

The log is one JSON line per event; `{"event":"table","table":"orders","file":"orders.live.yaml","snapshot":"orders"}` says the
declaration was taken.

### Step 7: change a quantity (alice)

**In the UI:** open `http://127.0.0.1:8095/`, sign in as `alice`, open **orders**. Double-click the Quantity of `A-1`, type `12`, press
Enter. The grid still shows 10: the change is held. **Pending changes** lists it: `held`, `A-1: QTY = 12`, `alice`, "waiting for an
approver".

**From the command line:** sign in with a cookie jar, read the page's CSRF token, and send the change:

```bash
B=http://127.0.0.1:8095
signin() {   # user: a session cookie in <user>.jar
  F=$(curl -s -c $1.jar -b $1.jar $B/signin)
  C=$(echo "$F" | grep -oE 'name="?csrf"? value="[^"]+"' | sed 's/.*value="//;s/"//')
  curl -s -o /dev/null -c $1.jar -b $1.jar -H "Origin: $B" --data-urlencode "csrf=$C" --data-urlencode "next=/" \
       --data-urlencode "user=$1" --data-urlencode "password=tutorial-pass" $B/signin
}
token() { curl -s -b $1.jar $B/ | grep -oE 'name="?csrf"? (content|value)="[^"]+"' | head -1 | sed 's/.*="//;s/"//'; }
write() { curl -s -b $1.jar -H "Origin: $B" -H "X-Rachana-CSRF: $(token $1)" -H "Content-Type: application/json" -d "$3" $B/data/v1/tables/$2/write; echo; }
signin alice; signin carol
write alice orders '{"id":"c1","key":["A-1"],"basedOn":1,"set":{"QTY":12}}'
```

```json
{"change":"c1","state":"held","message":"waiting for an approver"}
```

The change names the row's key, the version it was made on (`basedOn`) and the columns it sets; `id` is chosen by the client, so the
same change sent twice is taken once.

### Step 8: approve it (carol)

**In the UI:** in another browser (or a private window) sign in as `carol` and open **orders**. **Pending changes** shows alice's held
change. Double-click its State, pick **released** (or **rejected**). Within a second the grid shows `A-1` at 12, changed by alice.
Alice cannot release her own change: an approver never decides their own.

**From the command line:**

```bash
curl -s -b carol.jar -H "Origin: $B" -H "X-Rachana-CSRF: $(token carol)" -H "Content-Type: application/json" \
     -d '{"columns":["change","state","by","summary"]}' $B/data/v1/tables/orders.changes/query; echo
write alice orders.changes '{"id":"d1","key":["c1"],"basedOn":"held","set":{"state":"released"}}'
write carol orders.changes '{"id":"d2","key":["c1"],"basedOn":"held","set":{"state":"released"}}'
```

```text
{"table":"orders.changes",...,"columns":["change","state","by","summary"],"values":[["c1","held","alice","A-1: QTY = 12"]],"total":1}
{"error":{"code":"rdp.write.forbidden","message":"you may not decide the changes of orders","table":"orders.changes"}}
{"change":"d2","state":"applied","message":"c1 released: released by carol"}
```

### Step 9: see it everywhere

```text
$ cat project/data/orders.csv
ID,V,QTY,BY_USER
A-1,2,12,alice
A-2,1,25,system
$ cat data/orders.applied.jsonl
{"change":"c1","state":"writing","at":"..."}
{"change":"c1","state":"applied","at":"..."}
$ curl -s -b alice.jar "$B/data/v1/tables/orders/rows?shape=objects"
{"table":"orders","version":{"epoch":"e1","seq":1},"rows":[{"ID":"A-1","V":2,"QTY":12,"BY_USER":"alice"},...],"total":2}
```

The server log holds the audit trail: `write-submitted`, `write-held`, `write-refused` (alice's attempt), `write-released`,
`write-sent`.

### Step 10: what the guards refuse

```bash
write alice orders '{"id":"c3","key":["A-1"],"basedOn":1,"set":{"QTY":99}}'    # made on an old version
write alice orders '{"id":"c4","key":["A-2"],"basedOn":1,"set":{"QTY":-5}}'    # breaks the field's rule
curl -s -b alice.jar -H "Origin: $B" -H "Content-Type: application/json" \
     -d '{"id":"c6","key":["A-2"],"basedOn":1,"set":{"QTY":31}}' $B/data/v1/tables/orders/write; echo   # no CSRF token
```

```text
{"error":{"code":"rdp.write.conflict","message":"the row changed since version 1: it is at version 2, changed by alice","table":"orders","current":{"version":2,"by":"alice"}}}
{"error":{"code":"rdp.write.invalid","message":"the change breaks 1 rule: qty is at least 0","table":"orders","fields":[{"column":"QTY","rule":"value","message":"qty is at least 0"}]}}
{"error":{"code":"rdp.write.forbidden","message":"the CSRF token is missing or wrong"}}
```

Stop the server with Ctrl+C. To switch a table's edits off, list it under `edits.off`; to take changes without approval, remove it
from `edits.hold` (both read at start). A database table works the same way with `write: { table, journal-table }`
([Server](IN_YOUR_APP.md#5-writes-back-to-the-source)).

## Part 4: a live board in Flask

You will serve a Sutra and a live table from a Flask app in about thirty lines: the board's tiles and grid change on screen as orders
arrive, with no front-end code. The same app under FastAPI is beside it. Guides: [CLI and SDKs](IN_YOUR_APP.md#python),
[Data Protocol](IN_YOUR_APP.md#the-rachana-data-protocol).

### Prepare your environment (part 4)

Python 3.10 or later (3.13 recommended); no Java and no Node (the runtime bundle is committed). From the repository root, into the Python you choose:

```bash
pip install -r examples/live-orders/requirements.txt     # the package, Flask, FastAPI, uvicorn; Windows: py -3.13 -m pip ...
```

The run steps for Windows and Linux, and the troubleshooting, are in [examples/live-orders/README.md](../examples/live-orders/README.md).

In PyCharm: open the folder
`python/` and use the checked-in run configurations (`run-configs/`, the `Rachana: run live-orders` launchers).

### Step 1: the Sutra

[examples/live-orders/orders.sutra.yaml](../examples/live-orders/orders.sutra.yaml) reads one table, `orders`, declared live with
its key:

```yaml
data:
  orders: { table: orders, key: [order_id], live: true }
```

Four tiles, a bar chart of value by status and a grid of the whole book are computed from it. Check it:

```text
$ python -m drishti_rachana lint examples/live-orders
1 file(s), 0 failing problem(s)
```

### Step 2: the app

[examples/live-orders/flask_app.py](../examples/live-orders/flask_app.py), the part that matters:

```python
from flask import Flask
from drishti_rachana.adapters.flask import RachanaBlueprint
from gen_orders import OrderBook

book = OrderBook.from_seed_file(HERE / "orders_seed.csv")
app = Flask(__name__)
rachana = RachanaBlueprint(sutras=HERE)            # serves orders.sutra.yaml

@rachana.table("orders", key=["order_id"])
def orders():
    return book.snapshot()                         # list of dicts

@rachana.live("orders")                            # keyed changes, streamed over SSE
def orders_changes():
    while True:
        time.sleep(0.25)
        patch = book.step()
        for row in patch.get("add", []) + patch.get("update", []):
            yield (row["order_id"],), row
        for key in patch.get("remove", []):
            yield tuple(key), None                 # None removes the row

app.register_blueprint(rachana, url_prefix="/dash")
```

`@table` gives the rows; `@live` yields `(key, row)` changes, which the adapter numbers and streams as Data Protocol patches.
`OrderBook` (in `gen_orders.py`) stands in for your own source; it is synthetic.

### Step 3: run it

```bash
cd examples/live-orders && python flask_app.py          # Windows: cd examples\live-orders, then py -3.13 flask_app.py
```

**In the UI:** open `http://127.0.0.1:5000/dash/`, then **orders**: orders arrive, statuses move, and the tiles and grid change
without a reload.

**From the command line:** the same table over the Data Protocol:

```text
$ curl -s http://127.0.0.1:5000/dash/data/tables
{"tables": [{"name": "orders", "key": ["order_id"], "can": ["page", "sort", "filter", "offset", "count", "group", "changes", "replay"]}]}
$ curl -s "http://127.0.0.1:5000/dash/data/tables/orders/rows?limit=2&shape=objects"
{"table": "orders", "version": {"epoch": "1094", "seq": 0}, "rows": [{"order_id": "O-1001", ..., "status": "Pending"}, ...]}
$ curl -s -N "http://127.0.0.1:5000/dash/data/changes?table=orders"
id: 1094:1
event: msg
data: {"t": "msg", "topic": "rdp.orders", ..., "seq": 1, "data": {"table": "orders", "update": [{"order_id": "O-1016", "status": "Packed"}]}}
```

The epoch differs on each start. Stop with Ctrl+C.

### Step 4: the same under FastAPI

```bash
cd examples/live-orders && python -m uvicorn fastapi_app:app
```

`RachanaRouter` takes the same `@table` and `@live` decorators; open `http://127.0.0.1:8000/dash/`.

### Next (part 4)

- Check Sutras in CI: `python -m drishti_rachana lint --format sarif`.
- Your own app, step by step, live tables and troubleshooting: [Rachana and Elements in your own Flask or FastAPI application](IN_YOUR_APP.md#rachana-and-elements-in-your-own-flask-or-fastapi-application).
- A standalone server with sign-in, databases and editing: [Edit a live table with approvals](TUTORIAL.md#part-3-edit-a-live-table-with-approvals).
