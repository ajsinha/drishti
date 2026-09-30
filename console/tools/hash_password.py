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

"""Prints a PBKDF2-SHA256 hash for a users.yaml entry.  Usage: python console/tools/hash_password.py"""
import getpass
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.auth import hash_password  # noqa: E402

if __name__ == "__main__":
    pw = getpass.getpass("Password: ")
    if pw != getpass.getpass("Again: "):
        sys.exit("passwords differ")
    print(hash_password(pw))
