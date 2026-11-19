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
# Launcher and script review (2026-11-18)

A design review of every launcher and every script that starts, stops or maintains Drishti, on `origin/main` `ad7beb4fa`
(2.9.11.i7a). No code was changed. Requested by the owner: "launchers have stale information … it can become confusing".

**How it was done.** Every `.run.xml` was read in full (29 files), then `tools/drishti.py` `start|stop|status|setup|build`,
`tools/launch/*.py`, `bin/`, `deploy/stack/`, the settings examples, the pack and distribution files, and the four guides that describe
launchers. Every flag of the thirteen `drishti.py` launchers was checked against the real command line (`--help` of each command), and
every variable against `application.yaml`, the console's `application.yaml` and `CONFIGURATION.md`. **Not done:** nothing was started;
the review worktree has no server jar, so the two findings marked *(not run)* are from reading the code only.

## 1. The answer in short

The launchers are stale because **each one is a hand-written copy of the configuration**. A server launcher carries about 25 values
(12 to 14 JVM options, 9 to 13 environment variables). There are 8 of them, so about 200 hand-kept values, and the same values are written
again in `serverstart.py`, `stack.yaml`, the systemd unit and two container entry points. None of these copies is generated or
checked against the others. The existing guard test checks the shape of the files (type, working folder, settings file), not what
they say.

The structural fix is three rules:

1. **A launcher carries what to run and which settings file. Nothing else.** Everything that has a config home (packs, ports, sign-in,
   offline, secrets, distribution, data folder) lives in a settings file. A "profile" (demo, QA) is a named settings file, and
   `drishti.py start server --demo` reads the same file.
2. **Launchers are generated from one small table and checked**, the way `CONFIGURATION.md` is. The JVM flags come from the one
   function `drishti.py start server` uses. The tables of IDE_GUIDE section 4 are generated from the same table.
3. **One launcher per real job**: 29 files become 13.

Counts: **31 findings** (5 that are wrong today, 14 duplicated or stale settings, 3 parity, 4 Windows, 5 docs), 11 guard tests, 9 decisions.

## 2. Inventory

Verdicts: **keep**, **change** (keep the job, fix what it carries), **remove**.

### 2.1 IntelliJ (`run-configs/`, 11 files)

| Launcher | What it does | Settings it carries by hand | Verdict |
|---|---|---|---|
| Drishti build all | Maven `clean package -DskipTests -pl :drishti-app,:drishti-modules -am` | none | keep |
| Drishti build modules | the same for `:drishti-modules`, `-Pmodules-only` | none | keep |
| Drishti build server jar | the same for `:drishti-app` only | none | **remove**: it leaves the modules stale and the server then refuses to start (IDE_GUIDE.md:68-69 warns about its own launcher) |
| Drishti server | jar on 18480, the packs' samples | 12 JVM options, `DRISHTI_PACKS` (seven), sign-in, seed admin ×3, token secret, port, studio save | change: becomes "Drishti server (built-in samples)", no settings |
| Drishti server (demo lakes) | jar on 18480, the demo lakes, offline | the same, `-Xmx4g`, `DRISHTI_PACKS` (ten), demo off, offline, baskets seed | change: keep the name, no settings |
| Drishti server (All) | `DRISHTI_DISTRIBUTION=all` | the same and `DRISHTI_PACKS` (seven) | **remove**: `all` is the default distribution, so it is "Drishti server" again; its pack list overrides the distribution's own (F3) |
| Drishti server (Risk) | `DRISHTI_DISTRIBUTION=risk` | the same, no packs | **remove**: IDE_GUIDE.md:270 itself says to run the All server under both consoles |
| Drishti server (Finance) | `DRISHTI_DISTRIBUTION=finance` | the same, no packs | **remove** (same reason) |
| Drishti server (QA profile) | jar on 18580, `data/qa/data` | port twice (`-Dserver.port`, `DRISHTI_PORT`), data folder twice (`-D`, env), packs, sign-in, secrets | change: keep, no settings |
| Drishti server (debug-friendly) | QA profile, sign-in off, DEBUG log, 1 GB, builds the modules first | the same; before-launch Maven step | **remove**: every launcher debugs with Shift+F9; the differences are two settings |
| Drishti server (dev switch) | QA profile and `DRISHTI_MODULES_DEV=true` | the same | **remove** (section 6) |

### 2.2 PyCharm (`drishti-console/run-configs/`, 18 files)

| Launcher | What it does | Settings it carries by hand | Verdict |
|---|---|---|---|
| Drishti console | console on 17480 | port, server address, sign-in, token and session secrets, cookie | change: keep, no settings |
| Drishti console (QA profile) | console on 17580 for the QA server | the same, `DRISHTI_LAKE_ROOTS` | change: keep, no settings |
| Drishti console (All) | `--distribution=all` on 17480 | the same | **remove**: equals Drishti console |
| Drishti console (Risk) | `--distribution=risk` on 17480 | the same, a temp folder **in the wrong place** (F1) | change |
| Drishti console (Finance) | `--distribution=finance` on 17481 | the same, a temp folder in the wrong place (F1) | change |
| Build demo lakes | `lake demo --engine java` | none | keep |
| Load ENDUR file into the lake | `lake build` of a private file | pack folder, connector, key, mode, lake | change: one template "Load a file into the lake"; *(not run)* F5 |
| QA pack make (from JSON) | `pack make` of `YOUR_SAMPLE.jsonl` | placeholder file, relative output folder | **remove**: cannot run as shipped; it is a CLI example |
| QA lake build (big JSON to Delta) | `lake build` of `trade.YYYY-MM-DD.json` | placeholder file, relative lake folder (the old `--lake DIR` form) | **remove**: merged into the one template |
| QA doctor | `doctor` against 18580 with a token file | address, token path | change: one "Drishti doctor (QA)" |
| QA server smoke | `server smoke` against 18580 | address, token path | **remove**: doctor covers "is it up"; smoke stays a CLI command |
| drishti doctor | `doctor --server http://localhost:18480` | the default address, no token | change: "Drishti doctor"; F4 |
| drishti server smoke | `server smoke`, no token | the default address | **remove**; F4 |
| drishti view get | `view get trade/1000001`, no token | the default address | **remove**: a CLI example; F4 |
| drishti data profile | `data profile <sample>` | sample file | **remove**: a CLI example |
| drishti pack make | `pack make <sample>` | sample file, relative output | **remove**: a CLI example |
| drishti pack regenerate | `pack regenerate … --dry-run` | needs the one above first | **remove**: a CLI example |
| drishti sutra gen | `sutra gen <sample>` | sample file, relative output | **remove**: a CLI example |

The seven `drishti …` launchers have no `folderName`, so PyCharm shows them outside the "Drishti" folder. Their home is CLI_REFERENCE.md.

### 2.3 Terminal, cluster and deployment

| Path | What it does | Settings it carries by hand | Verdict |
|---|---|---|---|
| `drishti.py setup [--check]` | checks Python and Java, makes the data folder, `dev.ini`, `.venv` | none | keep |
| `drishti.py build jar\|modules\|all\|sdk` | the Maven builds | none | keep (`build jar` stays for the terminal: it prints the stale-module words) |
| `drishti.py start server` | one server or a node; pid file, logs, health wait | `--demo`: the ten packs, 4 GB, 6 switches, lenses, data inbox, 4 dev secrets (`serverstart.py:36-40, 205-216`); JVM flags (`:241-244`) | change: `--demo` reads the demo profile file |
| `drishti.py start console` | the console; `.venv` | `--demo`: sign-in, cookie, dev secrets | change (same) |
| `drishti.py start all`, `stop …`, `status` | both, in the background; stop by pid file over HTTP | none | keep; F20 |
| `drishti.py start\|stop cluster`, `status --cluster` | the cluster of `deploy/stack/stack.yaml` | see next row | keep |
| `deploy/stack/stack.yaml` | three nodes and a console | a Linux `java:` path, 11 settings, 4 pack lists, a fixed business day, heaps, ports | change (F8, F11, F12, F25) |
| `deploy/stack/secrets.env.example`, `README.md` | the secrets' names; the stack's guide | names `openssl`, bin/stop-cluster.sh | change (F28, F29) |
| `drishti.py doctor`, `lake check\|status\|maintain`, `dataload`, `feed` | checks and maintenance | none | keep; help text stale (F18) |
| bin/drishti-node.sh | drain before a stop, in the images and the systemd unit | none | keep, as the one documented shell exception (D8) |
| `deploy/systemd/drishti-server.service` | the service unit | its own JVM flag line (ZGC) | change: flags from the one source (F9) |
| `deploy/docker/server.Dockerfile`, `ecs/server-entrypoint.sh` | the images | their own JVM flag lines | change (F9) |
| `deploy/lake-maintenance.yaml` | nightly maintenance example | `root: ./data/delta/demo`, a bare relative path | change (F19) |
| `tools/load-*.sh` (9), tools/feeds/feed.sh, `tools/pvgen/*.sh` (4), tools/fetch-pyodide.sh | loaders and wrappers, bash only | — | decision D8 (they cannot run on the Windows laptop) |
| `deploy/aws/**/*.sh` (24) | AWS set-up and nightly jobs | — | out of this review's fix plan; D8 |

## 3. Findings

Severity: **A** wrong today, **B** will go wrong at the next change (duplicate or stale), **C** confusing.

### 3.1 Wrong today

**F1 (A). The Risk and Finance consoles write their temp folder inside `drishti-console/`.**
`Drishti console (Risk).run.xml:28` and `(Finance).run.xml:28` set `DRISHTI_CONSOLE_TMP_DIR=$PROJECT_DIR$/data/tmp/console-risk`. In
PyCharm `$PROJECT_DIR$` is `drishti-console/` (the QA console gets it right with `$PROJECT_DIR$/..`, `(QA profile).run.xml:24`). The
folder is `drishti-console/data/tmp/…`, hidden by `.gitignore:70`. The terminal uses `<data>/tmp/console-<name>`
(`consolestart.py:74`), and IDE_GUIDE.md:271 says `data/tmp/console-risk`.
*Fix:* drop the variable; the console's `--name` logic (or the generator) derives it from the data folder.

**F2 (A). A moved data folder is only half followed.** Every server launcher writes `-Djava.io.tmpdir=$PROJECT_DIR$/data/tmp/server`
and `-XX:HeapDumpPath=$PROJECT_DIR$/data/tmp/server` (for example `Drishti server.run.xml:21`), while the data folder comes from
`dev.ini` (`drishti.data.dir`). Point `dev.ini` at `D:/drishti-data` and the lakes move but the native libraries and heap dumps stay in
the clone. The QA launchers put the temp folder under `data/qa/data` but the heap dump under `data/tmp/server`
(`Drishti server (QA profile).run.xml:21`). `test_run_configs.py:112` enforces the literal. The terminal puts dumps in
`<data>/dumps/server` (`serverstart.py:243`), not in a temp folder that the server cleans at start.
*Fix:* the generator writes both from the profile's data folder; long term the server sets its own temp and dump folders from
`drishti.data.dir` so no launcher names them.

**F3 (A). "Drishti server (All)" pins seven packs.** `Drishti server (All).run.xml:27` sets `DRISHTI_PACKS` and `:35` sets
`DRISHTI_DISTRIBUTION=all`. `application.yaml:1189` says the default is "the distribution's packs"; the variable overrides it. So the
launcher that says "every module" (IDE_GUIDE.md:360) cannot show a pack the distribution adds. Risk and Finance set no packs, so the
three distribution launchers do not behave the same way.
*Fix:* remove the launcher (2.1); no launcher sets packs.

**F4 (A). Three tool launchers call a sign-in server without a token.** `drishti doctor`, `drishti server smoke` and `drishti view get`
pass `--server http://localhost:18480` and no `--token-file` (`drishti doctor.run.xml:31`), while every default server launcher sets
`DRISHTI_SECURITY_ENABLED=true`. They work only if `DRISHTI_TOKEN` happens to be in PyCharm's environment. They date from when sign-in
was off by default.
*Fix:* one "Drishti doctor" that takes the address and the token file from the profile; remove the other two.

**F5 (A, not run). "Load ENDUR file into the lake" names a connector the pack no longer holds.** It passes
`--pack $PROJECT_DIR$/../modules/banking/packs/trading --connector trade-endur` (`:31`). `lake build --help` says `--connector` is
"which of the pack's Delta connectors"; the trading pack has no `connectors/` folder now, and `trade-endur.yaml` is in
`modules/source-endur/connectors/`. Either it fails or it silently takes a default. Trying it in the review worktree ended in a Python
traceback (`resolved.Unbuilt`, no jar) instead of the one-line "build the jar first" that `start server` gives.
*Fix:* run it once on a built checkout; the template launcher names the lake and the kind only; `lake build` turns `Unbuilt` into words.

### 3.2 Duplicated or stale settings

**F6 (B). Pack lists are written by hand in 11 places besides their home.** The seven: five server launchers (`Drishti server.run.xml:27`, `(All):27`,
`(QA profile):27`, `(debug-friendly):27`, `(dev switch):27`), equal to config/packs/default-packs.yaml:19-23, so they change nothing
today and will hide a new default pack tomorrow. The ten: `Drishti server (demo lakes).run.xml:36`, `serverstart.py:36`,
`stack.yaml:82` (the console). Other lists: `stack.yaml:56` (twelve), `:64`, `:73`. The words "seven" and "ten" are also in
`launchcli.py:68`, tools/lake/cli.py:22,145, IDE_GUIDE.md:247,358-363 and CLUSTER_GUIDE.md:460 (which says `drishti-main` serves
"the seven default packs"; `stack.yaml:56` gives it twelve).
*Fix:* decision D1. No launcher or tool holds a pack list; the demo profile is the one place the demo's set is named.

**F7 (B). Values that only repeat the product default.** `DRISHTI_PORT=18480` (default at `application.yaml:104`),
`DRISHTI_SEED_ADMIN=true`, `DRISHTI_SEED_USERNAME`, `DRISHTI_SEED_PASSWORD` (defaults at `:1038-1043`) in all eight server launchers;
`--server.port=17480 --backend.url=http://127.0.0.1:18480` in the consoles (defaults at drishti-console/config/application.yaml:20,27)
and once more in `dev.ini` (config/settings.examples/dev.ini:38-39); `--server http://localhost:18480` in three tool launchers (the
tools' default). About 45 lines that say nothing and must be kept right.
*Fix:* removed by rule 1.

**F8 (B). The development secrets are literals in 14 files.** `DRISHTI_TOKEN_SECRET` in 8 server and 5 console launchers and
`serverstart.py:38`; `DRISHTI_SESSION_SECRET` in 5 console launchers and `:39`. The cluster already does it properly: random secrets
made once into a git-ignored file (`clusterctl.py:139-155`).
*Fix:* decision D6.

**F9 (B). The JVM flags are written in six places and already differ.** Eight launchers (`-XX:MaxDirectMemorySize=768m
-Ddrishti.arrow.max-bytes=536870912`, fixed), `serverstart.py:241-244` (256m plus what `tools/lake/memory_caps.py` computes: 512 today,
so the launchers hold a *computed* value as a literal), `stack.yaml:55` (G1 periodic), `drishti-server.service:28` (ZGC),
`server.Dockerfile:49`, `ecs/server-entrypoint.sh:97` (ZGC, 8 GB). The launchers also pass `--add-opens=java.base/java.nio=ALL-UNNAMED`,
which the jar's manifest already gives (`packaging/drishti-app/pom.xml:45`); it is a leftover of the class-path launchers. The terminal
adds a GC log and `-XX:+UseCompactObjectHeaders` on Java 25; the launchers do not.
*Fix:* one function (`serverstart.jvm_flags(profile)`) and one generated arguments file per target; the GC choice becomes one line.

**F10 (B). The QA launchers say the port and the data folder twice.** `-Dserver.port=18580` and `DRISHTI_PORT=18580`,
`-Ddrishti.data.dir=…` and `DRISHTI_DATA_DIR=…` (`Drishti server (QA profile).run.xml:21,34-35`). The `-D` always wins
(CONFIGURATION.md:182), so the two variables are dead.

**F11 (B). `stack.yaml` is a second configuration system.** `settings:` holds 11 environment variables (`stack.yaml:30-41`) with
values that differ from the demo of `start server --demo`: five lenses against four (`:39`, `serverstart.py:211`), the designer on, news
on, a 250-scenario window. `DRISHTI_BUSINESS_TODAY: "2026-11-06"` (`:41`) is tied by hand to where the demo lake ends.
`DRISHTI_GRAPH_LINKBUDGET` (`:36`) is the only use of that name in the repository and is not in CONFIGURATION.md.
*Fix:* the stack names the demo profile and lists only what makes it a cluster (nodes, ports, lakes, what is switched off); the
business day comes from the lake (`lake status` knows its last date) or from the profile.

**F12 (B). The JDK has a fifth home, and it is a Linux path.** `stack.yaml:22` `java: /usr/lib/jvm/java-21-openjdk-amd64/bin/java`,
with a comment ("JAVA_HOME/bin/java wins") older than the one lookup, where `drishti.java.home` comes first (`javacmd.py:135-155`).
See F23 for what it does on Windows.
*Fix:* delete the key; the one lookup serves the cluster.

**F13 (B). The server's address has four names.** `DRISHTI_SERVER` (tools, `drishti.py:275`), `DRISHTI_SERVER_URL`
(`start console`, `consolestart.py:99`), `DRISHTI_BACKEND_URL`/`--backend.url` (the console) and `[console.backend] url` (`dev.ini:38`).
*Fix:* one settings key; the others become its documented aliases or go.

**F14 (B). `dev.ini`'s promise about the port is only partly true.** `dev.ini:42` and IDE_GUIDE.md:162: "`[server] server.port=…`
moves every launcher's port at once". It moves the five launchers that use `DRISHTI_PORT` (a file beats a variable), not the three with
`-Dserver.port`, not the consoles' `--backend.url` and not the tool launchers' `--server`.
*Fix:* follows from rule 1: one port key, read by server, console and tools.

**F15 (C). The template `dev.ini` has a broken comment.** `dev.ini:21` stops mid-sentence ("A section without a dot ([roots]"), and
lines 22-25 explain the three folders three times. `setup` copies this text to every developer's machine.

**F16 (C). "(debug-friendly)" says "Nothing is built before launch" and builds before launch.** Comment at
`Drishti server (debug-friendly).run.xml:17-18`, step at `:39`. The step is `clean package … -am`, a reactor build on every Debug press.

**F17 (C). `launchcli.py:16` says the commands "replace the bin/ shell and PowerShell scripts"**, and `bin/README.md:31` says "the
newest jar" where `lib/README.md:22` says more than one jar is refused. History in a docstring; small.

**F18 (C). Maintenance help text describes older behaviour.** `lake demo`: "the seven packs … point the connector files at them"
(`tools/lake/cli.py:145`): connector files are read-only now. `lake build --lake NAME|DIR`: "a folder, the old form", and both QA
launchers use the old form. `lake relocate` and `lake migrate-config` are one-time moves from older layouts and still sit beside the
daily commands.
*Fix:* reword; move the one-time commands under a "from an older release" heading or retire them (D9).

**F19 (C). `deploy/lake-maintenance.yaml:19` uses `root: ./data/delta/demo`**, a bare relative path, against the explicit-paths rule.
Four launchers also pass bare relative folders (`--out data/build/…`, `--lake data/qa/delta`) that depend on the working folder.

### 3.3 IDE and terminal do not do the same job the same way

DEPLOYMENT_AND_QA_GUIDE.md:687 and OPERATIONS_GUIDE.md:208 say `start server --demo` and **Drishti server (demo lakes)** set the same
things. They do not:

| | `drishti.py start server --demo` | Drishti server (demo lakes) |
|---|---|---|
| Direct memory, Arrow budget | 256m plus computed (`serverstart.py:241, 100-112`) | 768m and 512 MB, fixed |
| Heap dumps | `<data>/dumps/server` | `<clone>/data/tmp/server` |
| GC log | `<data>/logs/gc-server-%p.log` | none |
| Data inbox | on, owner (`:215-216`) | off |
| Lenses | set (`:211`) | the default (the same today) |
| Roots | `DRISHTI_HOME`, `DRISHTI_MODULES_HOME`, `-Ddrishti.data.dir`, `DRISHTI_TMP_DIR` | `-Ddrishti.home` and `dev.ini` |
| Stale modules | refused in words before the start (`:141-155`) | found by the server, later |
| Pid file, `status`, `stop` | yes | no |
| Console's server address | this machine's LAN address; loopback warned (`consolestart.py:98-108`) | `127.0.0.1` |

**F20 (B). A server or console started from an IDE is invisible to `drishti.py status` and `stop`.** `status` prints "(no Drishti
process started with drishti.py start, in this data folder)" (`launchcli.py:202`) while the server runs, and `start server` then fails
only on "port in use".
*Fix:* `status` also asks the profile's ports and shows "running (started elsewhere)"; small.

**F21 (B). The data inbox is on in the terminal demo and off in the IDE demo** (table above). A tester following the data-load
tutorial from the IDE sees no inbox.

**F22 (B). `start server` advises the dev switch in its error.** `serverstart.py:152`: "start server --modules-dev loads the folders
as they are". On the shipped modules that switch refuses banking (section 6), so the advice leads to a second failure.

### 3.4 Windows

**F23 (A on Windows, not run). `start cluster` and the stack's `java:`.** `clusterctl.py:392-393` uses `stack.yaml`'s Linux path when
neither `DRISHTI_JAVA_HOME` nor `JAVA_HOME` is set, as `DRISHTI_JAVA_HOME`; a named home that holds no Java is never skipped
(`javacmd.py:136`), so the start fails even with a good `java` on the PATH. On the owner's laptop `JAVA_HOME` is Java 1.8, so the key
is skipped and the start works only when `--settings` (with `drishti.java.home`) is given.
*Fix:* F12.

**F24 (B). A path with a space breaks every server launcher.** `VM_PARAMETERS` holds `$PROJECT_DIR$` and `$USER_HOME$` unquoted
(`Drishti server.run.xml:21`); `C:\Users\First Last\…` splits the option.
*Fix:* the generator quotes each path option.

**F25 (B). The running jar and the build.** A single server runs `lib/drishti-server-*-exec.jar` itself (IDE and terminal); on
Windows the build cannot replace an open jar or an open `.dmod` (IDE_GUIDE.md:250 says "with the server stopped"). The cluster avoids
this with a copy checked by content (`clusterctl.py:172-194`). So one job, two behaviours, and on Windows a Maven failure deep in the
log.
*Fix:* `build` (and a Maven pre-check) asks the profile's ports first and says "stop the server on 18480 first" in one line. Do not
copy the jar for the IDE: the debugger needs the jar the sources were built into.

**F26 (C). Bash-only operator scripts are still documented as the way.** `tools/load-*.sh` (CLI_REFERENCE.md:3922-3930, with a Windows
note only for Delta), tools/fetch-pyodide.sh (CONFIGURATION.md:2964, CLI_REFERENCE.md:4020), tools/feeds/feed.sh,
`tools/pvgen/*.sh`. `test_launch.py:390` guards `bin/` only. Decision D8.

### 3.5 Docs

**F27. IDE_GUIDE.md section 4 is a hand copy of the 29 files** (lines 345-410) and repeats their values (heap sizes, pack counts,
ports, parameters). Generated from the launcher table it cannot drift.

**F28. Retired scripts are still named.** `deploy/stack/README.md:131` (bin/stop-cluster.sh, `bin/start-cluster.sh`),
CLUSTER_GUIDE.md:465 and :468 (`start.sh`), IDE_GUIDE.md:417 (`drishti-stack/`).

**F29. The secrets are said to be made with `openssl`** (`deploy/stack/README.md:113`, `secrets.env.example:16`); the code uses Python
(`clusterctl.py:144`), which is what works on Windows.

**F30. IDE_GUIDE.md:286 asks the reader to copy a launcher and edit a variable** to get a console without sign-in for the debug
server. A step that needs a hand edit is a missing profile or a launcher that should not exist; with "(debug-friendly)" removed the
step goes.

**F31. The equivalence claim** (DEPLOYMENT_AND_QA_GUIDE.md:687, OPERATIONS_GUIDE.md:208) becomes true once both read the demo profile;
until then it is wrong in the ways of section 3.3.

## 4. The proposed design

### 4.1 Profiles are settings files

The settings file is already "the way forward for every site setting" (CONFIGURATION.md:159) and a launcher may name several
(`a.ini,b.ini`, a later one wins). Use that:

| File | Holds | Who makes it |
|---|---|---|
| `~/drishti-envs/dev.ini` | this machine: `drishti.home`, `drishti.data.dir`, `drishti.modules.home`, `drishti.java.home` | `setup` (as today) |
| `~/drishti-envs/dev-secrets.ini` | token, session and cluster secrets, random | `setup` (new; D6) |
| `config/profiles/demo.ini` | sign-in on, demo source off, offline, baskets seed, studio save, data inbox, the demo's packs (D1) | committed |
| `config/profiles/samples.ini` | sign-in on, demo source on | committed |
| `config/profiles/qa.ini` | port 18580, console 17580, data folder `${drishti.home}/data/qa/data`, lake roots | committed |

A launcher is then `-Ddrishti.home=… -Ddrishti.settings.file=<dev.ini>,<dev-secrets.ini>,<profile>` and the JVM flags.
`drishti.py start server --demo` becomes "add `config/profiles/demo.ini`"; `DEMO_PACKS` and the `env.update(...)` block of
`serverstart.py:205-216` go. The console must read the same keys from the settings file (today it reads only `console.*`; this is the
one piece of real work, see the plan).

### 4.2 The launcher table and the generator

One file, `tools/launch/launchers.yaml`: for each launcher its IDE, name, kind (maven, server, console, tool), profile and, for a tool,
the command. `python tools/drishti.py launchers` writes the `.run.xml` files and the tables of IDE_GUIDE section 4;
`launchers --check` fails when a committed file differs. Server flags come from `serverstart.jvm_flags()`, the function the terminal
runs, so parity is by construction.

### 4.3 The minimal set (29 → 13)

| IDE | Name | Job |
|---|---|---|
| IntelliJ | **Drishti build all** | build the jar and the modules (after a clone or a pull) |
| IntelliJ | **Drishti build modules** | rebuild the modules for the jar in `lib/` |
| IntelliJ | **Drishti server (demo lakes)** | the normal server (name kept: about 20 docs and tutorials use it) |
| IntelliJ | **Drishti server (built-in samples)** | a first look before any lake is built (today's "Drishti server") |
| IntelliJ | **Drishti server (QA, port 18580)** | a second server with its own data folder |
| PyCharm | **Drishti console** | the console for 18480 |
| PyCharm | **Drishti console (QA, port 17580)** | the console for the QA server |
| PyCharm | **Drishti console (Risk)** | the Risk distribution, 17480 |
| PyCharm | **Drishti console (Finance)** | the Finance distribution, 17481 |
| PyCharm | **Build demo lakes** | the demo lakes |
| PyCharm | **Load a file into the lake** | the one template: a file, a lake name, a kind |
| PyCharm | **Drishti doctor** | is this machine and the server on 18480 right |
| PyCharm | **Drishti doctor (QA)** | the same for 18580 |

Debugging is Shift+F9 on any server launcher. A distribution server is the profile line `drishti.distribution=risk` in a copy.
The removed tool launchers stay as examples in CLI_REFERENCE.md.

## 5. Guard tests

| # | Test | Catches |
|---|---|---|
| G1 | the committed `.run.xml` files equal the generator's output | any hand edit; F1, F3, F10, F16 |
| G2 | a launcher carries only an allowed list (jar, settings files, home, JVM flags from the one function, `PYTHONUNBUFFERED`); any other `DRISHTI_*` or `-Ddrishti.*` fails with "this has a config home" | F6, F7, F8 |
| G3 | each PyCharm launcher's parameters parse with the real parser of `drishti.py` (and the console's keys exist in its `application.yaml`) | retired flags and commands |
| G4 | every `DRISHTI_*` and `drishti.*` name in launchers, profiles, `stack.yaml`, the systemd unit, compose and the entry points is in CONFIGURATION.md | F11 (`DRISHTI_GRAPH_LINKBUDGET`) |
| G5 | every pack id in any list is a `pack.yaml` on disk; no pack list literal in `tools/**/*.py` or a launcher | F6 |
| G6 | `start server --demo` and the generated demo launcher give the same flags and settings (pid and log apart) | section 3.3, F21 |
| G7 | paths: IntelliJ `$PROJECT_DIR$`, PyCharm `$PROJECT_DIR$/..` for the repository, never `$PROJECT_DIR$/data` in PyCharm; temp and dump folders under the profile's data folder; path options quoted | F1, F2, F24 |
| G8 | every `**Drishti …**` or `**Build demo lakes**` name in `docs/` is a launcher, and no doc names `start.sh`, `stop-cluster.sh`, `start-cluster.sh` or `drishti-stack/` | F27, F28 |
| G9 | a sample file named by a launcher exists under `data/testdata/`; a placeholder is allowed only in a launcher marked `template` | F5 |
| G10 | no shipped config or launcher holds an OS-specific absolute path (`/usr/lib/jvm`, `C:\`); `stack.yaml` has no `java:` | F12, F23 |
| G11 | the exact launcher set, replacing today's lower bounds `>= 8`, `>= 15`, `>= 25` (`test_run_configs.py:72,132,149`), which protect growth | new launchers by copy |

All are quick file checks (the `static` mark); G3 and G6 import the parser and need no jar.

## 6. The dev switch: remove it

"Drishti server (dev switch)" loads module folders unpacked and unverified. On the shipped modules it refuses banking (its allowance
needs the package's hash list), cannot find the Java modules' jars, misses the capital-markets and economy lenses and the files the
build adds; IDE_GUIDE.md:303-309 already tells the reader it is "not the shipped modules".

Making it work means a second module loader that must follow every feature of the packer: two ways to do one job, and an unverified
one. The real job, "see my module change quickly", already has one way: **Drishti build modules** and restart; `drishti.py build
modules banking` builds one module.

Recommendation:

1. Now: remove the launcher, the `--modules-dev` option and the advice in `serverstart.py:152`; IDE_GUIDE 1.5 and 3.7 say "build
   modules, restart".
2. If the loop is too slow, make the IDE's build take a module id (a Maven property the launcher asks for), not a second loader.
3. The Java switch (`drishti.modules.dev`, `LaunchRoots.java:87`): remove in the security release (D5); an "unverified load" switch
   is an attack surface even when refused under `prod`.

## 7. Fix plan, in order

Sizes: S up to half a day, M one to two days, L three or more. Each step lists its docs.

| # | Step | Size | Closes |
|---|---|---|---|
| 1 | Point fixes with no design: F1 (temp folder), F15 (`dev.ini` comment), F16, F17, F22, F28, F29, F19; run "Load ENDUR file" once on a built checkout and fix it (F5). Docs: IDE_GUIDE, CLUSTER_GUIDE, stack README | S | F1, F5, F15-F17, F19, F22, F28, F29 |
| 2 | Remove the dev switch launcher and option; remove "(debug-friendly)", "build server jar", the three distribution servers, console (All), and the nine example launchers; set the test's exact list (G11). Docs: IDE_GUIDE 1.3, 1.5, 3.4-3.7, 4; DISTRIBUTIONS.md:135,250 | S | F3, F4, F30, section 6 |
| 3 | Profiles: `config/profiles/{demo,samples,qa}.ini`; `--demo` reads `demo.ini`; `setup` writes `dev-secrets.ini`; the console reads the shared keys from the settings file. Docs: CONFIGURATION.md (generated), DEPLOYMENT_AND_QA_GUIDE 1.6, 6, 7 | M | F6-F8, F10, F13, F14, F21, F31 |
| 4 | `serverstart.jvm_flags()` as the one source; the launcher table, the generator and `--check`; IDE_GUIDE section 4 generated; G1, G2, G6, G7 | M | F2, F9 (IDE and terminal), F24, F27 |
| 5 | `stack.yaml` on the demo profile: no `java:`, no settings block beyond the cluster's own, pack lists per D1, business day from the lake. Docs: CLUSTER_GUIDE, stack README | S-M | F11, F12, F23 |
| 6 | `status` sees a process started elsewhere; `build` says "stop the server first" | S | F20, F25 |
| 7 | G3, G4, G5, G8, G9, G10 | S | keeps it so |
| 8 | The systemd unit and the images take their JVM flags from the generated arguments file | M | F9 (deployment) |
| 9 | Help text of `lake` (F18); bash scripts per D8 | S, then M-L | F18, F26 |

Steps 1 and 2 are safe before the next drill (file checks only). Steps 3 and 4 change how the demo starts: one targeted run of the
quick-start (build, lakes, server, console, sign-in) from both IDEs' launchers and from the terminal, on Linux and on the Windows laptop.

## 8. Decisions for the owner

| # | Decision | Recommendation |
|---|---|---|
| D1 | Where is the demo's pack set named? Today `default-packs.yaml` lists seven; the demo adds `us-equities`, `gleif`, `funds` by hand in four places, although the capital-markets module is on and `gleif` is a kernel pack | Packs follow the modules that are on, with a per-pack `default: false` in the module's manifest for packs that need a lake first; the demo profile turns those on by name, once. No pack list in any launcher or tool. `DRISHTI_PACKS` stays as a site override only |
| D2 | Profiles as committed settings files (`config/profiles/*.ini`), and `--demo` reading the same file | Yes |
| D3 | Launchers generated from one table and checked; IDE_GUIDE section 4 generated | Yes |
| D4 | The set of 13 (section 4.3), keeping the names the tutorials use | Yes. The one rename is "Drishti server" → "Drishti server (built-in samples)" |
| D5 | The dev switch | Remove launcher and option now; remove the Java switch in the security release |
| D6 | Development secrets: literals in git (today, 14 files) or random per machine | Random, written once by `setup` into `~/drishti-envs/dev-secrets.ini`, as the cluster does. The dev administrator's name and password stay the product default |
| D7 | Should an IDE-started server show in `drishti.py status`, and should `build` refuse while a server holds the jar? | Yes to both (step 6) |
| D8 | Bash-only scripts: `tools/load-*.sh`, `feed.sh`, `pvgen/*.sh`, `fetch-pyodide.sh`, `deploy/aws/**/*.sh` | Move the `tools/` ones behind `drishti.py` (they are thin wrappers already) and delete the `.sh`; AWS scripts in the AWS release; bin/drishti-node.sh stays as the one written exception (it runs inside images with no Python), with `drishti.py node drain|undrain|status` for Windows |
| D9 | `lake relocate` and `lake migrate-config` (one-time moves from older layouts) | Retire both after one more release; until then under their own help heading |
