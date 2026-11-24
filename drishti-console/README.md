<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# drishti-console

The Drishti web console: the Python (FastAPI) tier in front of one Drishti server or a cluster. It draws what the server decides
(screens, figures, access) and holds the shell: header, lenses, themes, keys, Home, Help, search, the BI and pivot screens and Admin. It
also hosts the console part of each module under `/m/<id>/`. Every risk number is computed on the server; the console only displays it.

## Run it

The server must be running (and, for the demo, the demo lake built). All commands run from the repository root; Windows uses
`py -3.13 tools\drishti.py`, Linux and macOS `python3.13 tools/drishti.py`.

| How | Command |
|---|---|
| Command line, console alone | `python3.13 tools/drishti.py start console --demo` (`--server URL` names the server, `--port` the port) |
| Command line, server and console together | `python3.13 tools/drishti.py start all --demo` |
| PyCharm (open `drishti/drishti-console`) | the launcher **Drishti: run console** (group PyCharm-console-run); also Finance, Risk and QA variants |
| Bare script | run `run_drishti_web.py` with no arguments: the same as **Drishti: run console** (`~/drishti-envs/dev.ini`, the demo profile, development secrets) |

The console listens on <http://127.0.0.1:17480> (the QA console on 17580). The demo profile signs in as `drishti-dev-admin`; it is not
for production. `python3.13 tools/drishti.py launchers --list` shows every launcher and the command it runs.

## Its Python

The Python is the one you prepared: Python 3.13, a dedicated one or a virtual environment you made yourself. Drishti never makes an
environment and never installs a package. You install the libraries with two pip commands, from this folder (Windows: `py -3.13 -m pip`
and `..\config\compute\locks\python.lock`), offline from the unzipped wheel pack:

```bash
python3.13 -m pip install --no-index --find-links ~/drishti-wheels -r requirements.txt
python3.13 -m pip install --no-index --find-links ~/drishti-wheels -r ../config/compute/locks/python.lock
python3.13 ../tools/drishti.py setup        # checks them, name by name, and installs nothing
```

`requirements.txt` holds the runtime libraries; `requirements-test.txt` adds pytest, pytest-xdist and Playwright (`python3.13 -m playwright install chromium` once).

## What is here

| Folder | What it holds |
|---|---|
| `core/`, `routes/`, `web/` | the console's code, its routes, its templates and static files |
| `config/` | the console's own configuration (Help map, pages) |
| `plugins/`, `run-configs/`, `tests/` | the console's plugins, its PyCharm launchers (PyCharm-console-setup, -data, -run, -check), its tests |

## Tests

From the repository root, with the Python you prepared and the test extras installed:

```bash
python3.13 -m pytest drishti-console/tests/test_docs_rot.py drishti-console/tests/test_help_links.py drishti-console/tests/test_tools_guide.py
python3.13 -m pytest drishti-console/tests -m "not static" -n 8     # the wider run (browser tests need Chromium)
```

The tools' own tests are separate (`python3.13 -m pytest tools`); a test that says the server jar is unbuilt needs `./mvnw -B clean package -DskipTests`
first. More: [TEST_HARNESS.md](../docs/architecture/TEST_HARNESS.md), [IDE_GUIDE.md](../docs/guides/IDE_GUIDE.md).
