<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.


# Production standard

What every Drishti component must satisfy to be run in production. One section per component; each rule is checked by a test named beside it.

## 2. Console

The console (`drishti-console`) is a deployable of its own.

1. **It uses only the server's published API.** It reads no file or folder of the server: not the server's config, its lakes, the identity database, the
   modules folder or the data folder, and takes no shortcut around the API. It may read its own configuration, its templates and static files, the docs it
   renders for Help (`docs/` and the generated copies), and the few shared read-only files whitelisted with a reason in the guard test. Where the API lacks
   an answer the console needs, the server gets a small endpoint, documented in [API_REFERENCE.md](../guides/API_REFERENCE.md). Test:
   `drishti-console/tests/test_console_boundary_guard.py` (a new reach fails; the known ones are listed as pending with the lane that fixes each, and the list only shrinks).
2. **It runs alone.** Its own requirements (`drishti-console/requirements.txt`), its own settings (`drishti-console/config/application.yaml` and a site
   file) and its own start command (`python3.13 tools/drishti.py start console --server URL`); it runs from the binary package's `drishti-console/` folder
   on a host that holds nothing of the server.
3. **It scales out.** No per-person state in the process (session isolation): several instances, or several workers, behind a load balancer that
   probes `GET /readyz`. Sticky sessions are not needed with the relay across hosts, and are needed for live pages without it
   ([OPERATIONS_GUIDE.md](../guides/OPERATIONS_GUIDE.md#57-the-console-as-its-own-deployable)).
4. **It checks the server's API version.** The server reports it (`GET /api/v1/version`, open); the console declares the range it supports
   (`core/api_compat.py`) and refuses to start against a server outside it, with one line. Tests: `drishti-console/tests/test_api_version_handshake.py`,
   `RootControllerTest` (the server's answer).
