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

"""The line cap of a code file: ONE number for every guard that counts a source file's lines (Python, Java, JavaScript, CSS, HTML).

The Python guards import :data:`CODE_LINE_CAP`; the Java guard (testing/drishti-it SourceFileSizeTest) reads this file's assignment.
A documentation page has its own cap (drishti-console/tests/test_docs_gates.py PAGE_CAP), which this is not.
"""

CODE_LINE_CAP = 2500
