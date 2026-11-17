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
package com.ash.drishti.trading;

import com.ash.drishti.kernel.api.module.Asker;
import java.util.Map;

/** The trading landings' questions: the typed kernel services ({@link Asker}) and the banking module's six operations ({@link RiskFigures}). */
interface TradingAsker extends Asker {

    /** The banking module's operations, as the person. */
    RiskFigures risk();

    default Map<String, Object> varScope(String scope) {
        return risk().varScope(person(), new RiskFigures.Scope(scope, null)).figures();
    }

    default Map<String, Object> varContributions(String scope, String by) {
        return risk().varContributions(person(), new RiskFigures.Contributions(scope, by)).figures();
    }

    /** The official record of the day (the person's date box is the day). */
    default Map<String, Object> varOfficial() {
        return risk().varOfficial(person(), new RiskFigures.Official(null)).figures();
    }

    default Map<String, Object> varHistory(String scope) {
        return risk().varHistory(person(), new RiskFigures.Scope(scope, null)).figures();
    }

    default Map<String, Object> varWhatIf(Map<String, Object> body) {
        return risk().varWhatIf(person(), new RiskFigures.Body(body)).figures();
    }

    default Map<String, Object> exposurePreDeal(Map<String, Object> body) {
        return risk().exposurePreDeal(person(), new RiskFigures.Body(body)).figures();
    }

    @Override
    TradingAsker as(com.ash.drishti.kernel.api.services.Person p);

    @Override
    default TradingAsker onDate(java.time.LocalDate day) {
        return as(person().onDate(day));
    }
}
