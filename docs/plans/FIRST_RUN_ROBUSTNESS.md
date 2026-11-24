<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# First run, fool-proof by design: invariants, scenario matrix, Windows proof, i7g gate

Written 2026-11-23, 19:10 to 19:30 EST, against `origin/release/i7g` at `f3a3e672a` (the lane's worktree was clean and equal to it) and
the index repository `drishti-platform` at `021bc7e` (main). No sub-agent. No product code was changed and nothing was run in the lane's
worktree or against the owner's stack; every statement about the code below was read in the file named. Three statements are
hypotheses that could not be checked from here and are marked **(to verify)** with the check that settles them.

**Why the last six attempts failed.** Each release fixed the one symptom the owner hit and added a unit test of that symptom, with the
Windows machine imitated by a monkeypatch. Nothing ever ran the real path (zip, wheel pack, build, setup, start, VaR) on a machine that
was not ours, and nothing stated what Drishti is *never* allowed to do, so each fix left the same class of fault open in the next
command (i7f fixed "setup makes a folder in the old clone"; `start` can still do it, section D2). This plan replaces symptom tests with
three things: invariants that hold for every command (A), a matrix checked by three general oracles rather than by one assertion per
known bug (B), and a real Windows machine that runs the release before the owner does (C).

Contents: [A invariants](#a-design-invariants) · [B scenario matrix](#b-scenario-matrix) · [C Windows proof](#c-real-windows-proof-github-actions) ·
[D review of i7g](#d-review-of-originreleasei7g-at-f3a3e672a) · [E release gate](#e-release-gate-for-i7g) · [F decisions for the lead](#f-decisions-the-lead-must-take)

---

## A. Design invariants

Each invariant is one sentence that a test can fail. The five entry points are **I** an IntelliJ launcher, **P** a PyCharm launcher,
**C** `tools/drishti.py` on the command line, **B** a bare `drishti-console/run_drishti_web.py`, **A** `build_all.py`.

### A1. Python: one resolution rule

| # | Invariant |
|---|---|
| P1 | One function answers "which Python" for every entry point: `DRISHTI_PYTHON`, else `drishti.python` of the settings file, else the Python running this process. Nothing else: no folder is probed, no name is looked up on the PATH by Python code. |
| P2 | Drishti never makes a virtual environment, on any operating system, and no tracked file names `.venv` except ignore patterns (`.gitignore`, `.dockerignore`). A user who has one runs Drishti with it or names it in `drishti.python`. |
| P3 | `setup` installs into exactly the Python that runs it, and writes that Python as `drishti.python` (absent or stale value only). |
| P4 | A named Python that is not there (`DRISHTI_PYTHON`, `drishti.python`) is a `[FAIL]`, never a fall-through to the next source. |
| P5 | Nothing in Java, Maven or the tools starts `python` or `python3` by name on Windows (the Microsoft Store stub); the builds pass `py`, the server uses `drishti.python`. |
| P6 | The first lines of every entry script check the Python version with syntax every Python 3.6+ parses, before any other import, so an old Python gets one line, not a `SyntaxError`. |

### A2. Folders and files: who may make what

| # | Invariant |
|---|---|
| R1 | **The write set.** `setup` writes only: the settings folder (`%USERPROFILE%\drishti-envs`, `~/drishti-envs`); `data/` and `lib/ext/` of this checkout; a `--data-dir` given on this command; the running Python's libraries, through pip; the temp folder. `start` writes only under a data folder that already exists (`run/ logs/ tmp/ dumps/ nodes/`). `stop` and `status` write nothing but the removal of their own stale pid file. The builds write `target/`, `lib/`, `modules/` of the checkout and the Maven repository. |
| R2 | Nobody writes anywhere else: not another checkout, not a folder named only by an environment variable or by another checkout's settings file, not the home folder's root, not a hidden name, not `Program Files`. |
| R3 | Only `setup` makes the settings folder and a data folder's root. Any other command that finds none says the setup line and stops. |
| R4 | One resolver for the roots (home, data, modules, lib/ext, temp), used by every Python entry point and matching the server's order: explicit option, settings file, environment, checkout. |
| R5 | **Trust rule.** A root from the environment (`DRISHTI_HOME`, `_DATA_DIR`, `_MODULES_HOME`, `_LIB_PATH`, `_TMP_DIR`) naming a folder outside this checkout is used only when a settings file was named explicitly (`--settings`: production). In the developer default it is ignored with one `[WARN]` line, by every command, not only by setup. |
| R6 | A settings file whose `drishti.home` is another folder contributes **no roots** (home, data, modules, lib/ext) to any process, Python or Java; its other keys are read; one `[WARN]` line says so with the setup line. This keeps "warn, never refuse" and makes cross-checkout writes impossible. |
| R7 | Files are written whole or not at all (a temporary file beside the target, then a rename); nothing is deleted, an old file is moved aside. |

### A3. Setup

| # | Invariant |
|---|---|
| S1 | One line, no required option, the same on every system but for the interpreter's name: `py tools\drishti.py setup`, `python3 tools/drishti.py setup`. |
| S2 | It runs on the standard library alone until the libraries are in. |
| S3 | Steps in a fixed order, one status line each: `[  ok]`, `[made]`, `[move]`, `[WARN]`, `[todo]`, `[FAIL]`, `[skip]`. A failed step never hides the later independent ones; a dependent one prints `[skip]` naming the step it waits for. |
| S4 | The wheel pack is found without help: `--wheels`, `DRISHTI_WHEELS`, `~/drishti-wheels`, then the pack **zip** for this system and Python in the platform folder and in the Downloads folder. The pack describes itself (`pack.json`: release, system, Python, Lake version); a pack for another system, Python or release is one `[FAIL]` naming both sides, and pip is not run. |
| S5 | pip runs only as `-m pip install --isolated --no-index --find-links <pack> --no-input --disable-pip-version-check`: a company `pip.ini`, `PIP_*` variables and the package index play no part. The index is used only with `--index`. |
| S6 | After pip, the libraries are verified in a fresh process of the same Python, name by name. |
| S7 | Setup never: makes a venv; uses the network without `--index`; changes the user's environment or registry; edits a settings file other than the one `drishti.python` line of this checkout's file; writes outside R1. |
| S8 | **Idempotent.** A second run right after a green one prints only `[  ok]` lines, exits 0, runs no pip, makes no `replaced-*` folder and changes no byte on disk. `--check` never changes a byte. |
| S9 | The last line is one of `Drishti is ready.` (exit 0, followed by the start lines) or `Drishti is NOT ready: N to fix (above).` (exit 1, no start line). |

### A4. Start, stop, status

| # | Invariant |
|---|---|
| G1 | `start` never installs, builds, downloads or makes a Python environment. |
| G2 | One preflight runs before anything is started or made (`start all`: for the server and the console together): this checkout is set up; Java 21+; exactly one server jar; modules stamped for it; the Python and the console's libraries; both ports free; the data folder exists; with `--demo`, the demo lake is built. Every failed check is one `[FAIL]` with its fix; then nothing is started. |
| G3 | `stop` and `status` act only on the pid files of this checkout's data folder and never on a process whose command line is not this install's; `status` works, and says "not set up", on a machine where nothing was prepared. |
| G4 | The order of starting does not matter: a console started first waits, says so once, retries with a back-off, and joins the server when it comes, with no restart. |
| G5 | Every HTTP call Drishti makes to itself (console to server, health checks, status, cluster) ignores proxy settings: the environment's and, on Windows, the registry's. |

### A5. Settings file life cycle

| # | Invariant |
|---|---|
| F1 | **Create:** only `setup`, only when none exists, from the example with this machine's paths. |
| F2 | **Keep:** a file whose `drishti.home` is this checkout is never rewritten; only its `drishti.python` line is added or repaired. |
| F3 | **Move aside:** a file naming another folder, an unreadable one, or a `jvm-*.args` that differs is moved to `drishti-envs/replaced-<date-time>/` and a fresh one written; a file that cannot be moved is a `[FAIL]` and is not written over. |
| F4 | **Follow:** the default `dev.ini` is taken only when it names this checkout; one function decides this for the tools and for the bare console. |
| F5 | A file named with `--settings` (production) is never moved, and no developer settings folder is made beside it. |
| F6 | The file parses with a byte-order mark and Windows line ends (Notepad). |

### A6. Secrets

| # | Invariant |
|---|---|
| X1 | The development secrets are spelled in one file; `--demo`, the IDE launchers and the bare console take them from it, so any mix of entry points agrees. |
| X2 | A run that uses them says so in one line (NOT FOR PRODUCTION); a value is never printed. |
| X3 | Without `--demo` and without the developer launchers no development secret is supplied: a missing secret is a `[FAIL]` naming the variable. A secret already in the environment wins and is named (`DRISHTI_TOKEN_SECRET from the environment`), so a left-over one is visible. |

### A7. Launchers

| # | Invariant |
|---|---|
| L1 | IntelliJ on the platform folder shows only the platform launchers; IntelliJ on `drishti` only the Java ones; PyCharm on `drishti/drishti-console` only the Python ones. No entry is red or "Unknown" in its own project. |
| L2 | Every launcher is generated from the one table and runs the same command line as the CLI; it holds only `$PROJECT_DIR$` and `$USER_HOME$`, no interpreter path, no `.venv`. |
| L3 | PyCharm's interpreter is the owner's choice; no launcher and no setup step sets it. Setup prints the path to choose. |

### A8. Failure, output, network

| # | Invariant |
|---|---|
| E1 | **One funnel.** Every entry script runs inside one handler: a known failure is one line `[FAIL] <what>: <why>. Fix: <command>`; an unknown one is one line naming a log file that holds the traceback. No traceback reaches the screen (unless `DRISHTI_DEBUG=1`). |
| E2 | A fix command is built by one helper and can be pasted as printed: the exact interpreter that is running, the system's separators, quotes around a path with a space. No message spells `python tools/drishti.py` by hand. |
| E3 | **No silent fallback.** Whenever a preferred source is skipped (a foreign variable, a foreign `dev.ini`, another Python), one line says what was ignored and what is used. |
| E4 | **No network unless asked:** only `setup --index`, `clone_all.py`, a Maven build, and feeds that were switched on. |
| E5 | Exit codes: 0 done, 1 failed, 2 wrong option, 3 already running or port in use, 130 interrupted. |
| E6 | Output is plain ASCII status text and never fails on the console's code page (`errors="replace"`). |

---

## B. Scenario matrix

### B1. How a row is proved

Seventy-one rows cannot each get a hand-written assertion that foresees the fault; the six failures were all faults nobody foresaw. Every
end-to-end cell is therefore checked by the same three **oracles**, and a row adds only its own expected line.

| Oracle | What it does | The class it catches |
|---|---|---|
| **Write set** | Lists every file (path, size, hash) of the scratch home, the checkout and a decoy "old clone" before and after the command; the difference must lie inside the command's write set of R1. | a folder made in the old clone; a `.venv`; litter in the home folder; a settings file edited in place |
| **Output** | No `Traceback`; every `[FAIL]` line carries `Fix:` and a command that parses; the exit code is the row's; the last line of setup is one of the two of S9. | tracebacks; vague failures; "start" offered while something failed |
| **Network** | The process tree runs with a `sitecustomize` that records and refuses every non-loopback connect, `PIP_INDEX_URL` and the proxy variables pointing at a dead port, and a **poisoned PATH**: shims named `python`, `pip`, `git`, `mvn` (and on Windows no `python*.exe` at all) that log their caller and fail. The logs must be empty. | the company index; the Store stub; git in a zip; a proxy in the way |

Kinds of proof, as written in the last column:

- **U** unit test (`tools/test_first_run.py`, `drishti-console/tests/test_first_run.py`): pure functions with `ntpath` as the path
  flavour and a monkeypatched `procs.windows`, as today; seconds.
- **S** static guard over tracked files (no `.venv`, no hand-spelled command, launcher sets, zip path lengths); seconds.
- **J** a Java unit test (`LaunchRoots`, the Python resolver).
- **E** Linux end-to-end, new: `testing/firstrun/run.py`. It unzips `git archive` of the checkout (no `.git`) into
  `<scratch>/John Smith/IdeaProjects/drishti-platform (1)/drishti`, sets `HOME` to `<scratch>/John Smith`, plays the user who has his
  own environment (`python3 -m venv "<scratch>/my py"`, made by the test, never by Drishti), builds a small wheel pack with
  `pip download` once per session (cached in `~/drishti-bench-scratch/firstrun-pack`), and runs each entry point as a subprocess under
  the three oracles. **E-light** (about 2 minutes, every lane run): everything up to and including start's preflight, with the jar
  absent or a stub. **E-full** (about 15 minutes, once per release through `heavy.sh`): build, setup, lake demo, start, VaR, stop.
  Launchers are run by the **launcher-equivalent runner**: the command line a `.run.xml` produces, macros expanded
  (`launchers.py` already has this function), so an IDE click is covered up to the IDE itself.
- **W** a step of the Windows workflow of section C.
- **M** by hand in the IDE, once per release (section E3); cannot be automated.

### B2. The matrix

Columns: the situation; the entry points it applies to (I P C B A); what the user sees; what must not happen; the proof. "Setup line"
means the pasteable command of E2; "three oracles" are implied in every E and W cell.

**Clean machine and order of steps**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 01 | Clean machine, the documented path: unzip, build, setup, demo lake, start, `VAR BANK` | I P C B A | VaR 167,372,692, ES 210,131,972 | any manual edit; any network after the build | E-full, W, M |
| 02 | Setup run a second time | P C | only `[  ok]`, `Drishti is ready.`, exit 0 | pip run; a new `replaced-*`; any byte changed | U, E, W |
| 03 | `setup --check` before anything | C | `[todo]` lines, exit 1 | any byte changed | U, E |
| 04 | Start before setup (no settings file) | I P C B | C, B, P: one `[FAIL] this checkout is not set up` with the setup line. I: java's one line naming the missing `jvm-demo.args`; START_HERE maps it to the setup line | a data folder or settings folder made; half a stack running | U, E; I: S (doc row) |
| 05 | Setup before the build (no jar, no modules) | P C | setup does its part; `NOT ready` names the build line; start: `[FAIL]` no server jar, build line | a start line offered | U, E |
| 06 | `--demo` with no demo lake built | I P C B | C: `[FAIL] the demo lake is not built` with the lake line. I: the server starts and says the same once in its log; `VAR BANK` answers in words, never a wrong number | an empty answer shown as a figure | E; J |
| 07 | Build run twice; build after a failed build | I A | the same result; `clean` first | a half-built jar used; two server jars in `lib/` | U, W |
| 08 | `build_all.py` run with no option inside the unzipped folder, or pointed at a wrong or incomplete folder | A | no option: builds the folder it lies in. Wrong folder: `[FAIL]` naming the missing product folder | a guess at another folder | U |
| 09 | Interrupted (Ctrl+C) during setup or start | P C | one line, exit 130 | a half-written `dev.ini`; a traceback | U |

**Left-over settings and environment**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 10 | Old `dev.ini` of another clone; setup is run | P C | `[move] dev.ini moved to …\replaced-<date>`; fresh file; ready | anything made in the old clone | U, E, W |
| 11 | Old `dev.ini` of another clone; setup is **not** run first | I P C B | `[WARN] …dev.ini belongs to <old>: not used`, then row 04's `[FAIL]`. I and P pass that file by its fixed path: its roots are ignored (R6) and the same warning is logged | the old clone's data read, written or its server stopped | U, J, E |
| 12 | Old `DRISHTI_HOME`, `_DATA_DIR`, `_MODULES_HOME`, `_LIB_PATH`, `_TMP_DIR` (setx) naming another folder | I P C B A | one `[WARN]` per variable in every command: ignored; the run goes on with this checkout | a folder made there; another checkout's pid files read by stop | U, E, W |
| 13 | `DRISHTI_PYTHON` naming a file that is gone or an old venv | P C B | `[FAIL] DRISHTI_PYTHON names <x>, which is not there` with how to delete it | a silent use of another Python | U, E |
| 14 | `drishti.python` in this checkout's `dev.ini` is stale (Python upgraded, old venv path) | P C B | setup: `[made] drishti.python=<this>  (was <old>: not there)`; start before setup: `[FAIL]` with the setup line | start with a guessed Python | U, E |
| 15 | Other left-over variables: `DRISHTI_PORT`, `_CONSOLE_PORT`, `_SERVER_URL`, `_PACKS`, `_DISTRIBUTION`, `_WHEELS`, `_TOKEN_SECRET`, `_SESSION_SECRET` | I P C B | setup lists each; start names each one it uses; a token secret from the environment is named (X3) | a console and a server with different secrets and no word about it | U, E |
| 16 | This checkout's `dev.ini` with the data folder elsewhere on purpose | P C | kept; `[todo]` with `setup --data-dir <it>` until the folder exists | the folder made without `--data-dir` | U (exists) |
| 17 | `dev.ini` unreadable, half written, or saved by Notepad (BOM, CRLF) | I P C B | BOM and CRLF: read normally. Unreadable: setup moves it aside; other commands `[FAIL]` with the setup line | a parse traceback | U, J |
| 18 | A file is locked: `dev.ini` open in an editor; the server jar held by a running server during a build | I P C A | setup: `[FAIL] <file> could not be moved: close the program`. Build: `[FAIL] Drishti is running from this folder: <stop line>` before Maven starts (A); Maven's own error (I) with a START_HERE row | the file written over; a half-replaced jar | U, W |
| 19 | Left-over `drishti-console/.venv` folder of an earlier release | P C | `[note] …: not used, you may delete it` once | it being used, probed or deleted | U, E |
| 20 | The folder was renamed or moved after setup | I P C B | as rows 10 and 11 (the file names a folder that is gone) | the old path made again | U |

**The wheel pack**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 21 | No pack anywhere, no `--index` | P C | `[FAIL] libraries: no wheel pack found` listing the places looked in, the exact zip name for this system and Python, and the setup line | any network; pip run | U, E, W |
| 22 | Pack zip left in Downloads, not unzipped | P C | `[  ok] wheel pack: <zip>` and the libraries installed | a request to unzip | U, E, W |
| 23 | Pack unzipped flat into `~/drishti-wheels` | P C | installed from there | | U (exists), E |
| 24 | Pack unzipped one folder deep ("Extract All"), or the zip inside a folder | P C | found, installed | "no wheels found" | U (exists), E |
| 25 | Pack for another system (Linux pack on Windows, arm against x64) | P C | `[FAIL] the wheel pack is for linux-x64; this machine is windows-x64` with the zip to fetch | pip run | U |
| 26 | Pack for another Python (313 pack; `py` starts 3.12 or 3.14) | P C | `[FAIL] the pack is for Python 3.13; this Python is 3.14` with `py -3.13 …` when that Python is installed, else "install Python 3.13" | pip run | U (exists), W |
| 27 | Pack of another release than the source | P C | `[FAIL] the pack is of release i7f; this is i7g` | a mixed install | U |
| 28 | Pack incomplete or damaged (a wheel missing, a cut-off zip, a checksum that differs) | P C | `[FAIL]` naming the wheel or the file | a partial install left unsaid | U |
| 29 | Two packs present (old and new) | P C | the one for this release, system and Python is taken and named | "the newest by date" | U |
| 30 | The libraries are already there (installed another way) | P C | `[  ok] libraries`, pip not run, no pack needed | a `[FAIL]` for the missing pack | U (exists) |
| 31 | Some libraries are there in other versions | P C | `[made] libraries: fastapi 0.100 -> 0.141, …` each change named | a silent downgrade | U, E |

**The Python itself**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 32 | `python` and `python3` are the Microsoft Store stubs | I P C A | everything works with `py`; the builds pass `py` to Rachana's helpers | any use of `python`/`python3` by name | S, W (poisoned PATH) |
| 33 | The interpreter is the Microsoft Store's Python | P C | `[WARN] a Microsoft Store Python: not tested; python.org's is recommended`, setup goes on | a refusal with no reason | U |
| 34 | Python installed for all users under `Program Files` (no write) | P C | pip installs for the user; verified in a fresh process; `[made] libraries (for your user)`. User site switched off: `[FAIL]` before pip, "ask IT to run this line as administrator" with the line | "installed" said while the import fails | U, W (optional) |
| 35 | The operating system's own Python (PEP 668) | P C | `[FAIL] this Python is managed by the operating system` with the two-line fix (your own environment, then setup with it) | pip run; `--break-system-packages` | U (exists), W ubuntu |
| 36 | The user's own virtual environment (on the PATH or run by path) | P C B | setup installs into it and writes it as `drishti.python`; later commands from any shell use it | Drishti making or naming one | E (the Linux default), W ubuntu |
| 37 | Python older than 3.11, or 32-bit | P C B A | one `[FAIL]` line naming the version needed | a `SyntaxError` | U, S |
| 38 | PyCharm with no interpreter chosen | P | PyCharm's own "interpreter is not configured"; START_HERE row; setup has printed the path to choose | | M |
| 39 | PyCharm's interpreter lacks the libraries (another Python than setup's, or a venv PyCharm made itself) | P B | console: one line naming the missing libraries and the setup line; **Drishti: setup** then installs into that interpreter; `drishti.python` kept, said | minutes of import errors; PyCharm's own "install requirements" being the documented fix | U, M |

**Java**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 40 | No `JAVA_HOME`, no `java` on the PATH | P C A | A: `[FAIL] no Java 21 found` before Maven starts. Setup: `[FAIL] java` with the fix. I uses the project SDK | Maven's "JAVA_HOME not found" as the only word | U, W |
| 41 | Java 8, 11 or 17 is the first found | P C A | `[FAIL] Java 17 at <path> (from JAVA_HOME): Drishti needs 21` with `drishti.java.home` | a class-version stack trace | U (exists) |
| 42 | Java 25 | I P C A | accepted, the version shown | a refusal; a silent switch to another JDK | W ubuntu (optional job) |
| 43 | `JAVA_HOME` in quotes, ending in `\bin`, or naming a JRE or a missing folder | P C A | `[FAIL] JAVA_HOME is "<value>": it should be the JDK folder, <suggested>` | the next java on the PATH used without a word | U |

**Paths**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 44 | User name with a space; folder `drishti-platform (1)` (a second download); `&` in a path | I P C B A | works; every printed command is quoted | a command cut at the space | U, E, W (the default folder of both) |
| 45 | The checkout lies inside OneDrive (`OneDrive - Company\Documents\…`) | I P C A | works; setup: `[WARN] inside OneDrive: syncing can lock files; C:\Users\<you>\IdeaProjects is recommended` | a refusal | U |
| 46 | The path is too long for Windows (260) | P C A | setup and build: `[FAIL] this folder's path is N characters; the longest file needs M: unzip nearer the drive's root` | a build failing on a file "not found" | S (zip path budget), U |
| 47 | Letters outside ASCII in the user name or the path | I P C B A | works, or setup `[WARN]` naming the limit (the JVM reads `jvm-*.args` in the system code page) | a `UnicodeEncodeError` | U |
| 48 | The checkout is not writable (unzipped under `Program Files` or a read-only share) | P C A | `[FAIL] <folder> is not writable: unzip under your user folder` | a traceback from `mkdir` | U |

**Ports, processes, order**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 49 | Port 18480 or 17480 held by another program | I P C B | C: `[FAIL] port 17480 is in use` (exit 3); `start all` checks both before it starts either. I, P, B: the framework's one line | a server left running without its console | U, E, W |
| 50 | Drishti of this checkout already runs; start again | C | `already running (pid, port)` with the stop line, exit 3 | a second process | U (exists), E |
| 51 | The server was started in IntelliJ; then `status`, `stop all` on the command line | C | status shows it as "started elsewhere"; stop leaves it and says where to stop it | a kill by name | U (exists), E |
| 52 | Server first, then console | I P C B | console connects | | E-full |
| 53 | Console first, then server; or console alone | P C B | one line "no server answers yet", back-off; joins when the server is up | a flood of 401 or connection lines; a restart needed | E |
| 54 | Mixed entry points: server from the launcher, console from the CLI or bare; and the reverse | I P C B | they agree (one source of development secrets) | 401 between them | S, E (launcher-equivalent runner) |
| 55 | Stale pid file after a crash or restart; the pid now belongs to another program | C | `stale … removed`; nothing stopped | another program stopped | U (exists) |
| 56 | A proxy is configured (`HTTP_PROXY`, `HTTPS_PROXY`, or the Windows system proxy) and does not exempt this machine's address | P C B | no effect: health checks and the console's calls go direct | "did not answer" while it runs; the proxy's 403/407 shown as a Drishti fault | S, U, E, W |
| 57 | The network changes between starts (VPN, dock, no adapter) | P C B | the console still reaches the server on this machine | a console pointed at yesterday's address | E |

**IDE and project**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 58 | IntelliJ opens the platform folder | I | exactly the platform launchers | a Drishti launcher, a red entry | S, M |
| 59 | IntelliJ opens `drishti` | I | exactly the six Java launchers | a Python launcher, a red entry | S, M |
| 60 | PyCharm opens `drishti/drishti-console` | P | exactly the ten Python launchers | a Java launcher; no launchers at all | S, M |
| 61 | The wrong program or folder (IntelliJ on `drishti-console`, PyCharm on `drishti` or the platform folder) | I P | no launcher of the other kind offered as usable; START_HERE names the folder for each program | anything made or changed | S (doc), M |
| 62 | Every launcher against the command line | I P | the launcher's command equals the CLI's for the same job | a launcher-only option or variable | S, E |

**Upgrade and several checkouts**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 63 | New zip unzipped over an i7b or i7f folder | I P C A | setup and build: `[WARN] N files of an older release are in this folder (first five named): unzip into an empty folder`; one server jar after the build | old launchers or an old jar used without a word | U, E |
| 64 | New zip in a new folder beside the old one | I P C B A | row 10 for the settings; the demo data is built anew | the old folder touched | E, W |
| 65 | Two checkouts used in turn | I P C B | setup in one moves the other's `dev.ini` aside and says the other must run setup to take over again; the other's commands meanwhile behave as row 11 | one checkout's server on the other's data | E, J |
| 66 | Data of an older release in this checkout's `data/` | I P C | the server's own one-line message; the demo lake is rebuilt by `lake demo` | a silent mix | E (demo only) |

**Network**

| # | Situation | At | Visible outcome | Must not | Proof |
|---|---|---|---|---|---|
| 67 | Machine offline after the build | I P C B | setup, lake, start, VaR all work | any connection attempt | E, W |
| 68 | Machine offline for the first build | I A | A: `[FAIL] the first build needs your Maven repository or the network` after Maven's failure | a hang with no word | U |
| 69 | The company index rejects the login; `pip.ini` or `PIP_*` set (index, `require-virtualenv`, `user`, `target`) | P C | no effect on setup from the pack | the index contacted; "a virtualenv is required" | U, E, W |
| 70 | `setup --index` and the index answers 401, has no route, or lacks a package | P C | one sentence and one line to paste | pip's forty lines as the only word | U (exists) |
| 71 | A company proxy re-signs TLS for Maven | I A | both build routes trust the Windows certificate store | A differing from I | U |

Matrix size: **71 situations, 194 cells** (situation × entry point), of which 7 situations need a look in the IDE (M) that no
machine can give.

---

## C. Real Windows proof (GitHub Actions)

A workflow in the index repository `drishti-platform/drishti-platform`, started by hand for a named release. It uses only what the
owner downloads: the source zip and the wheel pack of that release's page. It needs no access to the product repositories.

**Draft, not committed.** The lead asks the owner before adding it.

### C1. What it does

1. A fresh VM. On Windows: Java 21 (Temurin), then python.org's Python 3.13 installed for the user with the `py` launcher and **not**
   on the PATH, as on a company PC. Every Python of the runner image is taken off the PATH and shims named `python`, `python3`, `pip`,
   `git`, `mvn` are put first that write to a log and fail.
2. The two release files are fetched into `Downloads`. The source zip is unzipped into `IdeaProjects\drishti-platform (1)` under a home
   folder named `John Smith`. The wheel pack is **not** unzipped.
3. `py -3.13 build_all.py` (no git, no Spotless, Rachana's helper through `py`).
4. Negative cases, cheap, before anything is prepared: the pack hidden (row 21); the wrong Python (row 26, a Python 3.12 of the image).
5. Left-overs are planted: an old `dev.ini` and `jvm-demo.args` naming `C:\old\drishti-orig`, and `DRISHTI_HOME`, `DRISHTI_DATA_DIR`,
   `DRISHTI_TMP_DIR` naming it for **all later steps** (rows 10 to 12); a dead package index, `PIP_REQUIRE_VIRTUALENV`, and a dead proxy
   in the environment (rows 56, 69).
6. `py -3.13 tools\drishti.py setup`, then the same line again (row 02), with the settings folder hashed between.
7. `lake demo --engine java`, `start all --demo`, the acceptance check (`VAR BANK` through the console: 167,372,692 and 210,131,972),
   `status`, `stop all`.
8. The bare console (entry point B): the server from the CLI, the console as `py -3.13 drishti-console\run_drishti_web.py`, the
   acceptance check again, stop.
9. Final assertions: the shim log is empty; `C:\old` does not exist; no folder named `.venv` or `venv` and no `pyvenv.cfg` anywhere
   under the home folder; no `Traceback` in any captured output; both ports free.

Ubuntu runs the same with the user's own environment (`python3.13 -m venv "$HOME/my py"`, made by the workflow playing the user) and
adds row 35 with `/usr/bin/python3`. macOS (arm64) runs on request only.

### C2. The workflow

Three things in it do not exist yet and are part of section D: `build_all.py` with no `--bundle` (D4), `doctor --acceptance` (D9), and
the pack being found as a zip in Downloads (D11). Until then the lines marked `# D…` name today's form in a comment.

```yaml
# .github/workflows/first-run.yml   (DRAFT: not committed; see docs/plans/FIRST_RUN_ROBUSTNESS.md in the drishti repository)
name: first-run
on:
  workflow_dispatch:
    inputs:
      release:
        description: "Release on this repository's Releases page (a draft is enough), e.g. i7g"
        required: true
      macos:
        description: "Also run on macOS (10x minutes)"
        type: boolean
        default: false
permissions:
  contents: write            # to read the assets of a DRAFT release; 'read' is enough once it is published
concurrency:
  group: first-run-${{ inputs.release }}
  cancel-in-progress: true
env:
  REL: ${{ inputs.release }}
  GH_TOKEN: ${{ github.token }}
  PY_FULL: "3.13.9"          # the python.org build installed on Windows: keep it at the company's 3.13.x
  VAR: "167,372,692"
  ES: "210,131,972"

jobs:
  windows:
    runs-on: windows-latest
    timeout-minutes: 75
    defaults:
      run:
        shell: pwsh
    env:
      PACK: drishti-platform-${{ inputs.release }}-wheels-windows-x64-py313.zip
    steps:
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }

      - name: A home folder with a space; python.org Python for the user, with py, not on the PATH
        run: |
          $ErrorActionPreference = "Stop"
          $home2 = "C:\Users\John Smith"
          New-Item -ItemType Directory -Force "$home2\Downloads", "$home2\IdeaProjects", "$env:RUNNER_TEMP\poison", "$env:RUNNER_TEMP\out" | Out-Null
          Invoke-WebRequest "https://www.python.org/ftp/python/$env:PY_FULL/python-$env:PY_FULL-amd64.exe" -OutFile "$env:RUNNER_TEMP\python-setup.exe"
          Start-Process -Wait "$env:RUNNER_TEMP\python-setup.exe" -ArgumentList "/quiet","InstallAllUsers=0","PrependPath=0","Include_launcher=1","Include_test=0","Include_doc=0","Include_tcltk=0"
          $launcher = "$env:LOCALAPPDATA\Programs\Python\Launcher"
          # the PATH of every later step: no Python of the image, the shims first
          foreach ($n in "python","python3","pip","pip3","git","mvn") {
            Set-Content "$env:RUNNER_TEMP\poison\$n.cmd" "@echo %~n0 %* >> `"$env:RUNNER_TEMP\out\poison.log`"`r`n@exit /b 9009"
          }
          $keep = ($env:PATH -split ";" | Where-Object { $_ -and $_ -notmatch "\\Python|\\Git\\|\\maven" }) -join ";"
          "PATH=$env:RUNNER_TEMP\poison;$launcher;$keep" | Out-File -Append $env:GITHUB_ENV
          "USERPROFILE=$home2"  | Out-File -Append $env:GITHUB_ENV
          "PLATFORM=$home2\IdeaProjects\drishti-platform (1)" | Out-File -Append $env:GITHUB_ENV
          "OUT=$env:RUNNER_TEMP\out" | Out-File -Append $env:GITHUB_ENV

      - name: Which Python py starts (must be the python.org 3.13)
        run: |
          py -0p
          py -3.13 -c "import sys; print(sys.executable, sys.version)"
          if (Get-Command python.exe -ErrorAction SilentlyContinue) { throw "a python.exe is still on the PATH" }

      - name: Download the two release files; unzip the source only
        run: |
          $ErrorActionPreference = "Stop"
          # the real gh and tar by full path: the PATH has none of the image's tools the user lacks
          & "C:\Program Files\GitHub CLI\gh.exe" release download $env:REL --repo $env:GITHUB_REPOSITORY --dir "$env:USERPROFILE\Downloads" `
              --pattern "drishti-platform-$env:REL-source.zip" --pattern $env:PACK
          New-Item -ItemType Directory -Force $env:PLATFORM | Out-Null
          & "$env:SystemRoot\System32\tar.exe" -xf "$env:USERPROFILE\Downloads\drishti-platform-$env:REL-source.zip" -C $env:PLATFORM
          Move-Item "$env:USERPROFILE\Downloads\$env:PACK" "$env:RUNNER_TEMP\$env:PACK"      # hidden until the negative case is done
          if (Test-Path "$env:PLATFORM\drishti\.git") { throw "the source zip must hold no .git" }

      - name: Build (build_all.py; no git, no Spotless, py for the build helpers)
        working-directory: ${{ env.PLATFORM }}
        run: |
          py -3.13 build_all.py --skip-tests 2>&1 | Tee-Object "$env:OUT\build.txt"      # D4; today: build_all.py --bundle . --skip-tests
          if ($LASTEXITCODE) { throw "build failed" }
          if (@(Get-ChildItem "drishti\lib\drishti-server-*-exec.jar").Count -ne 1) { throw "exactly one server jar expected in drishti\lib" }

      - name: "Negative: no wheel pack (row 21); the wrong Python (row 26)"
        working-directory: ${{ env.PLATFORM }}\drishti
        run: |
          py -3.13 tools\drishti.py setup 2>&1 | Tee-Object "$env:OUT\neg-nopack.txt"
          if ($LASTEXITCODE -eq 0) { throw "setup must fail without a wheel pack" }
          $t = Get-Content -Raw "$env:OUT\neg-nopack.txt"
          if ($t -notmatch "\[FAIL\].*wheel pack" -or $t -notmatch "NOT ready") { throw "no plain [FAIL] line for the missing pack" }
          if ($t -match "Traceback") { throw "a traceback" }
          Move-Item "$env:RUNNER_TEMP\$env:PACK" "$env:USERPROFILE\Downloads\$env:PACK"
          $py312 = (Get-ChildItem "C:\hostedtoolcache\windows\Python\3.12.*\x64\python.exe" | Select-Object -First 1).FullName
          & $py312 tools\drishti.py setup 2>&1 | Tee-Object "$env:OUT\neg-py312.txt"
          if ($LASTEXITCODE -eq 0) { throw "setup must refuse a Python 3.12 with a 3.13 pack" }
          if ((Get-Content -Raw "$env:OUT\neg-py312.txt") -notmatch "\[FAIL\].*3\.13.*3\.12") { throw "the [FAIL] line must name both versions" }

      - name: Plant the left-overs (rows 10 to 12, 56, 69) for every later step
        run: |
          $ErrorActionPreference = "Stop"
          $envs = "$env:USERPROFILE\drishti-envs"
          Remove-Item -Recurse -Force $envs -ErrorAction SilentlyContinue        # the negative runs made this checkout's files
          New-Item -ItemType Directory -Force $envs | Out-Null
          Set-Content "$envs\dev.ini" "drishti.home=C:/old/drishti-orig`r`ndrishti.data.dir=C:/old/drishti-orig/data`r`ndrishti.python=C:/old/drishti-orig/drishti-console/.venv/Scripts/python.exe"
          Set-Content "$envs\jvm-demo.args" "`"-Djava.io.tmpdir=C:/old/drishti-orig/data/tmp/server`""
          foreach ($kv in "DRISHTI_HOME=C:\old\drishti-orig", "DRISHTI_DATA_DIR=C:\old\drishti-orig\data", "DRISHTI_TMP_DIR=C:\old\drishti-orig\data\tmp",
                          "PIP_INDEX_URL=http://127.0.0.1:9/simple", "PIP_REQUIRE_VIRTUALENV=true",
                          "HTTP_PROXY=http://127.0.0.1:9", "HTTPS_PROXY=http://127.0.0.1:9", "NO_PROXY=") { $kv | Out-File -Append $env:GITHUB_ENV }

      - name: Setup, the one line; then the same line again (row 02)
        working-directory: ${{ env.PLATFORM }}\drishti
        run: |
          function Tree($d) { (Get-ChildItem -Recurse -File $d | Sort-Object FullName | ForEach-Object { $_.FullName + " " + (Get-FileHash $_.FullName).Hash }) -join "`n" }
          py -3.13 tools\drishti.py setup 2>&1 | Tee-Object "$env:OUT\setup-1.txt"
          if ($LASTEXITCODE) { throw "setup failed" }
          $t = Get-Content -Raw "$env:OUT\setup-1.txt"
          foreach ($need in "\[move\]", "\[WARN\].*DRISHTI_DATA_DIR", "Drishti is ready\.") { if ($t -notmatch $need) { throw "setup output lacks $need" } }
          if ($t -match "Traceback|\.venv.*made|127\.0\.0\.1:9") { throw "a traceback, a venv, or the package index" }
          $before = Tree "$env:USERPROFILE\drishti-envs"
          py -3.13 tools\drishti.py setup 2>&1 | Tee-Object "$env:OUT\setup-2.txt"
          if ($LASTEXITCODE) { throw "the second setup failed" }
          if ((Get-Content -Raw "$env:OUT\setup-2.txt") -match "\[made\]|\[move\]|\[FAIL\]|\[todo\]") { throw "the second setup changed or wanted something" }
          if ((Tree "$env:USERPROFILE\drishti-envs") -ne $before) { throw "the second setup changed the settings folder" }
          if (@(Get-ChildItem "$env:USERPROFILE\drishti-envs" -Directory -Filter "replaced-*").Count -ne 1) { throw "exactly one replaced-* folder expected" }

      - name: Demo lake, start, VaR, status, stop
        working-directory: ${{ env.PLATFORM }}\drishti
        run: |
          py -3.13 tools\drishti.py lake demo --engine java 2>&1 | Tee-Object "$env:OUT\lake.txt"; if ($LASTEXITCODE) { throw "lake demo failed" }
          py -3.13 tools\drishti.py start all --demo 2>&1 | Tee-Object "$env:OUT\start.txt";        if ($LASTEXITCODE) { throw "start all failed" }
          py -3.13 tools\drishti.py doctor --acceptance 2>&1 | Tee-Object "$env:OUT\accept.txt"     # D9: signs in to the console, asks VAR BANK
          if ($LASTEXITCODE) { throw "acceptance failed" }
          $t = Get-Content -Raw "$env:OUT\accept.txt"
          if ($t -notmatch [regex]::Escape($env:VAR) -or $t -notmatch [regex]::Escape($env:ES)) { throw "VaR or ES differs" }
          py -3.13 tools\drishti.py status;   if ($LASTEXITCODE) { throw "status says something is not running" }
          py -3.13 tools\drishti.py stop all; if ($LASTEXITCODE) { throw "stop failed" }

      - name: The bare console (entry point B) with the server from the command line
        working-directory: ${{ env.PLATFORM }}\drishti
        run: |
          py -3.13 tools\drishti.py start server --demo --background; if ($LASTEXITCODE) { throw "start server failed" }
          $py = (py -3.13 -c "import sys; print(sys.executable)")
          $bare = Start-Process -PassThru -NoNewWindow $py -ArgumentList "drishti-console\run_drishti_web.py" -RedirectStandardError "$env:OUT\bare.txt"
          py -3.13 tools\drishti.py doctor --acceptance --wait 120 2>&1 | Tee-Object "$env:OUT\accept-bare.txt"
          $rc = $LASTEXITCODE
          Stop-Process -Id $bare.Id -Force
          py -3.13 tools\drishti.py stop all
          if ($rc) { throw "the bare console did not reach the server (see bare.txt)" }

      - name: What must not have happened
        if: always()
        run: |
          $bad = @()
          if (Test-Path "$env:OUT\poison.log") { $bad += "python/pip/git/mvn was called by name: " + (Get-Content -Raw "$env:OUT\poison.log") }
          if (Test-Path "C:\old") { $bad += "C:\old was made: " + ((Get-ChildItem -Recurse C:\old | Select-Object -First 5).FullName -join ", ") }
          $venvs = Get-ChildItem -Recurse -Force $env:USERPROFILE -Include ".venv","venv","pyvenv.cfg" -ErrorAction SilentlyContinue
          if ($venvs) { $bad += "a virtual environment: " + ($venvs.FullName -join ", ") }
          $tb = Select-String -Path "$env:OUT\*.txt" -Pattern "Traceback \(most recent call last\)" -List
          if ($tb) { $bad += "a traceback in: " + ($tb.Path -join ", ") }
          foreach ($port in 18480, 17480) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { $bad += "port $port still held" } }
          if ($bad) { $bad | ForEach-Object { Write-Host "::error::$_" }; exit 1 }

      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: first-run-windows-${{ inputs.release }}
          path: |
            ${{ runner.temp }}\out
            C:\Users\John Smith\IdeaProjects\drishti-platform (1)\drishti\data\logs
            C:\Users\John Smith\drishti-envs
          retention-days: 7

  posix:
    strategy:
      fail-fast: false
      matrix:
        include:
          - { os: ubuntu-latest, target: linux-x64, run: true }
          - { os: macos-latest, target: macos-arm64, run: "${{ inputs.macos }}" }
    runs-on: ${{ matrix.os }}
    timeout-minutes: 60
    env:
      PACK: drishti-platform-${{ inputs.release }}-wheels-${{ matrix.target }}-py313.zip
    steps:
      - uses: actions/setup-java@v4
        if: matrix.run == true || matrix.run == 'true'
        with: { distribution: temurin, java-version: "21" }
      - uses: actions/setup-python@v5
        if: matrix.run == true || matrix.run == 'true'
        with: { python-version: "3.13" }
      - name: The whole first run with the user's own environment
        if: matrix.run == true || matrix.run == 'true'
        shell: bash
        run: |
          set -euo pipefail
          BASE="$(command -v python3.13)"
          export HOME="$RUNNER_TEMP/John Smith"; OUT="$RUNNER_TEMP/out"; OLD="$RUNNER_TEMP/old/drishti-orig"
          PLATFORM="$HOME/IdeaProjects/drishti-platform (1)"
          mkdir -p "$HOME/Downloads" "$PLATFORM" "$OUT" "$RUNNER_TEMP/poison"
          gh release download "$REL" --repo "$GITHUB_REPOSITORY" --dir "$HOME/Downloads" \
             --pattern "drishti-platform-$REL-source.zip" --pattern "$PACK"
          unzip -q "$HOME/Downloads/drishti-platform-$REL-source.zip" -d "$PLATFORM"
          for n in python pip pip3 git mvn; do printf '#!/bin/sh\necho "$0 $*" >> "%s/poison.log"\nexit 127\n' "$OUT" > "$RUNNER_TEMP/poison/$n"; chmod +x "$RUNNER_TEMP/poison/$n"; done
          export PATH="$RUNNER_TEMP/poison:$PATH"

          cd "$PLATFORM"
          "$BASE" build_all.py --skip-tests 2>&1 | tee "$OUT/build.txt"                      # D4

          cd "$PLATFORM/drishti"
          if [ "$RUNNER_OS" = Linux ]; then                                                 # row 35: the system's own Python
            if /usr/bin/python3 tools/drishti.py setup > "$OUT/neg-pep668.txt" 2>&1; then echo "::error::setup accepted the system Python"; exit 1; fi
            grep -q '\[FAIL\].*managed by the operating system' "$OUT/neg-pep668.txt"
          fi
          "$BASE" -m venv "$HOME/my py"                                                     # the USER's environment, never Drishti's
          PY="$HOME/my py/bin/python"

          mkdir -p "$HOME/drishti-envs"                                                     # rows 10 to 12, 56, 69
          printf 'drishti.home=%s\ndrishti.data.dir=%s/data\n' "$OLD" "$OLD" > "$HOME/drishti-envs/dev.ini"
          export DRISHTI_HOME="$OLD" DRISHTI_DATA_DIR="$OLD/data" DRISHTI_TMP_DIR="$OLD/data/tmp"
          export PIP_INDEX_URL=http://127.0.0.1:9/simple HTTP_PROXY=http://127.0.0.1:9 HTTPS_PROXY=http://127.0.0.1:9 NO_PROXY=

          "$PY" tools/drishti.py setup 2>&1 | tee "$OUT/setup-1.txt"
          grep -q '\[move\]' "$OUT/setup-1.txt"; grep -q 'Drishti is ready\.' "$OUT/setup-1.txt"
          tree1="$(find "$HOME/drishti-envs" -type f -exec shasum -a 256 {} + | sort -k2)"
          "$PY" tools/drishti.py setup 2>&1 | tee "$OUT/setup-2.txt"
          if grep -Eq '\[(made|move|FAIL|todo)\]' "$OUT/setup-2.txt"; then echo "::error::the second setup changed or wanted something"; exit 1; fi
          [ "$tree1" = "$(find "$HOME/drishti-envs" -type f -exec shasum -a 256 {} + | sort -k2)" ]

          # from here on WITHOUT naming the environment: drishti.python of dev.ini carries it (rule: one Python)
          "$BASE" tools/drishti.py lake demo --engine java 2>&1 | tee "$OUT/lake.txt"
          "$BASE" tools/drishti.py start all --demo 2>&1 | tee "$OUT/start.txt"
          "$BASE" tools/drishti.py doctor --acceptance 2>&1 | tee "$OUT/accept.txt"         # D9
          grep -q "$VAR" "$OUT/accept.txt"; grep -q "$ES" "$OUT/accept.txt"
          "$BASE" tools/drishti.py status
          "$BASE" tools/drishti.py stop all

          bad=0
          [ ! -e "$OUT/poison.log" ] || { echo "::error::called by name: $(cat "$OUT/poison.log")"; bad=1; }
          [ ! -e "$RUNNER_TEMP/old" ] || { echo "::error::the old clone's folder was made"; bad=1; }
          if find "$PLATFORM" "$HOME/drishti-envs" \( -name .venv -o -name pyvenv.cfg \) | grep -q .; then echo "::error::a virtual environment inside Drishti's folders"; bad=1; fi
          if grep -l 'Traceback (most recent call last)' "$OUT"/*.txt; then echo "::error::a traceback"; bad=1; fi
          exit $bad
      - uses: actions/upload-artifact@v4
        if: always() && (matrix.run == true || matrix.run == 'true')
        with:
          name: first-run-${{ matrix.target }}-${{ inputs.release }}
          path: ${{ runner.temp }}/out
          retention-days: 7
```

Notes on the draft, to settle when it is first run (it has not been run):

- `gh release download` of a **draft** release with the workflow's token (`contents: write`) **(to verify)**. If it does not work, the
  release is published as a pre-release first, which makes its tag on the index repository; that needs the owner's word like any tag.
- The per-user Python installer puts `py.exe` in `%LOCALAPPDATA%\Programs\Python\Launcher`; the step after it prints `py -0p` and
  stops the job when `py -3.13` is not that Python **(to verify on the first run)**.
- `USERPROFILE` is redirected for the Python side. Java's `user.home` does not follow it; the job runs no IDE launcher, so nothing
  there reads it.
- The runner account is an administrator: row 34 (no write under `Program Files`) is not proved here.

### C3. Runtime and cost (estimates, not measured)

| Job | Build | Setup, lake, start, checks | Total | Billed |
|---|---|---|---|---|
| windows-latest | 20 to 30 min | 10 to 15 min | 35 to 45 min | ×2 |
| ubuntu-latest | 10 to 15 min | 8 to 10 min | 20 to 25 min | ×1 |
| macos-latest (on request) | 10 to 15 min | 8 to 10 min | 20 to 25 min | ×10 |

About 110 billed minutes for a Windows and Ubuntu run, about 350 with macOS. One run per release candidate and one per fix of a failed
run: a few hundred minutes a release. A cache of the Maven repository would cut the build by about a third and is left out on purpose
for the first runs (a fresh machine is the point).

### C4. What it cannot cover

- **The IDEs.** Which launchers a project shows, the Trust dialog, indexing, choosing the interpreter in PyCharm, the red marks.
  Covered partly by the launcher-equivalent runner (the command a launcher runs) and fully only by the hand check of section E3.
- **The company's machine.** Its real `pip.ini`, its TLS-re-signing proxy and system proxy, antivirus or AppLocker rules, group
  policy on the user site or on `py`, the Microsoft Store stubs themselves (imitated by shims), a non-administrator account,
  OneDrive's sync engine, the firewall prompt at the first start.
- **The first Maven build through the company's repository.** The runner reaches Maven Central directly.

---

## D. Review of `origin/release/i7g` at `f3a3e672a`

The lane is rebuilding this branch on the owner's final rules; the list is written against the pushed commit.

### D0. Already right (keep, and keep its tests)

- Setup moves a foreign `dev.ini` and a differing `jvm-*.args` to `replaced-<date>`; a locked file is a `[FAIL]`; nothing is made
  outside the checkout without `--data-dir`; foreign `DRISHTI_*` folder variables are reported and ignored **by setup** (`envsetup.py`,
  `settingsfile.py`; `tools/test_setup_collisions.py`).
- Offline by default: pip only with a pack or `--index`; PEP 668 and a pack for another Python are one `[FAIL]` each; a pack one folder
  deep is found; a missing wheel is named; a 401 is one sentence (`pylibs.py`; `tools/test_setup_split.py`).
- `start`, `start all` and the bare console install nothing and refuse an unprepared Python with the setup line; `start all` checks
  the console's Python before the server starts (`consolestart.py`, `launchcli.py`).
- The development secrets are spelled once (`devsecrets.py`) and the bare console takes them.
- The default `dev.ini` is taken by the tools only when it names this checkout (`drishti.py`, `default_settings`).
- Next steps never offer `start` while a `[FAIL]` or `[todo]` remains; `--check` changes nothing.
- Stop by pid file only; a process of another install is never stopped; `status` shows processes started elsewhere.
- Launchers are generated from one table with a check; no launcher names a `.venv`; `jvm-*.args` quotes paths with forward slashes.

### D1. What must change, in order

**P0: without these the same classes of failure remain open.**

| # | Finding (file) | Change | Test |
|---|---|---|---|
| D1 | **Drishti still makes and probes a `.venv`.** `envsetup.make_venv`, `setup venv`, `--reinstall`, `--python`, `--system`, the uv branch, `bootstrap`'s venv branch; `procs.old_venv`, `venv_python` and step 3 of `tools_python` (the folder probe); `pylibs.in_venv` (`".venv" in parts`); the words "on Linux and macOS drishti-console/.venv" in `drishti.py`, `consolestart.py`, `launchcli.py`; `docs/PRODUCTION_DEPLOYMENT.md` (about thirty lines, the console unit's `ExecStart`); 198 occurrences of `.venv` in tracked files. | Remove the making and the probe (P1, P2, P3). Setup = the one mode: into the running Python. `--system` accepted for one release as a word with no effect (START_HERE i7f prints it). PEP 668's fix line names the user's own environment. The production guide: the administrator makes the environment or the dedicated Python and runs setup with it; units name `drishti.python`'s value. | S: no `.venv` in any tracked file but the ignore files; no `venv`-making call anywhere, setup included. E, W: write-set oracle finds no `.venv`, `pyvenv.cfg` |
| D2 | **Start, stop, status and the lake commands still follow a stale variable.** `javacmd.launch_roots` takes `DRISHTI_DATA_DIR` when the settings file has no key; `default_settings()` gives no file when `dev.ini` is absent or another checkout's; then `serverstart.start` runs `data.mkdir(parents=True)` (`serverstart.py:200`) before any check, and `consolestart.start` the same: the folder in the old clone again, by `start` instead of `setup`. `stop all` would read that folder's pid files. Read, not run. | R3, R5: the resolver ignores a foreign environment root unless `--settings` was given, one `[WARN]`; `start` never makes a data root; with no settings file of this checkout every command but setup, build and help stops with the setup line (row 04). | U (`ntpath`), E rows 04, 11, 12 with the decoy clone, W (the variables stay set for every step) |
| D3 | **Each IDE sees the other's launchers.** IntelliJ loads every `*.run.xml` inside the opened project **(to verify, check IDE-1 in E3; START_HERE of i7f already tells the user that red marks are expected)**. Opening `drishti` therefore shows `drishti-console/run-configs/` as ten unknown entries, and opening the platform folder shows all three sets, the Drishti ones with a wrong `$PROJECT_DIR$`. The index repository's START_HERE (main) still sends PyCharm to `drishti` (no launchers there in i7g) and still says `setup --system --wheels`. | If IDE-1 confirms: the launchers move to each project's own `.idea/runConfigurations/` (read only by the project opened at that folder), committed and in the zip, no `*.run.xml` anywhere; decision F1. START_HERE and both IDE guides rewritten from the launcher table. | S: the three sets, their places, no `*.run.xml` elsewhere in the source zip; START_HERE names `drishti\drishti-console` for PyCharm. M: IDE-1 to IDE-3 |
| D4 | **Two build routes that differ.** The platform pom passes `-Dpython3=py`, `-Dspotless.check.skip=true` and the Windows trust store on Windows; `build_all.py` (`maven_command`) passes none of them, checks no Java, and needs `--bundle <folder>`. The i7b failures (Store stub, Spotless on LF files) are open on this route, which is the one the workflow and a non-IDE user take. | One table of Maven arguments read by both (the pom's `commandlineArgs` generated or checked from it); `build_all.py` with no option builds the folder it lies in; a Java 21 check and a "Drishti is running from this folder" check before Maven. | U: `build_all` arguments equal the pom's for Windows and for Linux. W: the build step under the poisoned PATH |
| D5 | **Proxy settings reach Drishti's own calls.** The console's `httpx.AsyncClient(...)` calls (`core/backend.py`, `cluster.py`, `servers.py`, `server_facts.py`, `backend_watch.py`) use the default `trust_env=True`; `procs.http_ok` and the CLI's `urllib` use the system proxy (on Windows also the registry's). `start all` points the console at the LAN address, which a company's exemption list rarely holds. On a PC with a proxy this gives "did not answer" or the proxy's refusals in place of the server's answers **(to verify on the owner's PC: not proved to be the cause of any past failure)**. | G5: `trust_env=False` on every client that talks to a Drishti server; an opener with `ProxyHandler({})` in the tools. | S: no `httpx` client and no `urlopen` to a Drishti address without it. E, W: dead proxy in the environment for the whole run |
| D6 | **No single failure funnel, and fix lines that cannot be pasted on Windows.** `drishti.py main` catches seven named exceptions; an `OSError`, `PermissionError`, `UnicodeError` or a YAML error is a traceback. `run_drishti_web.py` and `build_all.py` have none. At least ten messages spell `python tools/drishti.py …` (`run_drishti_web.SETUP_LINE`, `consolestart.setup_line`, `envsetup.FIX_LIBS` and the next steps, `launchcli.cmd_cluster`, `drishti.py`), which on the owner's PC is the Store stub; `pip_line` prints `py`, which may start another Python than the one used. | E1, E2, E6: one `run_guarded(main)` used by the three entry scripts; one `procs.command(*args)` that prints the running interpreter, quoted, with the system's separators. | S: no message string spells the command by hand. U: `ntpath` rendering with a space in the path. E, W: output oracle |
| D7 | **A foreign `dev.ini` is still followed where it is passed by path.** The bare console takes `~/drishti-envs/dev.ini` whenever it exists and only warns (`run_drishti_web.py`, `bare_defaults`, `warn_other_home`: "its data comes from <other>"); the PyCharm and IntelliJ launchers pass the fixed path, so after another checkout's setup this checkout's server and console run on the other's data. Two functions decide "is it mine" (`drishti.py`, `run_drishti_web.py`). | R6, F4: one function; a file naming another home gives no roots, in Python and in `LaunchRoots` (Java). | U, J, E rows 11 and 65 |
| D8 | **`start --demo` with no demo lake.** No check was found in `serverstart.py` **(to verify what the server then shows)**. | G2: the preflight names the lake line; the server says it once in its log. | E row 06 |
| D9 | **No command proves the result.** The acceptance today is a person typing `VAR BANK`. | `doctor --acceptance [--wait N]`: signs in to the console as the development administrator, asks `VAR BANK` through the console (so the secrets of both are proved to agree), prints both figures; exit 0 only when they are the release's. Used by the workflow, E-full, and the launcher **Drishti: check environment**. | E-full, W |
| D10 | **pip can still be bent by the company's configuration.** `pylibs.install` runs `pip install -q --no-index --find-links …` without `--isolated`: a `pip.ini` or `PIP_*` with `require-virtualenv`, `user`, `target` or `find-links` still applies. | S5. | U: the exact arguments. W: `PIP_REQUIRE_VIRTUALENV=true` and a dead index for the whole run |

**P1: needed for "zero manual work"; each small.**

| # | Finding | Change | Test |
|---|---|---|---|
| D11 | The pack is found only as `.whl` files under `~/drishti-wheels` (`envsetup.default_wheels`); only its Python is checked (`pylibs.pack_mismatch`), not its system or release. | S4: the zip in the platform folder or Downloads; `pack.json` written by `make_wheels.py`; the three checks; `SHA256SUMS` verified. | U rows 21 to 29 |
| D12 | Setup has no final verdict line and no `[skip]`; `todo` and `FAIL` are both "next steps". | S3, S9. | U, output oracle |
| D13 | The server's fallback when `drishti.python` is blank is `python` on Windows (`application.yaml:225`, `compute.yaml:20`). | P5: blank on Windows means "not configured: run setup", said in the feature's answer. | J |
| D14 | `start all` checks the console's Python before the server but the console's port only after the server is up. | G2: one preflight for both. | U, E row 49 |
| D15 | The home folder is spelled four ways (`envsetup.envs_dir` with `USERPROFILE`; `default_wheels`, `launchers.py`, `run_drishti_web.py` with `Path.home()`; `drishti.py` with `USERPROFILE`). | One `user_home()`. | S |
| D16 | Settings files are written in place (`ini.write_text`); output is not guarded against the console's code page; the entry scripts import before they check the Python version. | R7, E6, P6. | U |
| D17 | Unzipping over an older release is not noticed; two server jars in `lib/` stop the start. | Row 63: compare with the zip's `SHA-256SUMS`; the build leaves one jar. | U, E |
| D18 | A stale `DRISHTI_PYTHON` or a secret from the environment is used without a word. | P4, X3. | U rows 13, 15 |

**P2: can follow i7g (into `I8_CARRY_IN.md`).** The console's address in `start all` on one machine (loopback, row 57; touches the
owner's real-address rule, decision F3); OneDrive, path-length and non-ASCII warnings (rows 45 to 47); the Microsoft Store Python
warning (row 33); the user-site check (row 34); the `JAVA_HOME` shape hints (row 43); the IntelliJ server launcher's dependence on
`jvm-<profile>.args` before setup (row 04, I).

---

## E. Release gate for i7g

i7g is not tagged until all of E1 to E4 hold.

**E1. Design guards (S, J), all green.** No `.venv` in a tracked file but the ignore files and no venv-making call (D1); no
hand-spelled command in a message (D6); no Drishti-internal HTTP call that trusts the environment (D5); one home-folder function (D15);
the three launcher sets in their places and nowhere else (D3); `build_all.py` arguments equal the platform pom's (D4); `LaunchRoots`
ignores the roots of a foreign file (D7).

**E2. Matrix rows that must be green (U and E-light on Linux, every lane run).**

- Clean machine and order: 02, 03, 04, 05, 06, 08, 09.
- Left-overs: 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20.
- Wheel pack: 21, 22, 23, 24, 25, 26, 27, 28, 29, 30.
- Python: 32, 35, 36, 37, 39.
- Java: 40, 41.
- Paths: 44, 48.
- Ports and order: 49, 50, 51, 53, 54, 55, 56.
- Launchers: 62.
- Upgrade: 64, 65.
- Network: 67, 69, 70, 71.

With the three oracles on every E cell, and each E command run **twice** (the second run changes nothing).

**E3. Once for the release.**

- **E-full on Linux** (row 01 with rows 52 and 54): from `git archive` and a real pack, in the scratch home with a space, to both
  figures; through `heavy.sh`.
- **The workflow of section C green on windows-latest and ubuntu-latest**, on the release's own two files (needs the owner's word to
  add it, decision F2). Without it the gate is E-full plus the owner's own run, which is the state that failed six times.
- **The hand check in the IDEs** (rows 01, 38, 39, 58 to 61), by the lead on the Linux machine's IntelliJ and PyCharm, on the unzipped release
  zip in a scratch home, about fifteen minutes:
  - IDE-1: IntelliJ, open the platform `pom.xml`: the run list holds exactly the platform launchers.
  - IDE-2: IntelliJ, open `drishti/pom.xml`: exactly the six Java launchers, none red; the Maven project loads by itself.
  - IDE-3: PyCharm, open `drishti/drishti-console`: exactly the ten Python launchers; with no interpreter chosen PyCharm says so; with
    the one setup named, **Drishti: run console** starts.
- **A Fable gate review** of the lane's final commit against sections A and D.

**E4. Not required for i7g, recorded as known limits:** rows 33, 34, 42 (optional job), 43, 45, 46, 47, 57, 63, 66, 68; row 04 for the
IntelliJ server launcher; everything in C4.

---

## F. Decisions the lead must take

| # | Decision | Recommendation |
|---|---|---|
| F1 | Launchers in each project's `.idea/runConfigurations/` (a hidden folder, but the IDE's own and the only place that keeps the three sets apart), if IDE-1 confirms the leak. | Yes; ask the owner, because of the no-hidden-names rule. The alternative is to accept the other IDE's entries, which the owner has ruled out. |
| F2 | Add the workflow of section C to the index repository. | Yes, Windows and Ubuntu per release candidate, macOS on request. It is the only step in this plan that runs the release on a machine that is not ours. |
| F3 | On one machine, `start all` gives the console the loopback address of the server instead of the LAN address. | Yes for the console's own calls (it also removes the proxy and VPN cases); the addresses printed for people and advertised by cluster members stay real. |
| F4 | A foreign environment root is ignored by every command unless `--settings` is given (R5). The drill and any test that sets `DRISHTI_DATA_DIR` without a settings file must pass `--data-dir` instead. | Yes; the lane lists the callers first (`grep -rn DRISHTI_DATA_DIR tools/drill* testing tools/*test*`). |
| F5 | The release candidate's files for the workflow: a draft release on the index repository, built from `release/i7g` before any tag. | Yes; no tag is made before the gate. |
