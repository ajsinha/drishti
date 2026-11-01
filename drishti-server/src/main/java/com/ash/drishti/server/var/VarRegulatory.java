/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.var;


/**
 * Regulatory VaR's parts (VAR_GUIDE.md sections 18.10, 18.11 and 18.14): the desk P&amp;L reader, the backtest, the period statistics, the report
 * generator with its sign-off, and a demo server's synthetic history; all read the official records the VaR engine keeps.
 */
record VarRegulatory(VarDeskPnl pnl, VarBacktesting backtesting, VarPeriodStats stats, VarReports reports, VarReportSignoff signoff,
        VarSyntheticHistory synthetic) {
}
