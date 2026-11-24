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
# Production deployment (Linux)

How to run Drishti in production: one machine or a cluster, as services under a dedicated user, behind TLS, with secrets in a
protected file, a real database, backups and upgrades. **Production is Linux only.** Every command below is exact; where a setting has a
full table, this guide links it instead of repeating it ([CONFIGURATION.md](admin/CONFIGURATION.md),
[CLI_REFERENCE.md](guides/CLI_REFERENCE.md), [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md), [CLUSTER_GUIDE.md](guides/CLUSTER_GUIDE.md)).
The developer and QA path (clone, demo lake, IDE) is [DEPLOYMENT_AND_QA_GUIDE.md](DEPLOYMENT_AND_QA_GUIDE.md); **the demo profile is not for
production**: it carries development secrets and the development administrator.

**Conventions.** `<release>` is the release's folder name; `<host>` is the machine's real address (`ip route get 1.1.1.1`, the `src`
address), never `localhost`. Commands that change the machine run as root (`sudo`); the services run as the user `drishti`.

## Contents

1. [What you need](#1-what-you-need)
2. [Lay out the machine](#2-lay-out-the-machine)
3. [Install a release](#3-install-a-release)
4. [The settings and secrets](#4-the-settings-and-secrets)
5. [Database](#5-database)
6. [Run as services (systemd)](#6-run-as-services-systemd)
7. [Cluster (more nodes)](#7-cluster-more-nodes)
8. [TLS and the reverse proxy](#8-tls-and-the-reverse-proxy)
9. [Check it works](#9-check-it-works)
10. [Operate: logs, health, backups](#10-operate-logs-health-backups)
11. [Upgrade](#11-upgrade)
12. [Security checklist](#12-security-checklist)
13. [Troubleshooting](#13-troubleshooting)

## 1. What you need

| Need | Detail |
|---|---|
| **Linux**, 64-bit, systemd | Ubuntu 22.04+ or RHEL 9+ |
| **Java 21**, a JDK | the distribution's `openjdk-21-jdk` / `java-21-openjdk-devel`, or a Temurin 21 `.tar.gz`; `java -version` says 21 |
| **A Python 3.13 base** | the release's wheel pack is for Python 3.13. Use the distribution's `python3.13` package where it has one, else build python.org's source once into `/opt/python3.13` (below). The operating system's own `python3` is not used when it is not 3.13, and a distribution's Python refuses `pip install` into itself (PEP 668): Drishti's libraries never go into it |
| **The release** | `drishti-platform-<release>-source.zip`: the platform folder (`build_all.py`, `drishti/` and the products it is built on). There is no binary archive: the release is built on the machine once with `build_all.py` ([section 3](#3-install-a-release)), which needs the JDK 21 and Maven's libraries (a company mirror, or a filled `~/.m2`) |
| **The offline wheel pack** | `drishti-platform-<release>-wheels-linux-x64-py313.zip`: every Python library the console and the server's Python work need, and the pinned Lake wheel. No package index is needed on the machine |
| **A database** | PostgreSQL for a cluster or any shared use ([section 5](#5-database)); SQLite is the default of a single node |
| **Memory** | the server's heap plus about 1 GB, plus RAM for the page cache of the mapped hot files; the console about 1 GB (4 worker processes). About 4 GB of heap per 400,000 trades a day, 8 GB for a million or more |
| **Disk** | the data folder (lakes if local, hot files, caches, logs, dumps): size it with the sizing planner |
| **A reverse proxy** with a certificate | nginx or the bank's load balancer ([section 8](#8-tls-and-the-reverse-proxy)) |
| **NTP** | clocks within 60 seconds on every node of a cluster |

Nothing else is required: no Kafka or other broker, no Docker, no internet. Kafka, AI providers and the public data feeds are optional
and off as shipped; a stream connector whose broker is not there waits and says so, and the rest works.

**The Python 3.13 base** (once per machine, as root; skip the build where the distribution gives you `/usr/bin/python3.13`, and use that
path below in place of `/opt/python3.13/bin/python3.13`):

```bash
# the distribution's -dev packages for ssl, zlib, ffi, sqlite, bz2, lzma and readline first, then:
tar xf Python-3.13.<n>.tgz && cd Python-3.13.<n>
./configure --prefix=/opt/python3.13 --enable-optimizations
make -j"$(nproc)"
sudo make altinstall                        # altinstall: it never replaces the system's python3
/opt/python3.13/bin/python3.13 --version    # Python 3.13.<n>
```

**Never** use `--break-system-packages`. **Drishti never installs Python packages**: you install the libraries yourself with the pip command below, and `setup` only checks them. `setup` also refuses an operating system's own Python in one line:
*this Python is managed by the operating system: run setup with a Python you prepared for Drishti (a dedicated Python 3.13, e.g.
/opt/python3.13, see PRODUCTION_DEPLOYMENT.md; your own virtual environment is fine)*; a wheel pack for another Python version is one line
naming both versions. **Drishti never makes a virtual environment**: the Python is yours. The recommended one is the dedicated Python
above (a Python in its own prefix carries no `EXTERNALLY-MANAGED` marker); a virtual environment you make yourself from it works the same
(use its `python` wherever this guide says `/opt/python3.13/bin/python3.13`). You install the libraries into that Python (section 3), `setup` checks them, and both services
run it, owned by root and read-only to the service user.

**Sizing.** Before a large deployment, plan it: `python3 tools/sizing_planner.py --out <folder>` writes the single-node planner workbook (the
committed copy is `docs/architecture/Drishti-Sizing-Planner.xlsx`), and **Admin → Capacity** compares today against the plan
([CLUSTER_GUIDE.md › Sizing](guides/CLUSTER_GUIDE.md#15-sizing-memory-cpu-and-disk), [OPERATIONS_GUIDE.md › Memory, caches and capacity](guides/OPERATIONS_GUIDE.md#14-memory-caches-and-capacity)).

## 2. Lay out the machine

```text
/opt/drishti/<release>/        the platform folder of one release (build_all.py, the products); read only to the service user
/opt/drishti/<release>/drishti/      the install folder (DRISHTI_HOME)
/opt/python3.13/                    the Python 3.13 Drishti runs with, its libraries installed by setup (drishti.python)
/srv/drishti/data/             the data folder (DRISHTI_DATA_DIR): everything Drishti writes; owned by drishti
/etc/drishti/prod.ini          the settings file: folders, lakes, databases, brokers (no secret in it)
/etc/drishti/secrets.ini       optional: passwords of the databases, brokers and lakes, given last (mode 600)
/etc/drishti/secrets.env       the secrets as environment variables (mode 600, root)
/etc/drishti/drishti.env       the non-secret environment of both services (mode 644)
/etc/drishti/release.env       which release runs (the file to change on an upgrade)
/etc/drishti/server.args       the server's JVM options
```

```bash
sudo useradd --system --home-dir /srv/drishti --shell /usr/sbin/nologin drishti
sudo mkdir -p /opt/drishti /srv/drishti/data /etc/drishti
sudo chown drishti:drishti /srv/drishti/data
sudo chmod 750 /srv/drishti/data
```

What the data folder holds (made by the server as it needs them; `<data>` is `/srv/drishti/data`):

```text
<data>/
  identity/    users, roles, audit, settings (drishti.db: SQLite; unused when the identity database is PostgreSQL)
  fstore/      the content store: designs, collaboration, proposals, load history, BI items
  governance/, packs/, loads/, reports/, net/    state of the server
  delta/       the lakes, when they are local
  cache/, state/, derived/, index/, bi-store/     caches and markers (rebuilt from the sources)
  logs/        server.log, gc-server-<pid>.log
  dumps/       heap dumps (dumps/server)
  tmp/         tmp/server and tmp/console: each process's temporary files, removed on a clean stop
  console/     what the console keeps between runs
  nodes/<node>/net/    a cluster node's Drishti*net folder (section 7)
```

Logs, dumps and temporary files are under the data folder by design, never in the system's `/tmp`. Put the data folder on a file system
with room for the hot files and a free-space guard that holds (`drishti.disk.min-free-percent` 10, `drishti.disk.min-free-gb` 20 by
default). Local lakes sit on the data disk or on a mount named in their lake file ([4.3](#43-lakes)); a cluster's are shared.

## 3. Install a release

```bash
# as root. 1. unpack the release into its own folder (one folder per release, never over another) and build it once: the products,
#    then Drishti's server jar into drishti/lib and its module packages into drishti/modules (no tests)
sudo mkdir -p /opt/drishti/<release> && sudo unzip -q /root/drishti-platform-<release>-source.zip -d /opt/drishti/<release>
cd /opt/drishti/<release> && sudo /opt/python3.13/bin/python3.13 build_all.py --bundle . --skip-tests
#    (when the zip holds one top folder, cd into it first: the folder with build_all.py is the platform folder)

# 2. the server jar: exactly one, drishti-server-<version>-exec.jar, in lib/ (the start refuses two)
ls /opt/drishti/<release>/drishti/lib/drishti-server-*-exec.jar
ls /opt/drishti/<release>/drishti/modules/*.dmod | head -3          # the module packages (a release carries them, never stale)

# 3. the Python libraries: YOU install them, from the wheel pack unzipped into /root/drishti-wheels, into the Python 3.13 you prepared
#    (Drishti runs no pip: not setup, not start, not the server's compute environments)
PY=/opt/python3.13/bin/python3.13
cd /opt/drishti/<release>/drishti/drishti-console
sudo $PY -m pip install --no-index --find-links /root/drishti-wheels -r requirements.txt
#    then write /etc/drishti/prod.ini (4.1) and let setup CHECK the libraries (it prints "Drishti is ready." when all is in order, else the exact pip command above)
sudo $PY /opt/drishti/<release>/drishti/tools/drishti.py --settings /etc/drishti/prod.ini setup

# 4. the install is root-owned and not writable by the service user
sudo chown -R root:root /opt/drishti/<release> && sudo chmod -R go-w /opt/drishti/<release>
```

The wheel pack (`drishti-platform-<release>-wheels-linux-x64-py313.zip`) is unzipped into `/root/drishti-wheels`; `requirements.txt` lists the
pinned Lake wheel and every library of the console and of the server's Python work, and `--no-index` keeps pip off the network. `setup`
**only checks** the libraries of the Python that runs it: when something is missing it prints the pip command above for that Python and
changes nothing; when all is in order it says "Drishti is ready." It still writes the settings file, `drishti.python` and the JVM argument
files. The server's compute environments run with `drishti.python` as it is: a missing library is a clear error naming it and the pip
command, never an automatic install. The Python is shared by every release on the
machine, so a release whose pins differ changes it for the others: keep one release per machine, or give each release its own Python (a
second prefix, or a virtual environment you make yourself) and name it in that release's `drishti.python`. Never run setup with the
operating system's own Python (it refuses in one line).

Run setup with `--settings /etc/drishti/prod.ini` in production: it then reads and keeps your settings file (never moves it), adds
`drishti.python` (the Python it ran with) to it, and writes no developer settings folder and no IDE JVM options in the home of whoever runs it.

Your own jars (JDBC drivers, connector plugins) go in `/opt/drishti/<release>/drishti/lib/ext`; copy them from the previous release on an upgrade.

## 4. The settings and secrets

### 4.1 The settings file

`/etc/drishti/prod.ini` holds every folder and named resource, by absolute path; the example with every lake, database and broker form is
`config/settings.examples/prod.ini`. A minimal file:

```ini
# /etc/drishti/prod.ini: no secret in this file; a value runs to the end of its line, so no comment after a value
[roots]
drishti.home=/opt/drishti/<release>/drishti
drishti.data.dir=/srv/drishti/data
drishti.modules.home=/opt/drishti/<release>/drishti/modules

[console.backend]
url=http://127.0.0.1:18480
```

`setup` (section 3) adds `drishti.python`, the Python the server starts for its Python work (feeds, compute workers); the console runs
under the same interpreter ([6.2](#62-the-console-unit)). Check the libraries against the file, without starting anything:

```bash
sudo /opt/python3.13/bin/python3.13 /opt/drishti/<release>/drishti/tools/drishti.py --settings /etc/drishti/prod.ini setup
```

Both services read the file (`-Ddrishti.settings.file=` on the server, `--settings` on the console, which takes only the keys under
`console.` and the keys it shares with the server). An explicit `-D` or command-line argument beats the files; the files beat the
environment. Admin → Configuration → Resolved paths shows every folder and the file it came from; passwords show as masked values.
The rules, sections and placeholders: [CONFIGURATION.md › The settings file](admin/CONFIGURATION.md#the-settings-file--ddrishtisettingsfile).

### 4.2 The secrets

Three secrets, each made once with `openssl rand -hex 32`, kept only in `/etc/drishti/secrets.env` (mode 600, owned by root; systemd reads
it as root). Never in `prod.ini`, in a unit file, under `config/`, in git or in a ticket.

```bash
sudo install -m 600 -o root -g root /dev/null /etc/drishti/secrets.env
sudo tee /etc/drishti/secrets.env >/dev/null <<EOF
DRISHTI_TOKEN_SECRET=$(openssl rand -hex 32)
DRISHTI_SESSION_SECRET=$(openssl rand -hex 32)
DRISHTI_NET_SECRET=$(openssl rand -hex 32)
DRISHTI_SEED_USERNAME=<the first administrator's user name>
DRISHTI_SEED_PASSWORD=<a long password of your own>
EOF
```

| Variable | Read by | What it is |
|---|---|---|
| `DRISHTI_TOKEN_SECRET` | server and console | signs the tokens of signed-in people; at least 32 bytes, **the same on the server and the console** (and on every node of a cluster). Whoever holds it can act as any user: treat it as a master key |
| `DRISHTI_SESSION_SECRET` | console | signs the console's session cookie; at least 32 characters |
| `DRISHTI_NET_SECRET` | every server of a cluster | the cluster key ([section 7](#7-cluster-more-nodes)); leave it out of a single node |
| `DRISHTI_SEED_USERNAME`, `DRISHTI_SEED_PASSWORD` | server | the first administrator, made at the first start with no users; see [4.4](#44-the-first-administrator) |

Database and broker passwords go in `/etc/drishti/secrets.ini` (mode 600), given **last** on the settings list so it wins, under the same
sections and keys as `prod.ini` (`config/settings.examples/secrets.ini`); a key named password, secret, token or key is masked in logs and in
Admin. The list is then `-Ddrishti.settings.file=/etc/drishti/prod.ini,/etc/drishti/secrets.ini`.

### 4.3 Lakes

A lake is one file per lake in `config/lakes/` with its tables; **where it is** is a section of `prod.ini`, so the release's `config/` stays
as shipped:

```ini
[lake.risk]
root=s3://bank-risk-lake/drishti
storage.s3.region=us-east-1
```

Local lakes use an absolute path (`root=/srv/drishti/data/delta/risk`); in a cluster every node reads the same lake at the same path
([7.3](#73-shared-data)). The lake loaders and the data inbox: [DATA_LAKE_GUIDE.md](guides/DATA_LAKE_GUIDE.md).

### 4.4 The first administrator

Sign-in is on in production (`DRISHTI_SECURITY_ENABLED=true`, `DRISHTI_AUTH_ENABLED=true`, [4.5](#45-the-environment-file)). The server creates the
first administrator at the first start with no users, from `DRISHTI_SEED_USERNAME` and `DRISHTI_SEED_PASSWORD` (the log says *Created the
first administrator ... with the password from the configuration*). Then:

1. Sign in, create the real administrators (**Admin → Users**), and give each their own password.
2. Disable or delete the first one if it was only for set-up.
3. Delete the two `DRISHTI_SEED_*` lines from `/etc/drishti/secrets.env`; once the store holds users they are not read again.

The built-in development administrator `drishti-dev-admin` with its default password is what a start gets when `DRISHTI_SEED_USERNAME` and
`DRISHTI_SEED_PASSWORD` are not set: never start production without the two variables. With `DRISHTI_SEED_ADMIN=false` and an empty store
**no** administrator is made at all. Single sign-on is an option (`DRISHTI_OIDC_*`,
[OPERATIONS_GUIDE.md › Security checklist](guides/OPERATIONS_GUIDE.md#11-security-checklist)).

### 4.5 The environment file

`/etc/drishti/drishti.env` (mode 644, no secret) is read by both services; `/etc/drishti/release.env` names the release that runs:

```bash
sudo tee /etc/drishti/drishti.env >/dev/null <<'EOF'
# the server
DRISHTI_SECURITY_ENABLED=true
DRISHTI_DEMO_ENABLED=false
DRISHTI_LOG_FILE=/srv/drishti/data/logs/server.log
DRISHTI_DRAIN_WAIT=240
SERVER_ADDRESS=127.0.0.1
# the console
DRISHTI_AUTH_ENABLED=true
DRISHTI_CONSOLE_HOST=127.0.0.1
DRISHTI_CONSOLE_PORT=17480
EOF
sudo tee /etc/drishti/release.env >/dev/null <<'EOF'
DRISHTI_HOME=/opt/drishti/<release>/drishti
DRISHTI_DATA_DIR=/srv/drishti/data
DRISHTI_MODULES_HOME=/opt/drishti/<release>/drishti/modules
EOF
```

`DRISHTI_DEMO_ENABLED` is `true` as shipped: set it `false`. `DRISHTI_CONSOLE_HOST=127.0.0.1` makes the console reachable only from this
machine, so only the reverse proxy reaches it ([section 8](#8-tls-and-the-reverse-proxy)); `SERVER_ADDRESS=127.0.0.1` does the same for the
server on a single machine (**remove it on a cluster node**, [section 7](#7-cluster-more-nodes)). `DRISHTI_SECURE_COOKIE` stays unset
(cookies are `Secure` by default). Leave `DRISHTI_AI_ENABLED`, `DRISHTI_MCP_ENABLED`, `DRISHTI_OFFLINE`, `DRISHTI_STUDIO_SAVE` and the data
inbox as shipped unless you want them ([12](#12-security-checklist)). Modules and engines are switched in `config/modules/<module>.yaml`
(`enabled: true/false`) and `config/engines/<engine>.yaml`; Admin → Modules and Admin → Engines show and change them, and a change takes
effect at the next start. Every variable: [CONFIGURATION.md](admin/CONFIGURATION.md#every-placeholder).

### 4.6 The JVM options

`/etc/drishti/server.args` is a Java `@argfile`, one quoted option per line: the flags `drishti.py start server` passes, with every folder
under the data folder. Change the heap here. The GC log's folder must exist or the JVM does not start (the unit makes it).

```text
"-Djava.io.tmpdir=/srv/drishti/data/tmp/server"
"-Xmx8g"
"-XX:+UseZGC"
"-XX:MaxDirectMemorySize=256m"
"-XX:TrimNativeHeapInterval=60000"
"-Djdk.nio.maxCachedBufferSize=262144"
"-XX:NativeMemoryTracking=summary"
"-XX:+HeapDumpOnOutOfMemoryError"
"-XX:HeapDumpPath=/srv/drishti/data/dumps/server"
"-Xlog:gc*:file=\"/srv/drishti/data/logs/gc-server-%p.log\":time,uptime:filecount=5,filesize=20m"
```

`-XX:+ZGenerational` goes on the unit's command line on Java 21 (it is the default from Java 23). A shipped starting point is
`deploy/jvm/server-service.args` (a 4 GB heap, data folder `/opt/drishti/data`: change the paths).

## 5. Database

Drishti keeps users, roles, sessions, audit and the content store in one **identity database**.

| | SQLite (the default) | PostgreSQL |
|---|---|---|
| For | a single node | a cluster, or any shared or heavy use (the access log alone adds millions of rows) |
| Where | `<data>/identity/drishti.db` | a database you run, with its own backup and high availability |
| Settings | none | `[db.identity]` (and `[db.store]` for the content store) in `prod.ini`, the password in `secrets.ini` |

PostgreSQL: create an empty database and a user that owns it. Drishti applies its **complete schema file** (`CREATE ... IF NOT EXISTS`,
idempotent, **no migrations**) at every start.

```bash
sudo -u postgres psql -c "CREATE ROLE drishti LOGIN PASSWORD '<password>'" -c "CREATE DATABASE drishti OWNER drishti"
```

```ini
# /etc/drishti/prod.ini
[db.identity]
url=jdbc:postgresql://db.example.internal:5432/drishti?sslmode=verify-full
user=drishti

# the content store, in the same database (config/store/store.yaml, backend: postgres)
[db.store]
url=jdbc:postgresql://db.example.internal:5432/drishti
user=drishti
```

```ini
# /etc/drishti/secrets.ini (mode 600)
[db.identity]
password=<password>

[db.store]
password=<password>
```

The content store's backend is chosen in its own file, `config/store/store.yaml` (`backend: postgres`; the ready-made variant is
`config/store.examples/postgres.yaml`; with `backend: filesystem` a cluster needs a shared mount at the same path on every node), and every
node of a cluster must read the same one. Settings and pools: [CONFIGURATION.md › `drishti.identity`](admin/CONFIGURATION.md#drishtiidentity--users-passwords-and-the-identity-database),
[› The content store](admin/CONFIGURATION.md#the-content-store-configstorestoreyaml). The server does not start when it cannot reach the
database. A cluster never uses SQLite. Backup and restore: [10.3](#103-backups).

## 6. Run as services (systemd)

Both units run the process **in the foreground** under systemd (`Type=simple`, the default): the server as the JVM itself, the console as
the Python of section 3 running `run_drishti_web.py`. Neither goes through `tools/drishti.py start`, which is for a terminal.

### 6.1 The server unit

`/etc/systemd/system/drishti-server.service` (the shipped `deploy/systemd/drishti-server.service`, with this guide's layout):

```ini
[Unit]
Description=Drishti server
After=network-online.target
Wants=network-online.target

[Service]
User=drishti
Group=drishti
WorkingDirectory=/srv/drishti
EnvironmentFile=/etc/drishti/drishti.env
EnvironmentFile=/etc/drishti/release.env
EnvironmentFile=/etc/drishti/secrets.env
Environment=MALLOC_ARENA_MAX=2
ExecStartPre=/usr/bin/mkdir -p /srv/drishti/data/logs /srv/drishti/data/dumps/server /srv/drishti/data/tmp/server
ExecStart=/bin/sh -c 'exec java @/etc/drishti/server.args -XX:+ZGenerational -Ddrishti.settings.file=/etc/drishti/prod.ini,/etc/drishti/secrets.ini -jar "$DRISHTI_HOME"/lib/drishti-server-*-exec.jar'
SuccessExitStatus=143
TimeoutStopSec=300
Restart=on-failure
RestartSec=5
LimitNOFILE=65536
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

- `exec java ...` in a shell lets the release's one jar be found by its name whatever its version (the shell expands the pattern), and
  `exec` makes the JVM the unit's main process, so SIGTERM reaches it.
- **The stop drains first.** On SIGTERM the server drains itself (`drishti.node.drain.on-stop`: in a cluster its forwarded work and VaR
  shards move to other members and readiness turns `OUT_OF_SERVICE`), waits at most `DRISHTI_DRAIN_WAIT` seconds (240), then shuts down
  gracefully (open requests finish, up to 30 s; exit 143 is a normal stop). `TimeoutStopSec` covers the drain, the graceful stop and a margin.
- If `java` is not on the unit's `PATH`, write your JDK's `bin/java` path in its place. Without a `secrets.ini`, leave it off the list.

### 6.2 The console unit

`/etc/systemd/system/drishti-console.service`:

```ini
[Unit]
Description=Drishti console
After=network-online.target drishti-server.service
Wants=drishti-server.service

[Service]
User=drishti
Group=drishti
WorkingDirectory=/srv/drishti
EnvironmentFile=/etc/drishti/drishti.env
EnvironmentFile=/etc/drishti/release.env
EnvironmentFile=/etc/drishti/secrets.env
ExecStart=/bin/sh -c 'exec /opt/python3.13/bin/python3.13 "$DRISHTI_HOME"/drishti-console/run_drishti_web.py --settings /etc/drishti/prod.ini'
Restart=on-failure
RestartSec=5
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

The unit names the Python setup wrote as `drishti.python` (another, such as a virtual environment of your own, in its place). The console reads
`console.backend.url` from `prod.ini` (or `DRISHTI_BACKEND_URL`; `DRISHTI_BACKEND_URLS` for a cluster's entry nodes), listens on
`DRISHTI_CONSOLE_HOST`:`DRISHTI_CONSOLE_PORT`, and runs `DRISHTI_CONSOLE_WORKERS` (4) processes. Its temporary folder is `<data>/tmp/console`,
its state `<data>/console`, its log standard output (the journal).

### 6.3 Enable and start

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now drishti-server
curl -s http://127.0.0.1:18480/actuator/health/readiness        # {"status":"UP"} when ready (the first start can take minutes)
sudo systemctl enable --now drishti-console
curl -s http://127.0.0.1:17480/readyz                           # {"status":"UP","server":"reachable"}
journalctl -u drishti-server -f                                 # the server's log; also <data>/logs/server.log
```

Start servers before consoles and stop them after (a console newer than its server cannot sign anyone in); the console unit's `After=`
does this on one machine.

## 7. Cluster (more nodes)

Several servers can act as one (**Drishti\*net**): each holds its packs and data, they find each other by gossip, a request for something
one node holds is forwarded to it, and the console moves to another server when its own stops. Choose a cluster when one machine's memory
or CPU is not enough, when the service must survive a machine's loss, or for upgrades without downtime. Production is **three machines**,
each with one server and one console, the lake and the database shared and highly available outside them
([CLUSTER_GUIDE.md › Three machines in production](guides/CLUSTER_GUIDE.md#23-three-machines-in-production)). Sections 1 to 6 apply to every
machine; what differs is below.

### 7.1 What is the same and what differs per node

| | Same on every node | Per node |
|---|---|---|
| `DRISHTI_TOKEN_SECRET`, `DRISHTI_SESSION_SECRET`, `DRISHTI_NET_SECRET` | yes | |
| The release, `prod.ini` lakes and packs, `config/modules`, `config/engines`, `config/store/store.yaml`, the identity database | yes | |
| `DRISHTI_DATA_DIR` | the same path (each machine has its own folder) | its own contents |
| `DRISHTI_NET_DIR` | | `/srv/drishti/data/nodes/<node>/net` |
| `DRISHTI_DATALOAD_ENABLED=true` and `DRISHTI_DATALOAD_OWNER=true` | | on one node only, when the data inbox is used |
| `SERVER_ADDRESS` | **not set** (remove it from `drishti.env`) | |

Add the node's folder to `/etc/drishti/drishti.env` on each machine: `DRISHTI_NET_DIR=/srv/drishti/data/nodes/<node>/net`. On the console
machines also `DRISHTI_BACKEND_URLS=http://<host-a>:18480` (one entry node is enough: the console learns the others and fails over) and,
for consoles behind one load balancer, `DRISHTI_CONSOLE_RELAY_ACROSS_HOSTS=true` with **one** `DRISHTI_CONSOLE_RELAY_KEY` in `secrets.env` on
all of them; open `17490-17493` between the consoles.

### 7.2 The node's Drishti\*net folder

Each node advertises its **real address**, never `localhost`, `127.0.0.1` or `0.0.0.0` (they are refused in `advertise-url`, in a seed's
URL and in every tool; [CLUSTER_GUIDE.md › Real addresses only](guides/CLUSTER_GUIDE.md#12-real-addresses-only)). On each machine, as the
service user, with the cluster key in the command's environment so the check can see it (the key itself is never written to a file; the
files hold only the reference `${DRISHTI_NET_SECRET}`):

```bash
PY=/opt/python3.13/bin/python3.13
D=/opt/drishti/<release>/drishti/tools/drishti.py
NET=/srv/drishti/data/nodes/drishti-b/net
KEY=$(sudo sed -n 's/^DRISHTI_NET_SECRET=//p' /etc/drishti/secrets.env)
sudo -u drishti mkdir -p "$NET"
sudo -u drishti env DRISHTI_NET_SECRET="$KEY" $PY $D net set node.node-id drishti-b --dir "$NET"
sudo -u drishti env DRISHTI_NET_SECRET="$KEY" $PY $D net set node.secret '${DRISHTI_NET_SECRET}' --dir "$NET"
sudo -u drishti env DRISHTI_NET_SECRET="$KEY" $PY $D net set node.advertise-url http://<host-b>:18480 --dir "$NET"
sudo -u drishti env DRISHTI_NET_SECRET="$KEY" $PY $D net set node.enabled true --dir "$NET"
sudo -u drishti env DRISHTI_NET_SECRET="$KEY" $PY $D net add-peer drishti-a <host-a>:18480 --dir "$NET"   # not on the seed itself
sudo -u drishti env DRISHTI_NET_SECRET="$KEY" $PY $D net check --dir "$NET"                              # exit 0: every file would be taken
```

`drishti-a` is the seed the others are given; it needs no peer. The other files of the folder (`gossip.yaml`, `routing.yaml`, `access.yaml`)
have defaults. `net list` shows the folder; Admin → Cluster → Files edits it with the same checks.

### 7.3 Shared data

Every node reads **the same lake at the same path**, and the same identity database and content store.

| Shared piece | How |
|---|---|
| The lake | an S3 or S3-compatible store (`root=s3://...`, nothing mounted: the recommended choice), or NFS mounted at the identical path on every node |
| The identity database and content store | PostgreSQL ([section 5](#5-database)); with `backend: filesystem` the content store's folder is a shared mount |

Each node's own data folder (caches, hot files, its net state) is not shared: it is rebuilt from the lake.

### 7.4 Ports and firewall

| Port | Between | Notes |
|---|---|---|
| 18480 (server) | console to server, server to server | open between every pair of machines; never to users |
| gossip port | server to server only | `gossip-port: auto` chooses a free port; set a number (`net set node.gossip-port 7946`) and open exactly that |
| 17480 (console) | the reverse proxy to the console | the proxy's port 443 faces the users |
| 17490-17493 | console to console | only with `DRISHTI_CONSOLE_RELAY_ACROSS_HOSTS=true` |

```bash
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<net-of-the-nodes>" port port="18480" protocol="tcp" accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<net-of-the-nodes>" port port="7946" protocol="tcp" accept'
sudo firewall-cmd --reload
# or: sudo ufw allow from <net-of-the-nodes> to any port 18480,7946 proto tcp
```

Member-to-member and console-to-server traffic can use TLS with mutual authentication (`server.ssl.*`, `https://` advertise URLs, `tls.*`
in `node.yaml`): use it where the network is not trusted ([CLUSTER_GUIDE.md › Security](guides/CLUSTER_GUIDE.md#16-security-the-key-mutual-tls-nodes-per-role)).

### 7.5 Start order

1. The shared pieces: the lake reachable, PostgreSQL up.
2. The seed server (`drishti-a`), then the others (any order).
3. The consoles last.

On each machine `sudo systemctl enable --now drishti-server`, then `drishti-console`. Check Admin → Cluster: every member alive, each with its
real address. A node stopped with `systemctl stop` drains itself first ([6.1](#61-the-server-unit)).

## 8. TLS and the reverse proxy

Users reach **only the console**, through a reverse proxy that terminates TLS. The server's `/api` is for the console; `/actuator` is for
monitoring only.

| Path | Exposed to users? |
|---|---|
| console `:17480`, everything | yes, through the proxy |
| server `:18480/api/...`, `/api/docs` | no |
| server `:18480/actuator/...` | no; monitoring only (with sign-in on only `/actuator/health` is open; the rest needs an administrator's token or `DRISHTI_METRICS_TOKEN`) |

The console listens on `127.0.0.1` ([4.5](#45-the-environment-file)); the proxy runs on the same machine, or on a balancer in front of the
consoles of a cluster (health check `GET /readyz`; no stickiness needed with the relay on). nginx:

```nginx
server {
    listen 443 ssl http2;
    server_name drishti.example.internal;
    ssl_certificate     /etc/ssl/drishti/fullchain.pem;
    ssl_certificate_key /etc/ssl/drishti/privkey.pem;

    location / {
        proxy_pass         http://127.0.0.1:17480;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_set_header   Connection        "";
        proxy_buffering    off;          # live updates
        proxy_read_timeout 1h;           # long-lived event streams
    }
}
server { listen 80; server_name drishti.example.internal; return 301 https://$host$request_uri; }
```

- **Live updates** use one long-lived Server-Sent Events connection per browser (`/api/channel`): the proxy must not buffer, its read
  timeout must be well above the 15-second heartbeat, and it speaks HTTP/1.1 to the console.
- **Cookies** are `Secure` by default, `HttpOnly` and `SameSite=Lax`: leave `DRISHTI_SECURE_COOKIE` unset once TLS is in place.
- **Cross-site requests**: the console refuses a state-changing request whose `Origin` is not its own host (`403 DRS-5002`); keep
  `proxy_set_header Host $host`. If your proxy changes the host, list the public origin in `auth.allowed_origins` in
  `drishti-console/config/application.local.yaml`.
- **Single sign-on** behind a proxy: set `DRISHTI_OIDC_REDIRECT_URI=https://drishti.example.internal/auth/oidc/callback` and register exactly
  that URL.
- **At a sub-path** (`https://host/drishti/`): `DRISHTI_CONSOLE_BASE_PATH=/drishti`; the proxy may keep or strip the prefix
  ([OPERATIONS_GUIDE.md › TLS and the reverse proxy](guides/OPERATIONS_GUIDE.md#113-tls-and-the-reverse-proxy)).
- Do not add or loosen a content security policy at the proxy: the console sets a strict one.

## 9. Check it works

From the machine, then from outside:

```bash
systemctl is-active drishti-server drishti-console                        # active, active
curl -s http://127.0.0.1:18480/actuator/health/liveness                   # {"status":"UP"}: the process is up
curl -s http://127.0.0.1:18480/actuator/health/readiness                  # {"status":"UP"}: joined, newest dates open, shards ready
curl -s http://127.0.0.1:17480/healthz                                    # the console answers
curl -s http://127.0.0.1:17480/readyz                                     # the console reaches the server
curl -s http://127.0.0.1:18480/api/v1/sources                             # 401 DRS-5010 "missing bearer token": sign-in is on
curl -sI https://drishti.example.internal/healthz | head -1               # HTTP/2 200 through the proxy
```

Then in the browser: a private window goes to the sign-in page; sign in as the first administrator ([4.4](#44-the-first-administrator));
**Admin → Health** is OK, shows no default-password warning (`GET /api/v1/admin/status` reports `"defaultAdminPasswordInUse": false`) and
lists the folders under `/srv/drishti/data`; **Admin → Configuration → Resolved paths** shows each root and the file it came from;
**Admin → Modules** lists the modules you expect; **Admin → Cluster** shows every member alive. `drishti.py server health` and `doctor`
print the checks from a terminal (`--token-file` names a file holding an administrator's personal API token):

```bash
/opt/python3.13/bin/python3.13 /opt/drishti/<release>/drishti/tools/drishti.py server health --server http://127.0.0.1:18480 --token-file /root/drishti.token
```

## 10. Operate: logs, health, backups

### 10.1 Logs, dumps and temporary files

| What | Where |
|---|---|
| server log | `journalctl -u drishti-server`; and `<data>/logs/server.log` (`DRISHTI_LOG_FILE`), rotated by the server (100 MB a file, compressed, 30 days, 1 GB at most: `drishti.logging.*`) |
| the JVM's GC log | `<data>/logs/gc-server-<pid>.log` (5 files of 20 MB) |
| console log | `journalctl -u drishti-console` |
| heap dumps | `<data>/dumps/server` (`drishti.disk.max-dumps`, 2: the oldest go at the next start) |
| temporary files | `<data>/tmp/server`, `<data>/tmp/console`; the left-overs of a crash are listed on Admin → Health for you to remove by hand |

### 10.2 Health and monitoring

| URL | Auth | Use |
|---|---|---|
| `:18480/actuator/health/liveness` | none | restart the process if it fails |
| `:18480/actuator/health/readiness` | none | send traffic only when `UP`; `DOWN` (503) while an engine opens its newest date or the node joins; `OUT_OF_SERVICE` while drained |
| `:18480/actuator/info` | administrator or metrics token | the running version |
| `:18480/actuator/prometheus` | `Authorization: Bearer $DRISHTI_METRICS_TOKEN` | metrics for Prometheus (examples in `deploy/prometheus/`, `deploy/grafana/`) |
| `:17480/healthz`, `:17480/readyz` | none | the console (liveness, readiness) |

Set `DRISHTI_METRICS_TOKEN` (long, random, in `secrets.env`) before scraping with sign-in on. `drishti.py node wait-ready` waits for
readiness; `drishti.py node drain`, `undrain` and `status` act on one server ([CLUSTER_GUIDE.md › Drain before a stop](guides/CLUSTER_GUIDE.md#81-drain-before-a-stop)).
Alerts and dashboards: [OPERATIONS_GUIDE.md › Monitoring and health](guides/OPERATIONS_GUIDE.md#13-monitoring-and-health).

### 10.3 Backups

Back up **what Drishti writes and what you configured**; caches are rebuilt. The full table is
[OPERATIONS_GUIDE.md › Backups and restore](guides/OPERATIONS_GUIDE.md#17-backups-and-restore).

| What | Back up? | How |
|---|---|---|
| the identity database | **daily** | SQLite: `sqlite3 /srv/drishti/data/identity/drishti.db ".backup '/backup/drishti/identity.db'"` (safe while running); PostgreSQL: `pg_dump -Fc drishti` |
| `<data>/fstore/`, `governance/`, `packs/`, `loads/`, `reports/`, `net/` and `nodes/*/net/` (the node's `node-id` and net files) | **yes** | copy |
| the site's changes under the release's `config/` (modules, engines, connector overrides, tables, BI), `lib/ext`, your own Sutras | **yes**, in version control | commit or copy |
| `/etc/drishti/` (`prod.ini`, `secrets.ini`, `secrets.env`, units) | **yes**, securely | copy; the secrets also in your vault |
| the lakes (`<data>/delta/` or the bucket) | by their owners | your data platform's policy |
| `<data>/cache`, `derived`, `index`, `bi-store`, `tmp`, `dumps`, `logs` | no | rebuilt, or diagnostics |
| `<data>/state/<connector>/` of ActiveMQ and RabbitMQ connectors | if used | with the server stopped |

```bash
#!/usr/bin/env bash
# /usr/local/sbin/drishti-backup.sh: keep 14 nightly archives of Drishti's own state (run from cron as root)
set -euo pipefail
D=/srv/drishti/data; stamp=$(date +%Y%m%d-%H%M); mkdir -p /backup/drishti
[ -f $D/identity/drishti.db ] && sudo -u drishti sqlite3 $D/identity/drishti.db ".backup '$D/identity/backup.db'"
keep=(); for p in fstore identity governance packs loads reports nodes; do [ -e $D/$p ] && keep+=("$p"); done
tar czf /backup/drishti/drishti-$stamp.tgz -C $D --exclude=identity/drishti.db* "${keep[@]}" /etc/drishti
ls -1t /backup/drishti/drishti-*.tgz | tail -n +15 | xargs -r rm --
```

**Restore:** `systemctl stop drishti-console drishti-server`; move the damaged folder aside; unpack the archive into `/srv/drishti/data`
(with SQLite `mv identity/backup.db identity/drishti.db`); `chown -R drishti:drishti /srv/drishti/data`; start; sign in and check Admin → Users
and Admin → Audit. A PostgreSQL database is restored with `pg_restore` before the start.

### 10.4 Lake maintenance and secret rotation

Lake compaction and the lake build run as their own scheduled units ([OPERATIONS_GUIDE.md › Lake maintenance and scheduling](guides/OPERATIONS_GUIDE.md#16-lake-maintenance-and-scheduling),
`deploy/lake-maintenance.yaml`). Rotate `DRISHTI_TOKEN_SECRET` on a schedule (90 days is common) and at once on any suspicion: put the new
value in `secrets.env` on every machine and restart all servers and consoles together; everyone signs in again
([OPERATIONS_GUIDE.md › The token secret is a master key](guides/OPERATIONS_GUIDE.md#112-the-token-secret-is-a-master-key)).

## 11. Upgrade

The install folder is replaced, never edited; the settings, the secrets and the data folder are kept. **Run the pip command again** with the
new release's wheel pack (it changes only what the new pins need), then `setup` to check.

```bash
# 1. read RELEASE_NOTES.md and CHANGELOG.md of the new release; back up (10.3) and note the running release
# 2. unpack and build the new release beside the old one; its libraries: your pip command with its wheel pack (section 3)
sudo mkdir -p /opt/drishti/<new> && sudo unzip -q /root/drishti-platform-<new>-source.zip -d /opt/drishti/<new>
cd /opt/drishti/<new> && sudo /opt/python3.13/bin/python3.13 build_all.py --bundle . --skip-tests
PY=/opt/python3.13/bin/python3.13
cd /opt/drishti/<new>/drishti/drishti-console && sudo $PY -m pip install --no-index --find-links /root/drishti-wheels-<new> -r requirements.txt   # the new pack, unzipped
sudo $PY /opt/drishti/<new>/drishti/tools/drishti.py --settings /etc/drishti/prod.ini setup     # checks
sudo chown -R root:root /opt/drishti/<new> && sudo chmod -R go-w /opt/drishti/<new>

# 3. carry over what the site added: your jars, and the site's changed config files (compare the two config folders)
sudo cp -a /opt/drishti/<old>/drishti/lib/ext/. /opt/drishti/<new>/drishti/lib/ext/
diff -r /opt/drishti/<old>/drishti/config /opt/drishti/<new>/drishti/config

# 4. stop (the console, then the server), switch the release, start (the server, then the console)
sudo systemctl stop drishti-console drishti-server
sudo sed -i 's#/opt/drishti/<old>/drishti#/opt/drishti/<new>/drishti#' /etc/drishti/release.env /etc/drishti/prod.ini
sudo systemctl start drishti-server && curl -s http://127.0.0.1:18480/actuator/health/readiness
sudo systemctl start drishti-console
```

`prod.ini` names the release in `drishti.home` and `drishti.modules.home`: the `sed` changes both (`drishti.python` names the Python, not the release). Then check the
version in **Admin → Health** (or `/actuator/info` with an administrator's token) and run [section 9](#9-check-it-works).

- The database schema is applied again at the start (idempotent, no migrations). Keep the backup of before the upgrade.
- **A cluster upgrades one node at a time**, servers first, consoles last, draining each before its stop: `tools/rolling.py` does it from an
  inventory (`deploy/rolling.example.yaml`; `--dry-run` prints every step). Releases run side by side during the roll
  ([CLUSTER_GUIDE.md › Rolling upgrades](guides/CLUSTER_GUIDE.md#82-rolling-upgrades)).
- **Roll back:** stop both, point `release.env` and `prod.ini` at the previous release (its environment is still in its folder), restore the
  pre-upgrade backup if the new release changed stored data, start.
- Remove an old release when nothing needs it: `sudo rm -rf /opt/drishti/<old>`.

## 12. Security checklist

Work through it before anyone else uses the machine. Detail: [OPERATIONS_GUIDE.md › Security checklist](guides/OPERATIONS_GUIDE.md#11-security-checklist).

- [ ] Sign-in is on for both: `DRISHTI_SECURITY_ENABLED=true`, `DRISHTI_AUTH_ENABLED=true`; `curl http://127.0.0.1:18480/api/v1/sources` answers 401.
- [ ] `DRISHTI_TOKEN_SECRET` (the same on server and console), `DRISHTI_SESSION_SECRET` and, in a cluster, `DRISHTI_NET_SECRET` are each 32+ random bytes, only in `secrets.env` (mode 600, root).
- [ ] The development administrator is not there: the first administrator came from `DRISHTI_SEED_USERNAME` and `_PASSWORD`, which were then deleted from `secrets.env`; no default-password warning.
- [ ] `DRISHTI_DEMO_ENABLED=false`; the demo profile (`--demo`, `config/profiles/demo.ini`) is used nowhere.
- [ ] Roles and field masks reviewed (`drishti.security.roles`, `drishti.security.redact`); Sutra authoring off (`DRISHTI_STUDIO_SAVE` unset) with review and four-eyes on (`DRISHTI_SUTRA_REVIEW`, `DRISHTI_SUTRA_FOUR_EYES`, the defaults).
- [ ] Only the reverse proxy reaches the console (`DRISHTI_CONSOLE_HOST=127.0.0.1`); only the console and the other nodes reach the server's 18480; only monitoring reaches `/actuator`; the firewall names exactly these.
- [ ] TLS at the proxy; `DRISHTI_SECURE_COOKIE` unset; between nodes TLS where the network is not trusted.
- [ ] The services run as `drishti`, the install folder (with its Python environment) is root-owned and not writable by it, `/etc/drishti/secrets.env` is mode 600.
- [ ] AI and MCP off unless wanted (`DRISHTI_AI_ENABLED`, `DRISHTI_MCP_ENABLED`; provider keys as `${ENV}` references, never in files); Kafka, ActiveMQ and RabbitMQ only through `[broker.<name>]` sections you wrote; `DRISHTI_OFFLINE=true` where the machine must not reach the internet.
- [ ] Backups run and one restore has been tried; monitoring scrapes with `DRISHTI_METRICS_TOKEN`; the token secret has a rotation date.

## 13. Troubleshooting

| You see | Cause | Fix |
|---|---|---|
| the server stops at start: `drishti.security.secret must be at least 32 bytes when security is enabled` | `DRISHTI_TOKEN_SECRET` unset or short | put a 32-byte value in `/etc/drishti/secrets.env`; `systemctl restart drishti-server` |
| the JVM does not start, the log folder is named | the GC log's folder is missing | the unit's `ExecStartPre` makes it; check the folder's owner |
| the start refuses, naming a setting and a folder (modules folder, a relative path, a data folder with no identity database) | a root is relative or missing | `prod.ini` and `release.env` name absolute paths; Admin → Configuration → Resolved paths shows what was taken |
| the start names two server jars | `lib/` holds more than one `drishti-server-*-exec.jar` | keep exactly one in `lib/` (your jars are in `lib/ext`) |
| `setup`: the wheel folder has no wheel for X | wrong or incomplete pack | use the release's `linux-x64-py313` pack with a Python 3.13 base; the message names the wheel |
| setup says *this Python is managed by the operating system* | setup ran with the operating system's Python | run it with the Python you prepared for Drishti (the dedicated `/opt/python3.13`, or a virtual environment you made yourself); never `--break-system-packages` |
| the console unit fails: *this Python lacks ...* | the Python the unit names lacks the new release's libraries | run the pip command of section 3 with that Python and the new pack ([3](#3-install-a-release)) |
| sign-in is impossible, no administrator exists | the first start ran without `DRISHTI_SEED_USERNAME` and `DRISHTI_SEED_PASSWORD`, or with `DRISHTI_SEED_ADMIN=false`, on an empty store | set the two variables on a store with no users and restart |
| signed-in people are refused (401) between console and server | the two processes have different `DRISHTI_TOKEN_SECRET` | the same value on both (and every node); restart both |
| the console loads but live panels never tick | the proxy buffers or times out the event stream | `proxy_buffering off`, `proxy_read_timeout 1h`, HTTP/1.1 |
| `403 DRS-5002` on every save | the proxy changed the `Host` header | `proxy_set_header Host $host`, or list the public origin in `auth.allowed_origins` |
| a node refuses its `advertise-url` | a loopback, wildcard or bridge address | use the machine's real address (`ip route get 1.1.1.1`) |
| a node does not join | wrong cluster key, clocks apart by more than 60 s, the seed's 18480 or the gossip port blocked | the same `DRISHTI_NET_SECRET`, NTP, open the ports ([7.4](#74-ports-and-firewall)); `drishti.py net check --dir <the net folder>` |
| readiness stays `DOWN` after a start | an engine is opening its newest business date, or the node is joining | wait; `drishti.py node wait-ready`; Admin → Health says what it waits for |
| a stop takes minutes | the drain waits for `safeToStop` | normal on a cluster node (up to `DRISHTI_DRAIN_WAIT`); keep `TimeoutStopSec` larger |
| the disk fills | heap dumps, logs or hot files | the `drishti.disk.*` guards and caps; Admin → Health → Disk; remove old dumps |

More: [OPERATIONS_GUIDE.md › Troubleshooting](guides/OPERATIONS_GUIDE.md#21-troubleshooting) and [CLUSTER_GUIDE.md](guides/CLUSTER_GUIDE.md).
