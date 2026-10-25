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

"""Drishti's compute worker SDK (docs/architecture/PYTHON_COMPUTE.md section 8): the engine contract, the pure-Python Arrow codec and the
worker loop the server's supervisor starts. Pure Python, standard library only: it runs in any interpreter of 3.10 or later, on Linux and
on Windows, with no compiled package."""

__version__ = "0.1.0"

from .arrow import Column, Table  # noqa: E402
from .engine import ComputeError, Engine, Inputs, Progress, Request, Result, register_cache  # noqa: E402

__all__ = ["Column", "ComputeError", "Engine", "Inputs", "Progress", "Request", "Result", "Table", "__version__", "register_cache"]
