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
package com.ash.drishti.server.explain;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.rachana.UnderConstruction;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explain any number (API_GUIDE.md, "Explain"): reads only, as the caller.
 * <pre>
 *   GET  /api/v1/explain/kinds                                   the kinds that can be explained here, their measures, the work not built
 *   GET  /api/v1/explain?kind=&amp;measure=&amp;part=&amp;...            one number: the head, formula, inputs and lineage, why it moved, workbook,
 *                                                               checks (part=preview: the hover card only; part=why: that tab only)
 *   POST /api/v1/explain  {kind, measure, part, ...}             the same with a body (a pivot cell sends its view)
 * </pre>
 * Every other parameter is the explainer's (scope, date, from, to, view ...).
 */
@RestController
@RequestMapping("/api/v1/explain")
public class NumberExplainController {

    private final NumberExplainService service;

    NumberExplainController(NumberExplainService service) {
        this.service = service;
    }

    @GetMapping("/kinds")
    public Map<String, Object> kinds() {
        return service.kinds();
    }

    @GetMapping
    public Map<String, Object> explain(@RequestParam Map<String, String> q, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return service.explain(p, request(new LinkedHashMap<>(q), asOf));
    }

    @PostMapping
    public Map<String, Object> explainBody(@RequestBody Map<String, Object> body, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return service.explain(p, request(new LinkedHashMap<>(body == null ? Map.of() : body), asOf));
    }

    /** The request; the business date the person reads at (the as-of header or {@code asOf}) is the number's date unless {@code date} names one. */
    private static ExplainRequest request(Map<String, Object> args, AsOf asOf) {
        args.remove("asOf");
        if (!args.containsKey("date") && asOf != null && asOf.businessDate() != null) {
            args.put("date", asOf.businessDate().toString());
        }
        return new ExplainRequest(str(args.remove("kind")), str(args.remove("measure")), str(args.remove("part")), args);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** The service, with every explainer the engines registered. */
    @Configuration
    static class Wiring {

        @Bean
        NumberExplainService numberExplainService(List<Explainer> explainers, ObjectProvider<EngineRegistry> engines,
                ObjectProvider<UnderConstruction> registry) {
            return new NumberExplainService(explainers, engines, registry);
        }
    }
}
