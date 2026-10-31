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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.server.bi.engine.ArrowAnswers;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scenario matrix's API (2.9.7, VAR_GUIDE.md section 3.9): {@code GET /api/v1/var/matrix?id=<trade|position|scope>&days=20}, the
 * scenarios (rows, by historical day) of one trade, position or VaR node across its last N business dates (columns), with each date's
 * VaR rank, ES tail, base PV and source; {@code by=trade&scenario=<day>} a position's trades under one scenario day instead. N is capped by
 * {@code drishti.var.smat.max-days} (60). {@code Accept: application/vnd.apache.arrow.stream} answers the rows as Arrow (509 rows by up
 * to 60 dates: the payload the browser reads as columns), JSON otherwise. {@code confidence}, {@code es-confidence}, {@code window} and
 * {@code horizon} are the parameters the VaR page's pickers will pass (only the 1-day horizon is computed today).
 */
@RestController
@RequestMapping("/api/v1/var")
public class VarMatrixController {

    private final VarMatrix matrix;

    VarMatrixController(VarService svc, VarViews views, Entitlements entitlements, @Value("${drishti.var.smat.max-days:60}") int maxDays) {
        this.matrix = new VarMatrix(svc, views, new VarMatrixLake(svc.loader(), entitlements), maxDays);
    }

    @GetMapping("/matrix")
    public ResponseEntity<Object> matrix(@RequestParam String id, @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String ccy, @RequestParam(required = false) String by,
            @RequestParam(required = false) String scenario, @RequestParam(required = false) Double confidence,
            @RequestParam(name = "es-confidence", required = false) Double esConfidence, @RequestParam(required = false) String window,
            @RequestParam(required = false) Integer horizon, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        VarMatrix.Asked a = new VarMatrix.Asked(id, asOf == null ? null : asOf.businessDate(), days, ccy, by, scenario, confidence, esConfidence,
                window, horizon);
        return ArrowAnswers.negotiate(accept, matrix.matrix(p, a), "records", "rows");
    }
}
