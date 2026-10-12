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

"""BaseMCPTool: the base class of a tool, with the abstract methods and signatures of ``sajha/tools/base_mcp_tool.py`` in sajhamcpserver (the Java twin is
``com.ash.drishti.ai.llm.BaseMcpTool``, same methods in camelCase). A tool is configured by a dict (``name``, ``description``, ``version``, ``enabled`` ...) and
implements three abstract methods: ``execute``, ``get_input_schema`` and ``get_output_schema``. ``execute_with_tracking`` validates the arguments, runs the tool and
keeps the metrics. Tools are what an MCP server serves (``to_mcp_format``); the console's MCP client calls remote ones (``core/ai/mcp.py``)."""
from __future__ import annotations

import datetime
from abc import ABC, abstractmethod
from typing import Any, Dict, Optional


class ToolArgumentError(ValueError):
    def __init__(self, tool_name: str, detail: str):
        super().__init__(f"tool '{tool_name}': {detail}")
        self.tool_name, self.detail = tool_name, detail


class BaseMCPTool(ABC):
    def __init__(self, config: Optional[Dict] = None):
        self.config: Dict[str, Any] = dict(config or {})
        self._enabled = self.config.get("enabled", True) is not False
        self._calls = 0
        self._errors = 0
        self._last_called: Optional[str] = None
        self._last_error = ""

    @property
    def name(self) -> str:
        return str(self.config.get("name") or type(self).__name__)

    @property
    def description(self) -> str:
        return str(self.config.get("description", ""))

    @property
    def served_description(self) -> str:
        """The description a client is served (a subclass may add usage notes)."""
        return self.description

    @property
    def version(self) -> str:
        return str(self.config.get("version", "1.0.0"))

    @property
    def enabled(self) -> bool:
        return self._enabled

    @property
    def input_schema(self) -> Dict:
        return self.get_input_schema()

    @property
    def output_schema(self) -> Dict:
        return self.get_output_schema()

    def enable(self):
        self._enabled = True

    def disable(self):
        self._enabled = False

    @abstractmethod
    def execute(self, arguments: Dict[str, Any]) -> Any:
        """Run the tool with the given arguments."""

    @abstractmethod
    def get_input_schema(self) -> Dict:
        """The JSON Schema of the arguments."""

    @abstractmethod
    def get_output_schema(self) -> Dict:
        """The JSON Schema of the result."""

    def validate_arguments(self, arguments: Dict[str, Any]) -> bool:
        """Required names present; unknown names refused when the schema says ``additionalProperties: false``."""
        schema = self.get_input_schema()
        args = arguments or {}
        for r in schema.get("required", []):
            if r not in args:
                raise ToolArgumentError(self.name, f"missing required argument '{r}'")
        if schema.get("additionalProperties") is False:
            for k in args:
                if k not in schema.get("properties", {}):
                    raise ToolArgumentError(self.name, f"unknown argument '{k}'")
        return True

    def execute_with_tracking(self, arguments: Dict[str, Any]) -> Any:
        self._calls += 1
        self._last_called = datetime.datetime.now(datetime.timezone.utc).isoformat()
        try:
            self.validate_arguments(arguments)
            return self.execute(arguments)
        except Exception as e:
            self._errors += 1
            self._last_error = type(e).__name__
            raise

    def get_metrics(self) -> Dict:
        return {"name": self.name, "calls": self._calls, "errors": self._errors, "lastCalled": self._last_called, "lastError": self._last_error}

    def to_mcp_format(self) -> Dict:
        return {"name": self.name, "description": self.served_description, "inputSchema": self.get_input_schema(), "outputSchema": self.get_output_schema()}

    def load_from_config(self, config_path: str):
        """Reads the tool's settings from a YAML or JSON file into ``config``."""
        import json
        from pathlib import Path

        import yaml
        text = Path(config_path).read_text(encoding="utf-8")
        data = json.loads(text) if config_path.endswith(".json") else yaml.safe_load(text)
        self.config.update(data or {})
        self._enabled = self.config.get("enabled", True) is not False
