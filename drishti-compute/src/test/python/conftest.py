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

"""The SDK's tests import it from its sources (``src/main/python``) and the test engines from here."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.normpath(os.path.join(HERE, "..", "..", "main", "python"))
for p in (SDK, HERE):
    if p not in sys.path:
        sys.path.insert(0, p)
