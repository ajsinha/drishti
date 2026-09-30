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

"""samplegen: realistic sample documents for domain packs, standard library only.

Packs use it to write sample data that looks like what a real system would hold: valid identifiers with check
digits (LEI, ISIN, CUSIP, UTI, UPI), business-day calendars and schedules, day-count fractions, curves with
discount factors and forwards, cashflows with fixings and present values, and the lifecycle, regulatory,
settlement and audit blocks every booked trade carries. Everything is deterministic for a given seed.
"""
