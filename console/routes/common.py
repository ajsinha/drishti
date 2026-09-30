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

"""Shared helpers for page routes."""
from __future__ import annotations

from typing import Any

from fastapi import Request


def render(request: Request, template: str, status_code: int = 200, **context: Any):
    templates = request.app.state.templates
    return templates.TemplateResponse(request, template, context, status_code=status_code)


def user_of(request: Request) -> str:
    """The acting user. Until sign-in lands (Wave 10) this is the configured desk user."""
    return request.app.state.settings.get("ui.user", "ash")
