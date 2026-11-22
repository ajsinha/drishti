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
# Visual Studio Code: build and run Drishti

For developers and testers who work in VS Code. Every component of the Drishti platform (Drishti, Rachana, Lake, Tools, Elements) carries a
**workspace file** at its root, `<product>.code-workspace` (for Drishti, `drishti.code-workspace`), with the same build, test, check and run
actions as the IntelliJ and PyCharm launchers ([IDE_GUIDE.md](IDE_GUIDE.md)): one **task** per Maven or Python launcher, one **launch** entry
(F5, debuggable) per Java application and per Python program, under the same names. The convention is the platform's launcher standard:
`<Product>: <verb> [<what>]`, with the verbs `build`, `test`, `check`, `install`, `run`, `lint`, `bundle`, `maintain`, `bench`. Nothing here
needs a hidden folder, a shell script or an `.exe`: Java, Python and Maven only, so the same files work on Linux, macOS and Windows.

**In one minute.** Install the extensions (section 1), open `drishti.code-workspace` (File, Open Workspace from File), set the JDK path
(section 2), then **Terminal, Run Task, `Drishti: build all`**. Once, make `~/drishti-envs/dev.ini` (`python tools/drishti.py setup`, [QUICKSTART_DEVELOPERS.md](../QUICKSTART_DEVELOPERS.md#13-the-consoles-environment)),
then run the tasks **Drishti: build demo lakes**, **Drishti: run server (demo)** and **Drishti: run console**; open `http://localhost:17480`.

## 1. What you need

| You need | How |
|---|---|
| **JDK 21** | Linux `/usr/lib/jvm/java-21-openjdk-amd64`, macOS `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`, Windows `C:\jdk-21` |
| **Python 3.13** and the console's environment | `uv venv --python 3.13 drishti-console/.venv` and the requirements ([QUICKSTART_DEVELOPERS.md section 1.3](../QUICKSTART_DEVELOPERS.md#13-the-consoles-environment)) |
| **VS Code** with the extensions the workspace recommends | Extension Pack for Java (`vscjava.vscode-java-pack`), Python (`ms-python.python`, `ms-python.debugpy`, `ms-python.vscode-pylance`), YAML (`redhat.vscode-yaml`); VS Code offers them when the workspace opens |
| The platform's other repositories, built and installed | tools, Rachana and Lake from their tags into `~/.m2`, in that order ([QUICKSTART_DEVELOPERS.md section 2](../QUICKSTART_DEVELOPERS.md#2-build)); their versions: the index repository's [COMPATIBILITY.md](https://github.com/drishti-platform/drishti-platform/blob/main/COMPATIBILITY.md) |

## 2. Open a repository

1. **File, Open Workspace from File**, choose `drishti.code-workspace` at the root of the clone (a visible file).
2. The workspace's `settings` hold `java.configuration.runtimes` (name `JavaSE-21`) and `java.jdt.ls.java.home`: change the path to your JDK 21
   (Windows `C:\\jdk-21` in the JSON). A copy of the workspace file for yourself is fine; the shipped one is overwritten by a pull.
3. Choose the console's Python: **Python: Select Interpreter**, `drishti-console/.venv` (Windows `drishti-console\.venv\Scripts\python.exe`).
4. Wait for the Java extension to import the Maven project (the first import downloads libraries). Behind a company network that re-signs
   HTTPS the import and the build stop with `PKIX path building failed`: put `-Djavax.net.ssl.trustStoreType=Windows-ROOT` in the environment
   variable `MAVEN_OPTS` (`setx MAVEN_OPTS "-Djavax.net.ssl.trustStoreType=Windows-ROOT"`, then a new terminal) and in the Java extension's
   `java.import.maven.vmargs`; the full explanation is [IDE_GUIDE.md section 1.4](IDE_GUIDE.md#14-maven-behind-a-company-network-pkix-path-building-failed).

## 3. Build, test and run: the tasks

**Terminal, Run Task** (or Ctrl+Shift+B for the default build) lists every task of the workspace under the standard names. The same names exist
in IntelliJ and PyCharm, so a team mixes the three without translating.

| Task | What it does | Use it when |
|---|---|---|
| `Drishti: build all` | Maven `clean package -DskipTests -pl :drishti-app,:drishti-modules -am`: the server jar into `lib/` and every module package into `modules/` | after a clone and after every pull |
| `Drishti: build modules` | only the module packages, stamped for the jar in `lib/` | you changed only module code |
| `Drishti: test` | the Maven tests | before a hand-over |
| `Drishti: test Python` | the console's and the tools' pytest | before a hand-over |
| `Drishti: build demo lakes` | `drishti.py lake demo`, the demo lakes through Drishti Lake's loader | after a build, with the server stopped |
| `Drishti: run server (demo)` | the server on 18480 on the demo lakes | the normal run |
| `Drishti: run console` | the console on 17480 | with the server |
| `Drishti: check environment` | `drishti.py doctor` | something does not start or answer |

(The workspace file is the authority for the complete list; the names above are those of the standard.) Stop the server before a build: the
build replaces the jar and the packages it runs, and Windows keeps them locked.

## 4. Run and debug

**Run and Debug** (Ctrl+Shift+D) lists the launch entries, named like the tasks: pick **Drishti: run server (demo)** and press **F5** to debug
the server (breakpoints in the Java extension, the jar's classes from `lib/`), **Drishti: run console** to debug the console (the Python
debugger, `debugpy`). To start both without a debugger, run their tasks. Both read `~/drishti-envs/dev.ini` and the profile `demo.ini`
([IDE_GUIDE.md section 2.1](IDE_GUIDE.md#21-the-settings-file-drishti-envsdevini)); sign in as `drishti-dev-admin` / `drishti-dev-admin123`
(a QA machine's development secrets only).

## 5. Several repositories at once

To work across the platform (a change in Drishti that needs one in Lake), use one **multi-root workspace**: **File, Open Workspace from File**
on any one product's workspace, then **File, Add Folder to Workspace** for each of the others under `~/IdeaProjects/drishti-platform/`
(`drishti`, `drishti-rachana`, `drishti-elements`, `drishti-lake`, `drishti-tools`, `drishti-stack`), then **File, Save Workspace As** into
`drishti-platform/` itself (`drishti-platform.code-workspace`, beside the clones, outside every repository, so it is never committed). The
tasks and launches of the workspaces you did not open are not merged into it; run them from their own workspace (a second VS Code window
costs nothing), and use the multi-root one for searching, reading and editing across repositories. A change in a product repository reaches Drishti only
through a tag installed into `~/.m2` ([QUICKSTART_DEVELOPERS.md section 2](../QUICKSTART_DEVELOPERS.md#2-build)); to try an unreleased change,
build the product with `-Dmaven.repo.local=<scratch folder>` as that product's `BUILD_AND_IDE.md` says, and never install a branch.

## 6. Python and JavaScript parts

The Python parts (`drishti-console`, `tools`) run from the console's environment; the workspace's Python tasks set `PYTHONPATH` where a package
needs it. Elements (JavaScript) and Rachana's JavaScript build and test through Maven (`exec-maven-plugin`), so VS Code needs no Node
extension for the tasks; Node 20 or newer is needed only for the Node unit tests.

## 7. When it does not work

| You see | Cause and fix |
|---|---|
| the Java extension shows `JavaSE-21` unresolved | the JDK path in the workspace's settings is wrong for this machine: edit it, then **Java: Clean Java Language Server Workspace** |
| `PKIX path building failed` | section 2, step 4 |
| a task says `lib/drishti-server-exec.jar` is missing | run `Drishti: build all` first |
| the console cannot import a package | the wrong interpreter: section 2, step 3 |
| the server refuses the modules at start (`stamped for another jar`) | a new jar with old modules: run `Drishti: build all`, never the jar alone |

A fresh start, step by step, and every check: [IDE_GUIDE.md section 8](IDE_GUIDE.md#8-a-fresh-start-step-by-step-and-how-to-check-each-step).
