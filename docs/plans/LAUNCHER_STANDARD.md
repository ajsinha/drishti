<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti platform. Proprietary: see LICENSE. -->
# The launcher standard

Owner direction 2026-11-21: every component of the Drishti platform has, where it makes sense, IDE launchers to BUILD (Maven) and to RUN
it, organised the SAME way everywhere. This is the one convention. Applied on 2026-11-21 to the four product repositories (Rachana, Elements,
Lake, Tools). Drishti itself follows in the G4 docs pass: the appendix lists exactly what changes.

## 1. Where the launchers live

| Rule | Value |
|---|---|
| Folder | ONE `run-configs/` at the repository root; no other `run-configs/` folder anywhere (no `java/run-configs`, no `python/run-configs`) |
| Visible names | `run-configs/` and `<product>.code-workspace` are visible, tracked files; no `.idea/`, no `.vscode/` |
| IntelliJ / PyCharm project | the repository root; for Java, link `java/pom.xml` in the Maven tool window; every launcher sets its own working directory (`$PROJECT_DIR$/java`, `$PROJECT_DIR$/js`, ...) |
| VS Code | `drishti-<product>.code-workspace` at the root (File, Open Workspace from File) |
| Platform | Windows-safe: Java, Python and Maven only; no `.sh`, `.cmd`, `.ps1`, `.bat` in a launcher; JDK 21; JavaScript runs through Maven (`exec-maven-plugin`, `js/pom.xml` or the root `pom.xml`), so IntelliJ Community on Windows runs it with no Node plugin |
| Shared | the files are in git; the header of every file says what it does and when to use it; copy the configuration (Ctrl+D) to change it |

## 2. Names

`<Product>: <verb> [<what>]`, with the product capitalised (`Rachana`, `Elements`, `Lake`, `Tools`, `Drishti`), one of the verbs

| Verb | Meaning |
|---|---|
| `build` | compile and package, no tests (`build all`, `build JS bundles`, `build dist`) |
| `test` | run tests (`test`, `test Python`, `test JS (Node)`, `test TCK (this repository)`) |
| `check` | a gate or a read-only verification (`check guards`, `check bundles`, `check server (demo)`) |
| `install` | `install release (tag only)`: the jars into `~/.m2`, only on a release tag's checkout |
| `run` | start a program (`run server (demo)`, `run Studio (static-sales)`, `run CLI (lib)`) |
| `lint` | a linter (`lint examples`) |
| `bundle` | produce a distributable bundle (`bundle JS runtime`) |
| `maintain` | change a checked-in artefact on purpose (`maintain API surface (rewrite)`, `maintain release plan (dry run)`) |
| `bench` | a benchmark |

The `<what>` is lower case except proper names; a variant goes in brackets: `run server (demo)`, `run server (QA, port 18580)`. No colon in `<what>`.
The file is named after the launcher WITHOUT the colon (`Lake build all.run.xml`; Windows forbids the colon in a file name). `folderName` is the
product (the IntelliJ run-configuration folder). Debugging is the IDE's Debug button on the same launcher (no `debug` launcher); in VS Code the
`launch` entries of the same name are what F5 debugs.

## 3. The standard set per component type

| Component | Required | Usual additions |
|---|---|---|
| Java library | `build all`, `test`, `install release (tag only)` | `check guards`, `maintain API surface (rewrite)`, special tests (`test xlsx with LibreOffice`) |
| Server or app (Java) | the library set + `run <name> (demo)` | `check <name> (demo)`, `run <name> (QA, port N)`, `run CLI (...)`; the run launcher is an Application launcher, so Debug works |
| Python part | `test Python` | `lint ...`, `check ...`, `run ... (Flask)`; Python launchers set `PYTHONPATH` to the package's `src` |
| JavaScript part | `test JS (Node)`, `bundle JS runtime` | `run Studio (...)`, `run dev server (...)`, `run gallery server`; all through Maven exec, never bash |
| Repository with no Java (Elements) | `build all` (the bundles), `test` (every suite) | `check ...`, `test static`, `test Node` |

`build all` and `test` exist in every repository. An install launcher exists only where a repository publishes jars. A tag-only launcher says so
in its name and in its header.

## 4. VS Code mirroring

`drishti-<product>.code-workspace` (JSON with `//` header lines) carries `tasks` and `launch`:

- every Maven and every Python launcher is a task with the same label (`./mvnw ...` from `java/`, `mvnw.cmd` on Windows; JavaScript through `java/mvnw -f js/pom.xml exec:exec@<id>`, or the program itself where the repository has no `mvnw`);
- every Application launcher is a `launch` entry (`type: java`) and every Python launcher also one (`type: debugpy`), with the same name;
- the union of task labels and launch names equals the set of launcher names: none extra, none missing;
- `java.configuration.runtimes` names `JavaSE-21` for a repository with Java; the recommended extensions are listed.

## 5. The validation check

One check per repository, in its build or test run, with the same rules:

| Repository | Check | Run by |
|---|---|---|
| Rachana | `tools/launchers/check_launchers.py`, wrapper `python/tests/test_launchers.py` | `Rachana: test Python` |
| Elements | `tools/launchers/check_launchers.py`, wrapper `tests/test_launchers.py` (marker `static`) | `Elements: test static` |
| Lake | `tools/launchers/check_launchers.py`, wrapper `python/tests/test_launchers.py` | `Lake: test Python` |
| Tools | `LaunchersTest` in `tools-guards` | `Tools: test`, `Tools: check guards` |

The rules: every `run-configs/*.run.xml` parses; the name matches the pattern; the file name is the name without the colon; `folderName` is the
product; no `run-configs` folder elsewhere; a Maven launcher's working directory and pom exist and an `exec:exec@id` execution exists in that pom;
an Application launcher's main class exists in the sources; a Python launcher's script and working directory exist and name no shell script; a
`$PROJECT_DIR$/...` path in the parameters exists; `build all` and `test` (and, with `java/pom.xml`, `install release (tag only)`) exist; the
workspace parses, mirrors the names both ways and names JDK 21; the README has a "Build and run in the IDE" section naming `run-configs/`.

## 6. Applied on 2026-11-21

| Repository (branch) | Launchers | What changed |
|---|---|---|
| Rachana (develop) | 19 | the two folders `java/run-configs` and `python/run-configs` merged into one; the duplicate Python "JS runtime bundle" dropped (one way: Maven exec); `Python tests`, the Python paths and `PYTHONPATH` fixed for the root project |
| Elements (develop) | 10 | renamed; `Elements: test` (all suites) added; the Node tests and gallery server stay Maven exec |
| Lake (main) | 9 | `java/run-configs` and `tools/run-configs` merged; `test Python` and `maintain release plan (dry run)` added; TCK and check named `test`/`check` |
| Tools (develop) | 6 | renamed; `check guards` added; the launcher check is a guard test |

## Appendix: what Drishti must change (G4 docs pass)

Drishti today has two launcher folders, with names that do not follow the pattern, and no VS Code workspace.

1. **One folder.** Move `drishti-console/run-configs/*` into `run-configs/` at the repository root; delete `drishti-console/run-configs/`. Fix the paths:
   the console launchers' `$PROJECT_DIR$` becomes the repository root, so `$PROJECT_DIR$` -> `$PROJECT_DIR$/drishti-console` in `WORKING_DIRECTORY`/`SCRIPT_NAME`, and every
   `$PROJECT_DIR$/..` is `$PROJECT_DIR$`. Open the repository root as the one project in IntelliJ and PyCharm (IDE_GUIDE sections 1.2, 2.3, 4, recipe D and the
   "not one folder in both" advice change with it).
2. **Rename** (file = name without the colon; `folderName` stays `Drishti`):

| Today | Standard name |
|---|---|
| Drishti build all | `Drishti: build all` |
| Drishti build modules | `Drishti: build modules` |
| (none) | `Drishti: test` (`clean verify`) and `Drishti: install release (tag only)` if jars are published |
| Drishti server (built-in samples) | `Drishti: run server (built-in samples)` |
| Drishti server (demo lakes) | `Drishti: run server (demo)` |
| Drishti server (QA, port 18580) | `Drishti: run server (QA, port 18580)` |
| Drishti console | `Drishti: run console` |
| Drishti console (Finance) / (Risk) | `Drishti: run console (Finance)` / `(Risk)` |
| Drishti console (QA, port 17580) | `Drishti: run console (QA, port 17580)` |
| Drishti doctor / doctor (QA) | `Drishti: check environment` / `Drishti: check environment (QA)` |
| Build demo lakes | `Drishti: build demo lakes` |
| Load a file into the lake | `Drishti: run loader (file into the lake)` |

3. **Add**: `Drishti: test Python` (the console and tools pytest), `Drishti: test` (Maven), and `Drishti: lint ...` where a linter exists; the loader launchers
   already match `run`.
4. **VS Code**: add `drishti.code-workspace` (or `drishti-drishti`: the product is Drishti, file `drishti.code-workspace`) mirroring every name, as in section 4; JDK 21.
5. **Check**: add `tools/launchers/check_launchers.py` (copy from Lake, product Drishti; its `product()` reads the workspace file name, so give it the one
   rule `drishti.code-workspace` -> `Drishti`) and its pytest wrapper; list it in the guards run.
6. **Docs**: IDE_GUIDE sections 1.3, 3, 4 (every launcher) and 6 name the launchers by the new names; the root README "Build and run in the IDE" section names
   `run-configs/`; CHANGELOG entry.
7. **Drishti-stack and bin/**: unchanged (they start the stack, not an IDE launcher).
