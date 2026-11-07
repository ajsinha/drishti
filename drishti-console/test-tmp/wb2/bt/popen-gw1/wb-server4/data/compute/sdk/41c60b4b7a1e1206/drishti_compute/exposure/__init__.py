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

"""Drishti's Python exposure engine (docs/guides/EXPOSURE_GUIDE.md section 19.5): the exposure measures by the Java engine's rules
(``com.ash.drishti.compute.ccr.ExposureMath``: collateral called at the look-back point, E and N, EE, PFE, EEE, EPE, EEPE, the peaks), and
the what-if engine of the reactive connector ``exposure-whatif``: the exact ``paths`` method, candidates' paths on the date's run added to
their netting sets path by path, collateral and exposure computed again. Java keeps the additive aggregation (a scope's exposure summed
over the hot file); Python receives the scope's matrix and the few netting sets the candidates join. Standard library only: it runs on
every worker."""
