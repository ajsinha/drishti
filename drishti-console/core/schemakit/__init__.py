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

"""schemakit: JSON Schema (and sample documents) in, a Drishti pack plan out.

    plan.make_plan(schemas, samples, overrides)  -> Plan   kinds, keys, links, labels, match columns, Sutras, warnings
    synth.documents(kind_plan, plan, n)          -> docs   synthetic samples for the Sutra drafter
    decorate.*                                   -> text   Sutra / about.yaml / pack.yaml text from a plan
    build.prepare / assemble / make_bundle       -> files  the documents to draft from, a complete pack folder, its bundle

Pure Python (PyYAML for YAML input and output), no Java: the console and `tools/drishti.py` both use it, and only the drafting of a
Sutra from sample documents (the `drafter` callable) needs the server's Java code. The rules are in docs/guides/SCHEMA_TO_PACK.md.
"""
