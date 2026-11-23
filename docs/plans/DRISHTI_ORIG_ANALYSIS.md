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
# Why `drishti-orig` keeps coming back (2026-11-23)

Analysis only: nothing was built or changed. Source read: Drishti `v2.9.11.i_7e`, Drishti Tools `v1.1.0`, Rachana `v1.0.1`,
Lake `v0.9.1`, the platform index at `i7e`. Paths below are relative to each product's folder.

**The case.** Windows, user folder `C:\Users\<owner>`, company Python 3.13 through `py`, no `.venv`. Work happens only in
`C:\Users\<owner>\IdeaProjects\drishti-platform` (the unzipped platform source). Two operations were run:
(a) the IntelliJ launcher `platform-clean-install` (the platform `pom.xml`: each product's `mvnw clean install`, tests skipped), and
(b) `py tools\drishti.py setup --system` in `drishti-platform\drishti`. Afterwards `C:\Users\<owner>\IdeaProjects\drishti-orig`
exists again. `%USERPROFILE%\drishti-envs\dev.ini` is an old file from the `drishti-orig` clone: it names `drishti.home`,
`drishti.data.dir` and `drishti.modules.home` under `drishti-orig`.

## 1. The answer

**Setup creates it, not the build.** `setup` writes the IDE server launchers' JVM argument files
(`jvm-demo.args`, `jvm-qa.args`, `jvm-samples.args`) and, while doing so, **makes the folders those files name**. It takes the data
folder from the existing `dev.ini`, which says `drishti-orig\data`. The step runs although setup has just printed the WARNING that
the file points at another folder.

Reproduced on Linux with the i7e source and a `dev.ini` naming a folder that did not exist (no `DRISHTI_*` variable set):
setup printed `[WARN] settings file ... points at .../drishti-orig`, then `[made] JVM options`, and the folder appeared with exactly:

```
drishti-orig\data\tmp\server
drishti-orig\data\dumps\server
drishti-orig\data\logs
drishti-orig\data\run
```

All four empty, nothing else (no `delta`, no `dataload`, no `lib`, no `modules`). **That content is the signature of this cause.**

The same run also wrote the three `jvm-*.args` files with `-Djava.io.tmpdir=...\drishti-orig\data\tmp\server`,
`-XX:HeapDumpPath=...\drishti-orig\data\dumps\server` and the GC log under `drishti-orig\data\logs`. So the next server started
from an IntelliJ launcher (`@jvm-<profile>.args -Ddrishti.home=$PROJECT_DIR$ -Ddrishti.settings.file=dev.ini,...`) runs with its
data, temp files, dumps and modules in `drishti-orig`, with only a WARNING in the log.

The Maven build of the four products writes nothing outside the checkout, `~/.m2` and the system temp folder, unless one of the two
unusual variables in section 3 is set.

## 2. Every path of `setup --system` that can make a folder

`setup --system` passes no `--settings`, so `settingsfile.apply_roots` (`tools/settingsfile.py:61-73`) gets no entries and sets
nothing: only variables already in the environment reach the roots. Ranked by likelihood on this machine.

| # | Where | Reads | What it makes | In `drishti-orig` here? |
|---|---|---|---|---|
| 1 | `tools/launch/launchers.py:302-320` (`machine`; called from `tools/launch/envsetup.py:267-269`). Roots at line 310, `mkdir` at 314-315 | `drishti.data.dir` of the **existing `dev.ini`** (plus the profile file). `home` is passed explicitly, so `find_home` (`tools/javacmd.py:199-216`) returns before it looks at the file's `drishti.home`: the "another install" refusal never runs. `DRISHTI_DATA_DIR` is removed from the environment for this lookup, and the file would win anyway | `<data>\tmp\server`, `<data>\dumps\server`, `<data>\logs`, `<data>\run`; and the `jvm-*.args` files naming them | **Yes, certain** with the stale `dev.ini`. Demo and samples profiles; the qa profile sets its own data folder under the checkout |
| 2 | `tools/launch/envsetup.py:201-206` (data layout, `[made] data ...`), README copies at `:52-61` | `--data-dir`, else `DRISHTI_DATA_DIR`, else `<home>\data` (`tools/javacmd.py:273-298`) | `run`, `logs`, `tmp`, `dumps`, `delta`, `dataload\inbox`, `inprocess`, `outbox`, `failed`, `ledger`, each `dataload` folder with a `README.md` | Only if `DRISHTI_DATA_DIR` is still set (an old `setx`). Setup then prints `[made] data (DRISHTI_DATA_DIR) ...drishti-orig\data` and lists the variable under "Environment variables seen" |
| 3 | `tools/launch/envsetup.py:225-228` | `DRISHTI_LIB_PATH`, else `<home>\lib\ext` | that one folder | Only if `DRISHTI_LIB_PATH` names it: `drishti-orig\lib\ext` |
| 4 | `tools/drishti.py:1143-1152` (`use_tmp_dir`, `mkdir` at 1150); `tools/javacmd.py:351-358` (`cli_tmp`, used by the `module fresh` check of setup) | `DRISHTI_TMP_DIR` | that folder, before any command runs | Only if `DRISHTI_TMP_DIR` names a folder under `drishti-orig` |
| 5 | `tools/launch/envsetup.py:244-246`, `tools/launch/pylibs.py:375-393` | `%USERPROFILE%` | `drishti-envs\dev.ini` (new only when missing; `drishti.python` is added to an existing one) | No: `drishti-envs`, never `drishti-orig` |
| 6 | `tools/javacmd.py:199-216` | `DRISHTI_HOME` | nothing | No: a `DRISHTI_HOME` naming another folder stops setup with an error (exit 2) before anything is made. Setup finishing proves `DRISHTI_HOME` is unset or names this checkout |

Not a cause: `pylibs.install` (pip into the company Python; temp files in the system temp folder), the `bootstrap` re-run after
PyYAML is installed (same command, same roots), `modulesbuild.packages` / `module_layout.leftovers` (read only).

**How to tell 1 from 2 by looking inside:** four empty folders under `data` = cause 1 (the old `dev.ini`). `delta` and
`dataload\...\README.md` as well = cause 2 (a `DRISHTI_DATA_DIR` variable); both can be true at once.

## 3. Every path of the Maven build (`platform-clean-install`)

| Product | What was checked | Result |
|---|---|---|
| Platform `pom.xml` | four `exec` steps: `mvnw.cmd -B -Djavax.net.ssl.trustStoreType=Windows-ROOT -Dpython3=py -Dspotless.check.skip=true -DskipTests=true clean install`, each in its product folder | names only `${project.basedir}/<product>`; no runtime root |
| Tools (`java/pom.xml`) | compiler, jar, resources, enforcer, install, surefire (`:100-113`: `java.io.tmpdir` = `target/tmp`, `tools.repo.root`) | inside the checkout; surefire does not run |
| Rachana (`java/pom.xml:48`, `:88-92`) | `tmp-create`: `py tools/gate/tmp_leaks.py create ${maven.multiModuleProjectDirectory}/target/tmp`; the TeaVM, shade and dependency steps; the guards' `exec` steps (skipped with tests) | `drishti-rachana\java\target\tmp`: inside the checkout |
| Lake (`java/pom.xml:118-130`) | surefire roots `lake.data.dir` = `target/test-data`, `lake.home` = the module | inside the checkout; surefire does not run |
| Drishti root `pom.xml:96-98` | `drishti.home.lib`, `drishti.home.modules`, `drishti.kernel.jar` = `${maven.multiModuleProjectDirectory}/lib`, `/modules`, `/kernel/...` | `drishti\lib`, `drishti\modules`: inside the checkout |
| Drishti `packaging/drishti-app/pom.xml:80-129` | `exec:java` of `KernelLibraries` and `ModuleCli module install-jar ... --into ${drishti.home.lib}` | no environment variable, no settings file, no `Roots` / `DataDir` call |
| Drishti `packaging/dist/pom.xml:84-303` | `ModuleCli module pack`, `first-party`, `install ... --into ${drishti.home.modules}`; `check-genomics` (`module verify`) is skipped with tests | the same; `verify` alone uses a temp folder (below) |
| Drishti `drishti-sdk/drishti-module-parent/pom.xml:35-41, 117-160` | `drishti.home` = `${env.DRISHTI_HOME}`; a `file://${drishti.home}/sdk/repo` repository; `drishti-validate` / `-assemble` / `-conformance` run `${drishti.java} -jar ${drishti.home}/lib/drishti-server-<v>-exec.jar` | the executions are only in `pluginManagement`. `modules/genomics/java/genomics/pom.xml:42-49` turns them on only for `-Ddrishti.home=...` on the command line (an environment variable does not activate the profile). A `file://` repository is only read. With a stale `DRISHTI_HOME` this pom **reads** `drishti-orig\sdk\repo` and makes nothing |
| Drishti surefire (`pom.xml:300-331`, `kernel/drishti-server/pom.xml:124-151`) | `drishti.data.dir` = `target/test-data`, `drishti.home` = the module, `drishti.modules.home` = `none` | inside the checkout; not run |
| flatten plugin (`pom.xml:288-298`) | `drishti-flattened-pom.xml` | in each module's `target` |
| Resources filtering, Spring Boot repackage, clean plugin | no path outside `${project.build.directory}` | nothing |

Two variables could send a build outside the checkout. Neither is set by anything Drishti documents; check them once and rule them out:

| Variable | Where it is read | Effect |
|---|---|---|
| `DRISHTI_TMP_DIR` | `kernel/drishti-common/.../TempFiles.java:54-60` (`libraryBase`), reached from `ModuleCli.java:382-384` | only `module verify` / `inspect` make a folder there; the build with tests skipped does not call them |
| `MAVEN_BASEDIR` | Maven's own `mvn.cmd`: it becomes `maven.multiModuleProjectDirectory`, the anchor of every path above | all four builds would write `target\tmp`, `lib`, `modules` under that folder; the Drishti build would also fail (no `.mvn\maven.config` there) |

On the owner's machine: `set DRISHTI_` and `set MAVEN_` in a new terminal show them all.

## 4. Why it "keeps" happening

The old guide told Windows users to `setx DRISHTI_HOME` and `setx DRISHTI_DATA_DIR` (still in
`docs/DEPLOYMENT_AND_QA_GUIDE.md:202-208`); `setx` is permanent. And `dev.ini` in `%USERPROFILE%\drishti-envs` is outside every
checkout and is never overwritten. Both outlive the clone they were written for. After setup, these also write into the old folder
(not part of this run, but the same root cause):

- an IntelliJ server launcher: `-Ddrishti.settings.file=dev.ini` gives the server `drishti.data.dir` and `drishti.modules.home` of
  `drishti-orig` (Java order: `-D`, then the settings file, then the environment); the i7e start only warns;
- `drishti.py start server` with `DRISHTI_DATA_DIR` set (`tools/launch/serverstart.py:207-262`).

The precedence is also not the same everywhere: Java is `-D` > file > environment; Python `launch_roots` is file > environment for
the data folder but environment > file for the modules folder; `apply_roots` is environment > file for all three.

## 5. What the owner should do now

1. `ren "%USERPROFILE%\drishti-envs\dev.ini" dev-old.ini` (the command setup prints).
2. In a new terminal: `set DRISHTI_` and `set MAVEN_`. Remove every variable that names `drishti-orig`
   (`reg delete HKCU\Environment /v DRISHTI_DATA_DIR /f`, the same for the others), then open a new terminal and restart IntelliJ.
3. Delete `drishti-orig`, run `py tools\drishti.py setup --system` again: it writes a new `dev.ini` and new `jvm-*.args` for this checkout.

## 6. The structural fix

One rule: **a root that names a folder outside this checkout is never used silently, and setup never makes a folder it did not report.**

**Drishti**

1. **One resolver, one order.** `javacmd.launch_roots` is the only place Python resolves roots, with the Java order for every root:
   explicit option > settings file > environment > `<home>/...`. `launchers.machine` and setup's data layout use it with the same
   inputs (today `machine` drops `DRISHTI_DATA_DIR` and skips the home check; `apply_roots` uses another order).
2. **Foreign-root check in the resolver.** Each root comes back with its origin and a flag "outside this checkout". The check covers
   `drishti.home`, `drishti.data.dir`, `drishti.modules.home`, `drishti.lib.path`, `DRISHTI_TMP_DIR`, from the file and from the
   environment alike.
3. **Setup refuses, then offers.** With a foreign root and no flag, setup makes nothing for that root: no data layout, no
   `lib\ext`, no `jvm-*.args`, exit 1, and one line per root: its value, where it came from (file and line, or the variable), and the
   two ways out:
   - `setup --adopt` (the owner's OK to rewrite): the old `dev.ini` is kept as `dev-<date>.ini`, a new one is written for this
     checkout, and the variables to delete are listed with their `reg delete` lines (setup never edits the user's environment);
   - `setup --allow-outside-roots`: for a real site whose data folder is on another disk. The choice is recorded in `dev.ini`
     (`drishti.roots.outside=allowed`) so later runs are quiet.
   A data folder outside the checkout stays a supported set-up; only the unannounced one goes.
4. **"Environment variables seen" gets teeth.** Each `DRISHTI_*` path variable is shown with `WARNING: names a folder outside this
   checkout` when it does, and counts as a problem (exit 1) unless allowed as above.
5. **`machine` makes no folders.** The JVM argument files only name folders; the server already makes its own temp folder at
   start (`JvmTempDir.ensureExists`), and `start server` makes the rest. With point 3 the files are not written at all for a foreign root.
6. **Start refuses too.** The server and `drishti.py start` treat a settings file whose `drishti.home` is another folder as an
   error (today a WARNING), unless `drishti.roots.outside=allowed`. The IntelliJ launchers already pass `-Ddrishti.home=$PROJECT_DIR$`,
   so the server can compare the two.
7. **Docs.** Remove the `setx DRISHTI_HOME` / `setx DRISHTI_DATA_DIR` lines from `DEPLOYMENT_AND_QA_GUIDE.md`: the settings file is
   the one place for roots; `DRISHTI_JAVA_HOME` may stay.
8. **Guard tests.** (i) setup with a `dev.ini` naming another folder, and again with each `DRISHTI_*` path variable naming one:
   the folder does not exist afterwards and the exit code is 1; (ii) the same with `--adopt` and with `--allow-outside-roots`;
   (iii) a test that no function under `tools/launch` calls `mkdir` on a path that did not come through the resolver.

**The build (all four products and the platform pom)**

9. **The build never reads a runtime root.** `drishti-module-parent` takes `drishti.home` only from `-Ddrishti.home` (drop the
   `${env.DRISHTI_HOME}` default; the SDK build already passes the `-D`), so a kernel build never looks at another install.
10. **A guard in each product's guards module**: no `pom.xml` contains `${env.DRISHTI_`, `${user.home}` or a runtime root, and every
    path a pom writes starts with `${project.basedir}`, `${project.build.directory}` or `${maven.multiModuleProjectDirectory}`.
11. **The platform pom** adds an enforcer rule before the first product: fail when `MAVEN_BASEDIR` is set, and print a warning line
    for every `DRISHTI_*` variable that names a folder outside the platform folder (the build ignores them; the line tells the user
    setup will not).
