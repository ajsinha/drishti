<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary. All rights reserved. -->
# Rachana in your application

How to run what you wrote: embed Rachana and Elements in your own Flask or FastAPI application, run the standalone server, keep tables live and editable, speak the Data Protocol, and use the command line and the SDKs. Writing the Sutra is [WRITING_SUTRAS.md](WRITING_SUTRAS.md).

## Rachana and Elements in your own Flask or FastAPI application

You have a Python web application and you want Rachana screens in it: a Sutra file for the layout, a Python function for each table, and
the painter ([Drishti Elements](../js/vendor/drishti-elements/3.3.1/) through `<rachana-view>`) in the browser. This guide shows
the few lines for FastAPI and for Flask, live tables, the choice between a static site and a data server, and how to run the
`live-orders` example both ways. Everything below was run against this repository unless a line says it was not.

Related: the short tutorial [A live board in Flask](TUTORIAL.md#part-4-a-live-board-in-flask), the wire format in [Data Protocol](#the-rachana-data-protocol), live tables in [Live tables](#live-tables-editing-and-approvals), the standalone server in [Server](#the-rachana-server) and the SDK list in [Command line and SDKs](#command-line-and-sdks).

### 1. How the pieces fit

```
browser                                        your Python app
 <rachana-view src=… data=…>  ── GET /dash/sutras/orders.sutra.yaml ──►  the Sutra file (a folder you name)
   painter = Drishti Elements ─ GET /dash/data/tables/orders/rows ────►  your @table function (list of dicts)
                              ◄─ SSE /dash/data/changes?table=orders ──  your @live generator ((key, row) changes)
                              ─  GET /dash/static/... ────────────────►  Elements, the element, the runtime bundle
```

Nothing is generated: the Sutra is data, the compiler runs in the browser (the runtime bundle), and the adapter only serves four
things: the Sutra text, the rows, the changes, and the assets. The adapter is `drishti_rachana.adapters.fastapi.RachanaRouter` (an
`APIRouter`) or `drishti_rachana.adapters.flask.RachanaBlueprint` (a `Blueprint`). Both expose the same routes under the prefix you give:

| Route (under the prefix) | What |
|---|---|
| `/` | a list of the Sutras in the folder |
| `/page/<name>` | a ready page: Elements plus one `<rachana-view>` for that Sutra |
| `/sutras/<name>.sutra.yaml` | the Sutra's text |
| `/data/...` | the Data Protocol v1: `tables`, `tables/<t>/rows`, queries, and `changes` (SSE, `GET` or `POST`) |
| `/static/...` | the assets of `<rachana-view>`: the element, the vendored Elements pack, the runtime bundle (and Arrow JS) |

The Python adapters serve the Sutra and the data **read-only**. They have no sign-in and no write-back; put your own authentication in
front (section 8). For sign-in, edits and approvals use the [Rachana server](IN_YOUR_APP.md#the-rachana-server).

### 2. What you need

| Need | Notes |
|---|---|
| Python | 3.13 recommended; the package accepts 3.10 or later |
| Flask 3 or later, or FastAPI 0.110 or later with uvicorn | extras `flask`, `fastapi` of the package; uvicorn you install yourself |
| the `drishti_rachana` package | **not on a public index**: install it from a checkout (below) or from a wheel you built |
| the runtime bundle `rachana.runtime.js` | the browser needs it to compile the Sutra. It is **committed** in `python/src/drishti_rachana/bundle/` (with `rachana.tools.js` for the `rachana` command), so a zip of the platform needs no build: see "Rebuild the runtime bundle" |
| Java | **not needed to run your app.** JDK 21 (and Node 20 for the checks) is needed only to rebuild the runtime bundle with `tools/js/build_js.py`. The Java compiler and server are only for the [Rachana server](IN_YOUR_APP.md#the-rachana-server) |
| Node | not needed to run your app |

Optional extras of the package: `arrow` (Arrow IPC rows), `polars`, `v8` (compile and lint in Python: `python -m drishti_rachana lint`; it
runs the same bundle on V8 through `mini-racer`).

### 3. Install the package from a checkout

Rachana never makes a virtual environment or installs anything for you: use the Python you choose and install the packages from this checkout into it (run from the repository root).

```bash
python3 -m pip install -e 'python[flask,fastapi]' uvicorn
```

On Windows use `py -3.13 -m pip install -e "python[flask,fastapi]" uvicorn`. (For the `live-orders` example,
`pip install -r examples/live-orders/requirements.txt` does the same.)

An editable install reads the assets from the checkout's `js/` folder. For a wheel (a deployment that is not a checkout): build the
bundle first, then `python -m build python/`; the wheel then carries the element, Elements 3.3.1, Arrow JS and the bundle as package
data (`pyproject.toml`: `force-include`). *The wheel build was not run for this guide.*

You can also run without installing: `PYTHONPATH=python/src python -m uvicorn ...` (this is what the checked-in IDE launchers do).

#### Rebuild the runtime bundle

The bundle is committed, so there is nothing to build to run an app. Rebuild it only after a change to the Java core:

```bash
python3 tools/js/build_js.py            # JDK 21 and Node 20; writes js/packages/rachana/dist/ and the package's bundle/ folder (commit the result)
```

If `rachana.runtime.js` is missing from your copy, the page says so (instead of staying blank) and the console shows a 404 for
`/dash/static/rachana/rachana.runtime.js`: restore the file (unzip again, or `git checkout python/src/drishti_rachana/bundle`).

### 4. A minimal FastAPI app, step by step

A folder with a Sutra and two CSV files (copied from `examples/static-sales`):

```
myapp/
  app.py
  sutras/overview.sutra.yaml        # from examples/static-sales/overview.sutra.yaml
  sales.csv  targets.csv            # from examples/static-sales/data/
```

**Step 1, the Sutra.** It names tables, never rows (`data:` entries), and the host resolves each name:

```yaml
data:
  sales:   { table: sales, key: [region, product, month] }
  targets: { table: targets, key: [region, month] }
```

**Step 2, the app.** `RachanaRouter(sutras=<folder>)` serves every `*.sutra.yaml` below the folder. `@rachana.table(name, key=[...])`
registers a function that returns the rows: a list of dicts (or an Arrow, Polars or pandas table; sync or async). Then include the
router under a prefix. This is `app.py`:

```python
import csv
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from drishti_rachana.adapters.fastapi import RachanaRouter

HERE = Path(__file__).parent

def read(name, numbers):
    with open(HERE / f"{name}.csv", newline="", encoding="utf-8") as f:
        return [{k: (float(v) if k in numbers else v) for k, v in row.items()} for row in csv.DictReader(f)]

app = FastAPI()
rachana = RachanaRouter(sutras=HERE / "sutras")         # every *.sutra.yaml in the folder

@rachana.table("sales", key=["region", "product", "month"])
def sales():
    return read("sales", {"units", "revenue"})          # a list of dicts

@rachana.table("targets", key=["region", "month"])
def targets():
    return read("targets", {"target"})

app.include_router(rachana, prefix="/dash")
```

That is the whole backend. `uvicorn app:app` now serves `/dash/` (the list of Sutras) and `/dash/page/overview` (the ready page).

**Step 3, your own page.** To put the screen inside a page of your app, give the page Elements, the element module and one
`<rachana-view>`. The asset URLs are the adapter's, under your prefix. This route belongs in the same `app.py`:

```python
@app.get("/", response_class=HTMLResponse)
def home():
    el = "/dash/static/vendor/drishti-elements/3.3.1"
    return f"""<!doctype html><meta charset=utf-8><title>My app</title>
<link rel=stylesheet href="{el}/drishti-elements.css"><script src="{el}/drishti-elements.js"></script>
<script type=module>import "/dash/static/rachana/src/rachana_view.js";</script>
<h1>My app</h1>
<rachana-view src="/dash/sutras/overview.sutra.yaml" data="/dash/data"
              runtime="/dash/static/rachana/rachana.runtime.js"></rachana-view>"""
```

The attributes of `<rachana-view>` are `src` (the Sutra's address), `data` (the Data Protocol root), `runtime` (the bundle), `live` (follow
changes) and, not used here, `formats`, `today`, `allow`, `form`, `libraries`. In a Jinja or Django template the same four lines go
in the template. The easiest start is the adapter's own page, `/dash/page/overview`; it writes exactly this markup.

**Step 4, run it.**

```bash
cd myapp
python3 -m uvicorn app:app --port 8000        # open http://127.0.0.1:8000/  and  /dash/
```

Checked here for this app (with `curl`, on a free port): `/` 200, the Sutra 200 (2104 bytes), `/dash/data/tables` lists `sales` and
`targets`, `rows?limit=1&shape=objects` answers a row with numbers as numbers, Elements and `rachana_view.js` 200, and the runtime
bundle 404 until it is built (section 3).

### 5. The same in Flask

`RachanaBlueprint(sutras=..., name="rachana")` has the same decorators. Only the framework lines change:

```python
from flask import Flask
from drishti_rachana.adapters.flask import RachanaBlueprint

app = Flask(__name__)
rachana = RachanaBlueprint(sutras=HERE / "sutras")

@rachana.table("sales", key=["region", "product", "month"])
def sales():
    return read("sales", {"units", "revenue"})

app.register_blueprint(rachana, url_prefix="/dash")

if __name__ == "__main__":
    app.run(threaded=True)          # threaded: each live reader holds one open response
```

Serve the page the same way (a Flask route returning the HTML of step 3). With a production server give it enough threads or
workers for the open change streams: one per open browser tab that follows a live table (a general WSGI point, not measured here).

### 6. Live tables: pushing updates

Declare the table live in the Sutra (`live: true`, with its `key`) and add a generator with `@rachana.live(name)`. It yields
`(key, row)`; a key is a tuple of the key columns' values; a row of `None` removes the row. The adapter numbers the changes and streams
them as Data Protocol patches over SSE, and the painter changes the affected tiles and rows without a reload. Copied from the real example:

```python
@rachana.table("orders", key=["order_id"])
async def orders():
    return book.snapshot()                         # the starting rows

@rachana.live("orders")                            # keyed changes, streamed over SSE
async def orders_changes():
    while True:
        await asyncio.sleep(0.25)
        patch = book.step()                        # your source: a queue, a poll, a message consumer
        for row in patch.get("add", []) + patch.get("update", []):
            yield (row["order_id"],), row
        for key in patch.get("remove", []):
            yield tuple(key), None
```

The Flask version is the same with `def`, `time.sleep` and `Blueprint` decorators (`examples/live-orders/flask_app.py`). `@live`
accepts a sync or an async generator in either framework. Two points from the adapter code: a table function that is `async` is run
with its own `asyncio.run` for each snapshot, so do not rely on resources bound to your application's event loop inside it; and
an update row may carry only the changed columns (the example's updates carry `order_id` and `status`). The shape of patches (`add`,
`update`, `remove`, `reset`; epochs and sequence numbers) is in [Data Protocol](IN_YOUR_APP.md#the-rachana-data-protocol).

### 7. Static mode or a data server

| | Static site | Python adapter in your app | Rachana server |
|---|---|---|---|
| Data | CSV, JSON or Arrow files built into the site | your functions: database, queue, API, files | files, JDBC, REST, in a config file |
| Live changes | no change stream | yes: `@live` over SSE | yes |
| Backend | none: any static web server | your Flask or FastAPI process | the Java server (JDK 21) |
| Sign-in, edits, approvals | no | no: yours to add | yes |
| Make it | `rachana build` (Node CLI) | this guide | [Server](IN_YOUR_APP.md#the-rachana-server) |

Static mode: `cd examples/static-sales && rachana build` writes `site/` (one page per Sutra; the page answers the Data Protocol itself from
the table files). Details in [static-sales](../examples/static-sales/README.md) and
[Command line and SDKs](IN_YOUR_APP.md#command-line-and-sdks). The static CLI and the server were **not** run for this guide. Both need the compiler bundles
(and the server a JDK 21 and its jar).

Choose the adapter when the data already lives in your Python process or behind your Python code. Choose the server when you need
sign-in, row rules, write-back or approvals without writing them.

### 8. Putting your own sign-in in front

The adapters add no authentication. Your framework does, in its own way (framework features, not Rachana APIs, and not exercised here):
in FastAPI pass `dependencies=[Depends(your_check)]` to `app.include_router(rachana, prefix="/dash", ...)`; in Flask use
`rachana.before_request(your_check)` on the blueprint. The data routes and the assets are all under the prefix, so one check covers all of
them. Do not bind a data-bearing app to a public address without it.

### 9. Run the `live-orders` example (both ways)

`examples/live-orders` is the same board in both frameworks: `orders_seed.csv` (40 synthetic orders), `gen_orders.py` (the data source:
`OrderBook`, standard library only), `orders.sutra.yaml`, `flask_app.py`, `fastapi_app.py`. The install, the exact commands for
Windows and Linux, what you should see, how to stop and the troubleshooting are in its README:
[examples/live-orders/README.md](../examples/live-orders/README.md).

**The IDE launchers.** In PyCharm (open the repository root, choose a Python interpreter that has the extras): the run
configurations **Rachana: run live-orders (FastAPI)** and **Rachana: run live-orders (Flask)**, in the group **PyCharm-rachana-run**.
They set `PYTHONPATH` to `python/src` and the working directory to `examples/live-orders`.

The same table over the command line (from a running app):

```text
$ curl -s http://127.0.0.1:8000/dash/data/tables
{"tables": [{"name": "orders", "key": ["order_id"], "can": ["page", "sort", "filter", "offset", "count", "group", "changes", "replay"]}]}
$ curl -s -N "http://127.0.0.1:8000/dash/data/changes?table=orders"
id: 008b:1
event: msg
data: {"t": "msg", "topic": "rdp.orders", ..., "seq": 1, "data": {"table": "orders", "update": [{"order_id": "O-1013", "status": "Shipped"}]}}
```

The epoch (`008b`) differs on each start.

### 10. Troubleshooting

| You see | Cause and fix |
|---|---|
| the page says the runtime bundle is missing, or is blank with a 404 for `rachana.runtime.js` | `python/src/drishti_rachana/bundle/rachana.runtime.js` is missing from your copy: unzip again or `git checkout` it; to rebuild: `python3 tools/js/build_js.py` (JDK 21) |
| `ModuleNotFoundError: drishti_rachana` | not installed in this interpreter: `pip install -e 'python[flask,fastapi]'` from the repository root, or set `PYTHONPATH` to `python/src` |
| `ModuleNotFoundError: flask` or `fastapi` | the extra is missing: install it (`flask`, `fastapi` and `uvicorn`) into the interpreter the launcher uses |
| `/dash/` lists no Sutra | `sutras=` points at a folder without `*.sutra.yaml` files below it, or the file name does not end in `.sutra.yaml`; the page name is the file name without the suffix |
| `/dash/data/tables` lacks your table | the `@rachana.table("name")` name must equal the `table:` name in the Sutra's `data:` |
| the board shows data but never changes | the table has no `key=` or no `@live`, or the Sutra's `data:` entry lacks `live: true`; check `/dash/data/changes?table=<name>` with `curl -N` |
| Flask: changes stall with several tabs open | run with `threaded=True` (or enough workers): each reader holds one stream |
| behind a reverse proxy the changes arrive in bursts | disable response buffering for the `/data/changes` path (a general proxy setting) |
| a sum shows 0 or the wrong figure | the function returned numbers as text; convert them (the CSV reader gives strings, as `read()` above does) |
| `406` on a rows request for Arrow | `pyarrow` is not installed, or the table does not declare `arrow` in its schema |
| port already in use | pick another with `--port` (uvicorn), `app.run(port=...)` (Flask; `live-orders`: `python flask_app.py 5001`) |
| a Sutra problem you cannot read | `python -m drishti_rachana lint <folder>` (extra `v8`) or `rachana lint`; the problem codes are in [Language](WRITING_SUTRAS.md#the-rachana-language-level-12) |

## The Rachana server

The standalone server (`java/rachana-server`) serves a project folder of Sutras as pages, the [Data Protocol](../spec/1/PROTOCOL.md) over
tables read from files, JDBC databases and REST endpoints and kept [live](IN_YOUR_APP.md#live-tables-editing-and-approvals), sign-in, access rules, and the guarded edit
path with approvals. It is one JVM process configured by one YAML file.

### 1. Run it

```bash
cd java && ./mvnw clean package -DskipTests && cd ..        # after python3 tools/js/build_js.py: the jar carries the runtime bundle
java -jar java/rachana-server/target/rachana-server-<version>-all.jar check --config examples/server-demo/rachana-server.yaml
java -jar java/rachana-server/target/rachana-server-<version>-all.jar serve --config examples/server-demo/rachana-server.yaml
java -jar java/rachana-server/target/rachana-server-<version>-all.jar hash-password      # type a password, get its hash for users.yaml
```

`check` reads the whole file, prints the resolved settings and every problem at once, and starts nothing (exit 0 when the file is
valid). `serve --port 0` takes any free port. The same commands from Node: `rachana serve --config <file> [--port n] [--check]` with
`--jar` or `RACHANA_SERVER_JAR`. In the IDE: the launchers **Rachana server serve (server-demo)**, **check** and **hash-password**
([DEVELOPING.md](DEVELOPING.md#build-and-run-in-the-ide)).

The example [examples/server-demo](../examples/server-demo/README.md) serves on `http://127.0.0.1:8090/` (user `dev`, password
`dev-only-pass`, development only).

### 2. The configuration file

Every key is shown, with its default and meaning, in
[rachana-server.example.yaml](../java/rachana-server/rachana-server.example.yaml); that file is the one reference. The sections:

| Section | What it sets |
|---|---|
| `server` | `bind` (loopback by default), `port`, `origins`, `cookie-secure`, `behind-tls-proxy`, session lifetimes, `max-body` |
| `project` | `dir`: `sutras/` (any depth), `rachana_libs/`, `static/`, and `*.live.yaml` declarations (also in `live/`); `watch` reloads on change |
| `libraries` | more library folders or `.rlib` files ([Libraries](WRITING_SUTRAS.md#libraries-and-rachana-lib)) |
| `assets.runtime` | serve another runtime bundle than the jar's |
| `drivers` | JDBC driver jars (none is bundled) |
| `tables` | each a Data Protocol table: `file` (`.csv`, `.json`, `.jsonl`/`.ndjson`, `.arrow`), `jdbc` + `query` + `params`, or `rest` + `path`; with `key`, `refresh`, `max-rows`, `where`, `columns`, `timeout`, `write` |
| `auth` | `mode`: `none` (loopback only), `users` (a users file of PBKDF2 hashes and roles) or `header` (a trusted reverse proxy's headers) |
| `access` | `default` (`signed-in` or `closed`), per table `read`, `unmask`, `edit {roles, columns}`, `approve`; per Sutra the roles that may open it |
| `edits` | `journal` (the outbox), `off` (tables whose edits are switched off), `hold` (tables whose changes wait for an approver) |

**Paths are explicit**: absolute, or from `${config}` (the configuration file's folder) or `${project}` (`project.dir`). A bare relative
path is refused. **Secrets are never written in the file**: a database password is `password-env` or `password-file`, a secret
header is `header-env`.

### 3. What it serves

| Address | What |
|---|---|
| `/` | the index of the Sutras the caller may open |
| `/v/<name>` | a Sutra's page: `<rachana-view>` over the Data Protocol, live |
| `/sutras/<name>.sutra.yaml` | the Sutra's text |
| `/data/v1/...` | the Data Protocol: tables, rows, query, changes, writes |
| `/static/...` | the project's `static/` folder |
| `/signin`, `/signout` | sign-in (users mode) |
| `/healthz`, `/metrics`, `/problems` | health, counters, and the Sutras and declarations set aside with their problems |

Tables are read with the reader's access applied: rows and columns they may not read are never sent, masked columns arrive masked.

### 4. Security

- Loopback by default. Binding another address needs `auth.mode: users` or `header`; `users` on a non-loopback address also needs
  `behind-tls-proxy: true` (a TLS proxy in front) and should set `cookie-secure: true`.
- A session ends after `session-idle` unused and `session-max` after sign-in.
- Every write needs the page's CSRF token (`X-Rachana-CSRF`, given in the page) and an allowed `Origin`.
- JDBC connections are read-only by default and rolled back after each read; queries take `:name` parameters through prepared
  statements.
- The log is one JSON line per event (requests, tables, writes, refusals), the audit trail of the edit path.

### 5. Writes back to the source

A table with `write:` takes the changes of its `*.live.yaml` declaration's `edit:` ([Live tables](IN_YOUR_APP.md#4-editing)):

| Source | `write:` | How each change applies once |
|---|---|---|
| `.csv`, `.json`, `.jsonl`, `.ndjson` file | `{ journal: "${config}/data/<t>.applied.jsonl" }` | the file is replaced atomically and the change id recorded in the journal |
| JDBC | `{ table: <db table>, journal-table: <db table> }` | the update and the change id are written in one transaction |

An `.arrow` file is never written to. The tutorial [Edit a live table with approvals](TUTORIAL.md#part-3-edit-a-live-table-with-approvals) runs it end to end.

## Live tables, editing and approvals

A **live table** is a table that stays current while people look at it: rows arrive and change by key, each reader sees the rows
their access allows, and, when the table is declared editable, readers with the right may change cells. Changes can be held for a
second person to approve (four eyes). The engine is `java/rachana-live`; the [Rachana server](IN_YOUR_APP.md#the-rachana-server) runs it over files, JDBC and
REST sources. The protocol additions are [PROTOCOL.md section 16](../spec/1/PROTOCOL.md); the design and its
reasoning are [DESIGN.md](DESIGN.md#live-tables-design).

### 1. How it works

- **Snapshot and stream.** A table reads its rows from a snapshot source (a file, a database query, a REST answer) and applies
  changes from a stream. The server's stream reads the source again every `refresh` and sends what changed by key.
- **Merge by key and version.** Rows are matched by the declared key. With a `version:` column an arriving row whose version is not
  above the held one is dropped, so changes never go back or repeat.
- **One shared table, per-reader views.** The rows are held once; each reader gets a view made by the host's access rules (row
  filter, masked columns, editable columns), never stored beyond the request and that reader's subscription.
- **Windows.** A reader showing a page of a large table follows only the keys it shows, plus the new total; bursts are coalesced per
  key and bounded, so a busy table never queues without limit.
- **Gaps.** When a stream loses its place the table starts a new epoch and every reader reads again; a stream whose source is down
  is `paused` and the table keeps serving what it has.

### 2. The declaration

One file per table, `<name>.live.yaml`, in the project folder or its `live/` folder. It names a configured table as its `snapshot`
(and `stream`) and says how it is shown and edited:

```yaml
rachana: 1
live-table: orders           # the table's name in the Data Protocol
title: Orders
snapshot: orders             # a table of rachana-server.yaml
stream: orders               # follow its changes
key: ID
version: V                   # needed for edits: the version a change is based on
changed-by: BY_USER          # optional: who made the row's last change
tray: true                   # serve the pending changes as orders.changes
columns:
  - { name: ID, label: Order }
  - { name: V, type: integer }
  - { name: QTY, type: integer, label: Quantity }
  - { name: BY_USER, label: Changed by }
edit:                        # absent: read-only
  fields:
    - { id: qty, bind: QTY, type: number, min: 0 }
```

Other keys: `where` (an expression every row must hold), `sort`, `group-by`, `subtotals`, `changed-at`, `retention` (`ttl`, `on`,
`max-rows`), `narrow` (a list must filter on one of the named columns first) and `heavy: true` on a column (left out of list pages,
read one row at a time). A declaration is checked whole when it is loaded; one with problems is set aside and listed, never
half-served. `rachana lint` checks `*.live.yaml` files with the Sutras.

### 3. Showing a live table

A Sutra needs no special syntax: a `data:` entry names the table, and two fragments of the core library draw it.

```yaml
libraries: [core@1]
data:
  orders:  { table: orders, key: ID, live: true }
  pending: { table: orders.changes, key: change, live: true }
panels:
  - use: core/live-table@1
    with: { title: Orders, rows: $.orders, order: V, edit: cells,
            columns: [ { label: Order, bind: "@.ID" }, { label: Quantity, bind: "@.QTY", fmt: amount0 } ] }
  - use: core/held-changes@1
    with: { changes: $.pending }
```

`core/live-table@1` is the grid: a window of rows kept current, "N new" for rows outside the page, the stale and paused banners, and
editing in the cells (`edit: cells`) or in a record panel beside the grid (`edit: beside`). Only the columns the schema offers this
reader under `write` are editable. `core/held-changes@1` lists the reader's own pending changes and, for an approver, the held changes
of others to release or reject. Their parameters are on the [core library page](../libraries/core/README.md).

### 4. Editing

A write is accepted only when all four hold, checked on the server for every request:

1. the declaration has `edit:` naming the column;
2. the table's edits are switched on (`edits.off` in the server configuration lists tables switched off);
3. a write binding exists (the server: `write:` on the configured table and an `edits.journal`);
4. the access rules let this reader edit this row and column (`access.tables.<name>.edit`).

A change names the row's key, the version it was made on (`basedOn`), the columns it sets and a change id chosen by the client:

```json
{ "id": "c1", "key": ["A-1"], "basedOn": 1, "set": { "QTY": 12 } }
```

It is checked by the same form rules the page ran (`type`, `required`, `pattern`, `min`, `max`, `options`), then by the version
guard. The answers:

| Answer | When |
|---|---|
| `200 {change, state: applied \| queued \| held \| sent, message}` | accepted |
| `409 rdp.write.conflict` with `current: {version, by}` | the row changed since `basedOn` |
| `422 rdp.write.invalid` with `fields: [{column, rule, message}]` | a form rule failed |
| `403 rdp.write.forbidden` | edits off, no right, a column not editable, or no CSRF token (audited) |
| `404` | the row is outside the reader's scope, as if absent |

**Exactly once.** The change is written to the outbox journal before it is acknowledged, sent to the source once, and sent again with
the same change id after a crash; the source records each applied id, so a change never applies twice. The same change id sent again
answers the first answer. The edit shows when the source's own change arrives on the stream, never before.

### 5. Approvals (four eyes)

A table listed in the server's `edits.hold` keeps every change `held` until an approver decides it. Approvers are the roles in
`access.tables.<name>.approve`. A change is never decided by its maker.

- The pending changes are the table `<name>.changes` (columns `change`, `state`, `summary`, `by`, `at`, `message`): each reader sees
  their own; an approver also sees the held changes of others whose rows they may read.
- An approver decides with a write to that table: `{"id": "d2", "key": ["c1"], "basedOn": "held", "set": {"state": "released"}}`, or
  `"rejected"` with a `reason`.
- A released change is applied as any other; every step (`write-submitted`, `write-held`, `write-released`, `write-sent`,
  `write-refused`) is in the server's audit log.

The tutorial [Edit a live table with approvals](TUTORIAL.md#part-3-edit-a-live-table-with-approvals) runs the whole path.

### 6. In another host

`rachana-live` is a library: a JVM host installs `LiveTables` with its own snapshot sources and streams (`SnapshotSource`,
`LiveStream`), an `Authoriser` (who sees and edits what), an `AuditSink`, and either Rachana's `Outbox` with a `ChangeWriter` for its
source, or its own `ChangeSink`. `LiveTables.screen(name)` answers the Sutra that shows a declared table with the two fragments above,
so a declaration alone gives a screen. The live-table cases of the conformance suite run against such a host with
`tools/conformance/protocol_check.py --live` or `--base ... --with-live`.

## The Rachana Data Protocol

The Data Protocol is how a Rachana view gets **tables** from a **host** and hears about **changes** to them. Any server that speaks
it can feed any Sutra; any client that speaks it can paint one. The normative text and its JSON Schemas are
[spec/1/data-protocol/PROTOCOL.md](../spec/1/PROTOCOL.md) (`schema.json`, `page.json`, `filter.json`,
`patch.json`, `error.json`, `bridge.json`). This guide shows how the parts fit and which implementation to use.

### 1. The parts

| Part | Section of PROTOCOL.md | In one line |
|---|---|---|
| Tables and schema | 2, 3 | a name, typed columns, an optional key and the capabilities the table declares in `can` |
| HTTP resources | 4 | `GET {base}`, `GET {base}/tables`, `GET {base}/tables/{name}`, `GET .../rows`, `POST .../query`, `GET` or `POST {base}/changes` |
| Rows | 5 | JSON (columns or objects), Apache Arrow IPC, NDJSON |
| Query | 6, 7 | sort, cursor or offset paging, grouping with totals, and a filter syntax tree (never a query string) |
| Live changes | 8 | keyed patches (`reset`, `remove`, `update`, `add`) numbered by `{epoch, seq}`, over the events wire protocol, SSE or WebSocket |
| Security | 9, 11 | the host authenticates and authorises; masked values never leave it |
| Errors | 10 | one shape: `{error: {code, message, ...}}` with `rdp.*` codes and HTTP statuses |
| In-process bridge | 12 | the same messages over a desktop web view's message channel, no HTTP |
| Limits, versions | 13, 14 | sizes per page, patch and message; both sides ignore what they do not know |
| Live tables | 16 | windowed subscriptions, writes, pending changes, narrowed lists (see [Live tables](IN_YOUR_APP.md#live-tables-editing-and-approvals)) |

A host must answer only `GET {base}/tables/{name}` and `GET {base}/tables/{name}/rows`; everything else is declared in `can` and a
reader never uses a capability the table does not declare. A request a host only half understood is refused
(`rdp.query.unsupported`), never answered with other rows.

### 2. A conversation

From the [server tutorial](TUTORIAL.md#part-3-edit-a-live-table-with-approvals) (base `http://127.0.0.1:8095/data/v1`, signed in):

```text
GET  /data/v1/tables/orders
{"name":"orders","title":"Orders","key":["ID"],"columns":[{"name":"ID","type":"string","label":"Order"}, ...],
 "can":["page","sort","filter","count","group","offset","changes","changes.filter","changes.window","replay","arrow","write"],
 "rowVersion":"V","changedBy":"BY_USER","write":{"columns":{"QTY":{"id":"qty","type":"number","min":0}},"reason":"none"},
 "live":{"state":"up"},"version":{"epoch":"e1","seq":0}}

GET  /data/v1/tables/orders/rows?shape=objects
{"table":"orders","version":{"epoch":"e1","seq":0},
 "rows":[{"ID":"A-1","V":1,"QTY":10,"BY_USER":"system"},{"ID":"A-2","V":1,"QTY":25,"BY_USER":"system"}],"total":2}
```

The reader then follows `GET /data/v1/changes?table=orders&after=e1:0` (Server-Sent Events): each frame is one patch, and a resume
after a lost connection sends `Last-Event-ID`. A host that cannot replay answers a `gap` and the reader reads the rows again.

### 3. Values

Types are `string`, `integer`, `decimal`, `number`, `boolean`, `date`, `timestamp`, `list` and the rest of section 3.1. A `decimal`
travels as text (`"140.00"`) so no digit is lost; aggregates over decimals are exact (34 digits, half to even) on every host. A
masked value travels as a marker, never as the value (3.2).

### 4. Implementations

| Use | Implementation |
|---|---|
| a standalone server over files, JDBC and REST | [the Rachana server](IN_YOUR_APP.md#the-rachana-server) (`java/rachana-server`) |
| a Flask or FastAPI app | `drishti_rachana.adapters.flask` / `.fastapi` over `drishti_rachana.server.DataHost` ([CLI and SDKs](IN_YOUR_APP.md#python)) |
| a static site with no server | `static_files_host.js`, written by `rachana build`: answers schema, filter, sort, columns and cursor paging from the site's files (no change stream) |
| a JVM host of your own | `java/rachana-data` (`TableProvider`, `ReferenceServer`, `TableClient`) and `java/rachana-live` for live tables |
| a desktop web view | the bridge messages of section 12, answered by the host application |
| the reader in a page | `DataClient` in `js/packages/rachana` (used by `<rachana-view>`) |

### 5. Checking a host

The `protocol` cases of the conformance suite run against any host that offers the test adapter of section 15:

```bash
python3 tools/conformance/protocol_check.py --reference                  # the JVM reference host (build java/ first)
uv run --with pyarrow python3 tools/conformance/protocol_check.py --python   # the Python DataHost
python3 tools/conformance/protocol_check.py --live                       # the live-table reference host, live cases too
python3 tools/conformance/protocol_check.py --base http://127.0.0.1:8080/rdp/v1   # your own host
```

Each case loads a fixture, sends its requests, reads the change stream and compares the answers; a results line per case and a
summary are written, as for the language cases ([CONFORMANCE.md](../conformance/CONFORMANCE.md#the-runner-contract)).

## Command line and SDKs

Rachana has one compiler, written in Java and built for JavaScript (TeaVM); Python runs the same JavaScript bundle on V8. Every answer
is byte-identical on the JVM, in Node, in the browsers and in Python (each runtime passes the same conformance suite). This guide
lists the ways in and when to use each.

| You want | Use |
|---|---|
| a dashboard site from files, no backend | the `rachana` command: `new`, `lint`, `preview`, `build` (static mode) |
| to edit Sutras visually | `rachana studio` ([Studio](WRITING_SUTRAS.md#rachana-studio)) |
| a server with sign-in, databases and live tables | `rachana serve` / the server jar ([Server](IN_YOUR_APP.md#the-rachana-server)) |
| screens inside your own web app | `<rachana-view>` and the JavaScript facade |
| screens inside a Flask or FastAPI app | the Python package `drishti-rachana` |
| to compile, lint or evaluate on the JVM | the Java modules (`com.ash.rachana`) |
| to check Sutras in CI | `rachana lint --format sarif`, or `python -m drishti_rachana lint` |

**Stability.** The JSON documents are the frozen contract: the ViewModel schemas, the Data Protocol and the conformance case format.
The JavaScript facade, `<rachana-view>`'s attributes and the Python SDK are marked experimental and may change in a minor release; the
Java entry surface (`Rachana`, `RachanaOptions`, `Node`, `ElCompiler`, `Expr`, `Formats`, `LibrarySet`, ... marked `[stable]` in
[java/api-surface.txt](../java/api-surface.txt)) is stable within major 1.

### The `rachana` command (Node 20+)

The CLI is `js/packages/rachana-cli/bin/rachana.js` (no npm dependencies). It needs the compiler bundles:
`python3 tools/js/build_js.py` (JDK 21) writes them to `js/packages/rachana/dist/`. Below, `rachana` stands for
`node js/packages/rachana-cli/bin/rachana.js`.

| Command | What it does |
|---|---|
| `rachana new <dir> [--template basic]` | a starter folder: `sutras/overview.sutra.yaml` at the newest level, `data/sales.csv`, `rachana.yaml`, `README.md` |
| `rachana lint [paths] [--format text\|json\|sarif] [--strict] [--libs <path>,...]` | compiles and lints every `*.sutra.yaml` and `*.live.yaml`, each at its declared level; exit 0, 1 on errors (`--strict`: also on warnings), 2 usage |
| `rachana preview <file> [--data <dir>] [--port <n>]` | one screen in the browser; `rachana dev <dir>` serves a folder |
| `rachana build [--src <dir>] [--out site] [--libs <path>,...]` | lints, then writes a static site: one page per Sutra, the data, the runtime and the painter |
| `rachana studio [folder] ...` | [Rachana Studio](WRITING_SUTRAS.md#rachana-studio) |
| `rachana lib <command> ...` | [library commands](WRITING_SUTRAS.md#4-rachana-lib) (needs a JVM) |
| `rachana serve --config <file> [--port n] [--jar file] [--check]` | [the server](IN_YOUR_APP.md#the-rachana-server) (needs a JVM) |

A built site needs no server of its own: the page answers the Data Protocol from `data/index.json` and the table files (schema,
filter, sort, columns, cursor paging; no change stream). Serve the folder with any static web server (a browser does not fetch from
`file://`).

#### The Java launcher

`lib` runs the Java implementation (one implementation, `com.ash.rachana.cli.Main`). Point the CLI at it with
`RACHANA_JAR` (a runnable jar) or `RACHANA_CLASSPATH` (the module jars), and `RACHANA_JAVA` for another `java`:

```bash
cd java && ./mvnw clean package -DskipTests && cd ..
export RACHANA_CLASSPATH="$(ls java/rachana-{cli,tools,core,api}/target/rachana-*.jar | grep -v -e sources -e tests | tr '\n' ':')"
```

On Windows (PowerShell): `$env:RACHANA_CLASSPATH = (Get-ChildItem java\rachana-cli\target\*.jar, java\rachana-tools\target\*.jar,
java\rachana-core\target\*.jar, java\rachana-api\target\*.jar | ForEach-Object FullName) -join ';'`. The same commands run without Node:
`java -cp "$RACHANA_CLASSPATH" com.ash.rachana.cli.Main lib ...`.

### JavaScript: the facade and `<rachana-view>`

```js
import * as rachana from "./js/packages/rachana/dist/rachana.runtime.js";   // an ES module; works under a strict CSP
rachana.init("core", "[]");
JSON.parse(rachana.evaluate(JSON.stringify({ expr: "$.a * 2", given: { record: { a: 21 } } })));   // {value: 42, text: "42", ...}
JSON.parse(rachana.check(JSON.stringify({ sutra: yamlText })));                                     // {ok, problems}
```

`rachana.runtime.js` compiles, binds and diffs; `rachana.tools.js` adds the editing tools (Studio uses it). In a page:

```html
<script src="/vendor/drishti-elements/3.3.1/drishti-elements.js"></script>
<script type="module" src="/rachana/src/rachana_view.js"></script>
<rachana-view src="/sutras/sales.sutra.yaml" data="/data/v1" runtime="/rachana/dist/rachana.runtime.js" live></rachana-view>
```

Attributes: `src`, `data` (the Data Protocol base), `runtime`, `formats`, `today`, `allow` (other data origins), `live`, `form`
(`json`, `arrow` or `ndjson` rows), `libraries`. Properties: `sutra` (text instead of `src`), `record`, `controls`, `host` (the page's
handling of `notify` and `host` effects), `linkResolver`, `fetch`, `files`. Events: `rachana-view:ready`, `rachana-view:change`,
`rachana-view:error`, `rachana-view:write`. The painter is Drishti Elements, vendored under `js/vendor/drishti-elements/3.3.1/`.
The full list is the header of [rachana_view.js](../js/packages/rachana/src/rachana_view.js).

### Python

The package `drishti-rachana` (folder `python/`, import `drishti_rachana`) holds no language logic of its own: it runs the JavaScript
bundle on V8.

```bash
python3 tools/js/build_js.py             # the bundles, also into the package's bundle folder
pip install -e 'python[v8,flask]'        # extras: v8, flask, fastapi, arrow, polars, conformance, test
python -m drishti_rachana lint examples/static-sales        # 3 file(s), 0 failing problem(s)
```

| Module | What it is |
|---|---|
| `drishti_rachana.engine` | `Rachana()`: `check(sutra)`, `evaluate(expr, record=...)`, `template`, `format`, `view`, `edit` |
| `drishti_rachana.adapters.flask`, `.fastapi` | `RachanaBlueprint` / `RachanaRouter`: mount a folder of Sutras, `@table(name, key=...)`, `@live(name)`; routes `/`, `/page/<name>`, `/sutras/...`, `/static/...`, `/data/...` |
| `drishti_rachana.server` | `DataHost`: a framework-neutral Data Protocol host (JSON, Arrow IPC, NDJSON, filtered and conflated SSE change streams, `can` enforced, masked columns) |
| `drishti_rachana.protocol` | the protocol's messages as dataclasses |
| `python -m drishti_rachana lint \| conformance run` | lint (`--format text\|json\|sarif`, `--strict`, `--libs`), the conformance runner |

```python
from drishti_rachana.engine import Rachana
r = Rachana()
r.check(open("examples/static-sales/overview.sutra.yaml").read())    # {'ok': True, 'problems': []}
r.evaluate("$.a * 2", record={"a": 21})                              # {'value': 42, 'type': 'number', 'text': '42', 'reads': ['$.a']}
```

A whole live app in about thirty lines: [examples/live-orders](../examples/live-orders/README.md) (Flask and FastAPI), walked through
in the tutorial [A live board in Flask](TUTORIAL.md#part-4-a-live-board-in-flask).

### Java

The reactor `java/` (group `com.ash.rachana`, Java 21, named modules): `rachana-api` (the `Node` value interface), `rachana-core` (the
compiler, binder and ViewModel), `rachana-tools` (generators, library tool), `rachana-data` (the Data Protocol host and client),
`rachana-live` (live tables), `rachana-server`, `rachana-cli`, plus the conformance runtime and the guards.

```java
Rachana rachana = Rachana.with(RachanaOptions.DEFAULTS, Profile.core());
Expr e = rachana.compiler().compile("upper(@.DESK)");
```

Building, testing, the IDE launchers and installing a release for another product: [DEVELOPING.md](DEVELOPING.md#java-reactor).
