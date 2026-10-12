#!/usr/bin/env python3
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

"""How many connections must a database accept? (tutorial 17, OPERATIONS.md section 16: "Sizing the database connection pools").

    max_connections >= (the pool sizes of every connector that points at the database + the identity pool + loaders + your own clients) x server instances

Edit the numbers to your installation and run it."""
pool_sizes = {"market-store": 20, "reference-store": 20, "credit-store": 20, "liquidity-store": 20, "collateral-store": 20, "risk-store": 20}
identity, loader, instances = 20, 20, 2
per_instance = sum(pool_sizes.values()) + identity + loader
total = per_instance * instances
print(f"connectors {sum(pool_sizes.values())} + identity {identity} + loader {loader} = {per_instance} per server instance; x {instances} instances = {total}")
print("PostgreSQL's default max_connections is 100:", "NOT enough" if total > 100 else "enough")
