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

# title: Hours open by status
# description: The ticket's own age and status as a one-row DataFrame, to start a queue analysis from.
# kinds: ticket

import pandas as pd
pd.DataFrame([{"status": view.doc["status"], "hours": view.doc["ageHours"]}])
