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

"""The functions a BI Python item defines (docs/guides/BI_GUIDE.md › Python in BI; docs/architecture/BI_DESIGN.md section 3.5).

A Python item is a source file and a manifest. Its manifest's ``interface`` says which function the runner calls (the ``entry``, defaulted
per interface in ``core.pyrunner.ENTRIES``), and that function receives pandas frames of the rows the person may see, already masked as they
see them. The item runs in a sandbox (``core/pyrunner_worker.py``): one short-lived process, no network, no files outside its scratch folder,
only the packages its manifest lists, under CPU, memory and row limits.

This module is the contract as types, for the API reference and for an author's editor: nothing imports it at run time, and an item need
not import it (the runner finds the function by name). Each protocol is one interface:

======================  ==================  =======================================================================================
interface               entry               what it returns
======================  ==================  =======================================================================================
``transform``           ``run``             a DataFrame: the table of a derived dataset (data prep)
``measure``             ``compute``         one number per group (or ``compute_groups``: every group in one call)
``column``              ``derive``          one value per row: a derived column of a dataset
``visual``              ``render``          an ECharts option or a Vega-Lite specification (a dict), SVG text or PNG bytes
======================  ==================  =======================================================================================

A minimal Transform::

    def run(trades, params):
        out = trades.groupby("desk", as_index=False)["mtm"].sum()
        return out[out["mtm"].abs() > float(params.get("min", 0))]
"""
from __future__ import annotations

from typing import TYPE_CHECKING, Any, Protocol, Union

if TYPE_CHECKING:  # the item's own sandbox has pandas; the console does not need it to load this module
    import pandas as pd

__all__ = ["Params", "VisualOutput", "Transform", "TransformClass", "Measure", "GroupedMeasure", "Column", "Visual", "INTERFACES"]

Params = dict
"""The item's parameters: what its manifest's ``params`` declare, with the values the dataset, visual or person gave (text, numbers)."""

VisualOutput = Union[dict, str, bytes]
"""What a visual returns: an ECharts option or a Vega-Lite specification (a dict, ``output: echarts`` or ``vega-lite`` in the manifest), SVG
text (no script, links or embedded content) or PNG bytes."""


class Transform(Protocol):
    """``interface: transform``: a Transform of a derived dataset (data prep).

    ``run`` receives one DataFrame per input of the Transform, in the manifest's order, and returns the dataset's table. Column names are
    letters, digits and underscore (not starting with a digit), each once; the rows are capped by the run's limit. ``params`` is passed only
    when the function's signature has a parameter of that name."""

    def __call__(self, *frames: pd.DataFrame, params: Params = ...) -> pd.DataFrame:
        """The table of the derived dataset, typed from its columns."""
        ...


class TransformClass(Protocol):
    """``interface: transform`` written as a class named ``Transform``: the runner makes one, sets ``self.params`` and calls its entry
    method (``run``) with the input frames."""

    params: Params

    def run(self, *frames: pd.DataFrame) -> pd.DataFrame:
        """As :class:`Transform`, with the parameters in ``self.params``."""
        ...


class Measure(Protocol):
    """``interface: measure``: a Python measure, called once per group of the visual or the dataset measure's ``by``.

    ``rows`` holds the declared input columns of one group's rows; the result is one number (NaN and infinity are refused)."""

    def __call__(self, rows: pd.DataFrame, params: Params) -> float:
        """The measure's value for one group."""
        ...


class GroupedMeasure(Protocol):
    """``interface: measure``, vectorised: a top-level ``compute_groups`` is called once for every group instead of ``compute`` per group.

    ``frame`` holds the group columns and the declared inputs of all rows; the result is a DataFrame with the group columns and one value
    column (named ``value``, else the last one)."""

    def __call__(self, frame: pd.DataFrame, by: list[str], params: Params) -> pd.DataFrame:
        """One row per group: the group columns and the value."""
        ...


class Column(Protocol):
    """``interface: column``: a derived column of a dataset (``derive: {name: {python: item, inputs: [...]}}``).

    ``rows`` holds the declared input columns of every row; the result is one value per row, vectorised (a Series, an array or a list of
    the frame's length), typed integer, decimal, boolean or string from its values."""

    def __call__(self, rows: pd.DataFrame, params: Params) -> Any:
        """One value per row, in the frame's order."""
        ...


class Visual(Protocol):
    """``interface: visual``: a Python visual. ``rows`` holds the visual's columns (the id column left out)."""

    def __call__(self, rows: pd.DataFrame, params: Params) -> VisualOutput:
        """The picture: see :data:`VisualOutput`."""
        ...


INTERFACES: dict[str, tuple[str, type]] = {
    "transform": ("run", Transform),
    "measure": ("compute", Measure),
    "column": ("derive", Column),
    "visual": ("render", Visual),
}
"""Each author-facing interface, its default entry function and its protocol (``tests/test_python_items.py`` holds this to the runner)."""
