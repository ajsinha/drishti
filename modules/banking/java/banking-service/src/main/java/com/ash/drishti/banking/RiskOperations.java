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
package com.ash.drishti.banking;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.banking.exposure.ExposurePreDealController;
import com.ash.drishti.banking.var.VarController;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.services.Operation;
import com.ash.drishti.kernel.api.services.Person;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * What the banking module serves to other modules (the trading landings): the VaR and exposure engines' figures, each the answer of the
 * REST path it names, for the person asked as, on the person's date box. The controllers answer; nothing is worked out here.
 */
@Component
public class RiskOperations {

    /** A scope of the engines, and the currency (null: the engine's). */
    public record Scope(String scope, String ccy) {
    }

    /** The contributions of a scope, by {@code position} or {@code attribute:<field>}. */
    public record Contributions(String scope, String by) {
    }

    /** The official record of the person's date. */
    public record Official(String ccy) {
    }

    /** A request body as the REST path takes it. */
    public record Body(Map<String, Object> body) {
    }

    /** The engine's figures. */
    public record Figures(Map<String, Object> figures) {
    }

    private final VarController var;
    private final ExposurePreDealController preDeal;

    public RiskOperations(VarController var, ExposurePreDealController preDeal) {
        this.var = var;
        this.preDeal = preDeal;
    }

    private static Principal principal(Person p) {
        return new Principal(p.user(), p.roles());
    }

    private static AsOf asOf(Person p) {
        return p.asOf();
    }

    @Operation(value = "var.scope", rest = "GET /api/v1/risk/var/scope")
    public Figures varScope(Person p, Scope r) {
        return new Figures(var.headline(r.scope(), r.ccy(), null, null, null, null, null, null, null, asOf(p), null, false, null, false, principal(p)));
    }

    @Operation(value = "var.contributions", rest = "GET /api/v1/risk/var/scope/contributions")
    @SuppressWarnings("unchecked")
    public Figures varContributions(Person p, Contributions r) {
        ResponseEntity<Object> answer = var.contributions(r.scope(), r.by(), null, null, null, null, null, null, null, asOf(p), null, false, principal(p), null);
        return new Figures((Map<String, Object>) answer.getBody());
    }

    @Operation(value = "var.official", rest = "GET /api/v1/risk/var/official")
    public Figures varOfficial(Person p, Official r) {
        return new Figures(var.officialRecord(r.ccy(), asOf(p), principal(p)));
    }

    @Operation(value = "var.history", rest = "GET /api/v1/risk/var/scope/history")
    public Figures varHistory(Person p, Scope r) {
        return new Figures(var.history(r.scope(), r.ccy(), null, null, null, null, null, asOf(p), principal(p)));
    }

    @Operation(value = "var.whatif", rest = "POST /api/v1/risk/var/what-if")
    public Figures varWhatIf(Person p, Body r) {
        return new Figures(var.whatIf(r.body(), null, asOf(p), null, false, principal(p)));
    }

    @Operation(value = "exposure.predeal", rest = "POST /api/v1/risk/exposure/predeal")
    public Figures exposurePreDeal(Person p, Body r) {
        return new Figures(preDeal.check(r.body(), asOf(p), principal(p)));
    }
}
