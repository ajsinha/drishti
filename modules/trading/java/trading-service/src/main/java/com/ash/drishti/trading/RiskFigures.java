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

import com.ash.drishti.kernel.api.services.Operation;
import com.ash.drishti.kernel.api.services.Person;
import java.util.Map;

/**
 * What the trading landings ask of the banking module (its VaR and exposure engines): the consumer's view of six operations, asked
 * through {@code Kernel.operations().of("banking", RiskFigures.class)} as the person. Each request and answer is a record of this module's
 * own; the answer carries the engine's figures as the REST path of the operation answers them.
 */
public interface RiskFigures {

    /** The engine's scope ({@code BOOK:…}, {@code DESK:…}, a union) and the currency (null: the engine's). */
    record Scope(String scope, String ccy) {
    }

    /** The contributions of a scope, by {@code position} or {@code attribute:<field>}. */
    record Contributions(String scope, String by) {
    }

    /** The person's date box day's official record (the person's date is the day asked). */
    record Official(String ccy) {
    }

    /** A request body as the REST path takes it (a what-if's {@code {scope, items}}, a pre-deal check's fields). */
    record Body(Map<String, Object> body) {
    }

    /** The engine's figures. */
    record Figures(Map<String, Object> figures) {
    }

    @Operation("var.scope")
    Figures varScope(Person p, Scope request);

    @Operation("var.contributions")
    Figures varContributions(Person p, Contributions request);

    @Operation("var.official")
    Figures varOfficial(Person p, Official request);

    @Operation("var.history")
    Figures varHistory(Person p, Scope request);

    @Operation("var.whatif")
    Figures varWhatIf(Person p, Body request);

    @Operation("exposure.predeal")
    Figures exposurePreDeal(Person p, Body request);
}
