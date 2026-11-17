# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""The tools' tests: the Java resolver's answers the tools read (tools/resolved.py) are cached in the test process's own folder
(``DRISHTI_RESOLVED_CACHE``), inherited by the tools a test starts, never under the repository's data/tmp/resolved, which the drill
checks is left unchanged (docs/architecture/TEST_HARNESS.md, section 3.15)."""
import atexit
import os
import shutil
import tempfile

if not os.environ.get("DRISHTI_RESOLVED_CACHE"):
    _RESOLVED = tempfile.mkdtemp(prefix="drishti-resolved-")
    os.environ["DRISHTI_RESOLVED_CACHE"] = _RESOLVED
    atexit.register(shutil.rmtree, _RESOLVED, True)
