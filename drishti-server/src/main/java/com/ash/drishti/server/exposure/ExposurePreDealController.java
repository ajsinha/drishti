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
package com.ash.drishti.server.exposure;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pre-deal service's API (EXPOSURE_GUIDE.md section 18.9), in the exposure engine (the counterparty-risk domain):
 *
 * <pre>
 *   POST /api/v1/exposure/predeal           {command: "PREDEAL CP-ALDERSHOT LIKE 1000001 250M"} or the same as fields: the decision
 *                                           (approve, refer, decline), its reasons, the method and the template, the limit and its
 *                                           buckets before and after, the exposure before, with and incremental; audited (ccr-predeal)
 *   GET  /api/v1/exposure/predeal/grammar   the command's grammar and its words
 * </pre>
 *
 * Computed as the caller (their entitlements apply; a template is a trade they see), on the request's business date ({@code asOf}).
 */
@RestController
@RequestMapping("/api/v1/exposure/predeal")
public class ExposurePreDealController {

    private final ExposurePreDeal preDeal;

    ExposurePreDealController(ExposureService svc) {
        this.preDeal = new ExposurePreDeal(svc, new ExposureWhatIf(svc));
    }

    @PostMapping
    public Map<String, Object> check(@RequestBody Map<String, Object> body, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return preDeal.check(p, body, asOf == null ? null : asOf.businessDate());
    }

    @GetMapping("/grammar")
    public Map<String, Object> grammar() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("word", ExposurePreDealCommand.WORD);
        m.put("usage", ExposurePreDealCommand.usage());
        m.put("words", List.of("LIKE <trade>", "<notional>", "REVERSE", "IN <netting set>", "<asset class>", "<maturity>", "MTM <amount>"));
        m.put("assetClasses", ExposurePreDealCommand.CLASSES);
        m.put("examples", List.of("PREDEAL CP-ALDERSHOT LIKE 1000001 250M", "PREDEAL CP-ALDERSHOT LIKE 1000001 100M REVERSE IN NS-ALDERSHOT-LDN",
                "PREDEAL CP-ALDERSHOT RATES 250M 5Y"));
        m.put("decisions", Map.of("approve", "exact, within the limit and every bucket", "decline", "exact, over the limit or a bucket",
                "refer", "anything less than exact data: a proxy, no limit, unseen netting sets, a stale run, the netting set not certain"));
        return m;
    }
}
