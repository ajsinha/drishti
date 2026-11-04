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

import com.ash.drishti.api.compute.ComputeEngine;
import com.ash.drishti.api.compute.EngineException;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineManifest;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The computed kind {@code fva-result} (docs/guides/FVA_GUIDE.md section 6.1, COUNTERPARTY_ENGINES.md decision 9): a scope's FVA answered by the
 * server's FVA engine through the counterparty-risk pack's reactive connector {@code fva-engine} ({@code engine.type: java}), so FVA reads
 * as an ordinary kind for a Calc snippet, BI, the entities API and the CCR cube ({@code FVAR NSET:NS-ALDERSHOT-FRA <GO>}), computed as the
 * viewer. The id is the scope; the choices are parameters after {@code ~} ({@code CPTY:CP-ALDERSHOT~fba=off}): {@code eePoint},
 * {@code fba}, {@code survival}, {@code spread}. The record is the one FVA answer (/api/v1/fva/scope) in the kinds' upper-case names: {@code SCOPE},
 * {@code SCOPE_KIND}, {@code LABEL}, {@code BUSINESS_DATE}, {@code RUN_ID}, {@code CALIBRATION}, {@code FCA}, {@code FBA}, {@code FVA},
 * {@code EPE_LIFE}, {@code LIFE}, {@code FUNDING_SPREAD}, {@code EE_POINT}, {@code FUNDING_SURVIVAL}, {@code FBA_ON}, {@code NETTING_SETS},
 * {@code BY_NETTING_SET}, {@code TERMS}, {@code CVA_STATUS}, {@code FLAGS}, {@code PROVENANCE}, {@code STATUS}; the tables {@code terms}
 * and {@code nettingSets} for panels. Never stored.
 */
public final class FvaResultEngine implements ComputeEngine {

    public static final String KIND = "fva-result";
    static final String VERSION = "1.0";
    private static final AtomicReference<FvaService> INSTALLED = new AtomicReference<>();

    static void install(FvaService svc) {
        INSTALLED.set(svc);
    }

    @Override
    public EngineManifest manifest() {
        return new EngineManifest("fva-engine", VERSION, List.of(KIND));
    }

    private static String param(EngineRequest req, String name) {
        Object v = req.param(name, null);
        return v == null ? null : String.valueOf(v);
    }

    @Override
    public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) throws Exception {
        FvaService svc = INSTALLED.get();
        if (svc == null || !svc.running()) {
            throw new EngineException("the FVA engine is not running on this server (config/engines/fva.yaml, Admin → Engines)");
        }
        String scope = ExposureService.scopeText(req.id().trim());
        if (scope.isEmpty()) {
            throw new EngineException("give a scope as the id: NSET:<netting set>, CPTY:<counterparty>, GROUP:<group>, LE:<legal entity>, BANK");
        }
        Principal p = new Principal(req.viewer().user(), req.viewer().roles());
        LocalDate date = req.asOf() == null ? null : req.asOf().businessDate();
        FvaService.Answer a;
        try {
            a = svc.answer(p, scope, date, FvaController.choices(param(req, "eePoint"), param(req, "fba"), param(req, "survival"), param(req, "spread")),
                    null, null);
        } catch (DrishtiException e) {
            throw new EngineException(e.getMessage());
        }
        progress.fraction(1, 1);
        Map<String, Object> row = row(a);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> terms = (List<Map<String, Object>>) row.get("TERMS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sets = (List<Map<String, Object>>) row.get("BY_NETTING_SET");
        return new EngineResult(List.of(row), Map.of("terms", terms, "nettingSets", sets), Map.of("engine", "fva-engine"));
    }

    /** The record of an answer: the one FVA answer's fields in upper case (one source, FvaViews). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> row(FvaService.Answer a) {
        Map<String, Object> v = FvaViews.answer(a);
        Map<String, Object> f = (Map<String, Object>) v.get("figures");
        Map<String, Object> st = (Map<String, Object>) v.get("settings");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("STATUS", "ok");
        row.put("SCOPE", v.get("scope"));
        row.put("SCOPE_KIND", v.get("kind"));
        row.put("LABEL", v.get("label"));
        row.put("BUSINESS_DATE", v.get("businessDate"));
        row.put("RUN_ID", v.get("runId"));
        row.put("CALIBRATION", v.get("calibration"));
        row.put("CURRENCY", "USD");
        row.put("FCA", f.get("fca"));
        row.put("FBA", f.get("fba"));
        row.put("FVA", f.get("fva"));
        row.put("EPE_LIFE", f.get("epeLife"));
        row.put("LIFE", f.get("life"));
        row.put("FUNDING_SPREAD", a.borrow().id());
        row.put("EE_POINT", st.get("eePoint"));
        row.put("FUNDING_SURVIVAL", st.get("fundingSurvival"));
        row.put("FBA_ON", st.get("fba"));
        row.put("NETTING_SETS", v.get("nettingSetCount"));
        row.put("CVA_STATUS", "planned");
        row.put("FLAGS", v.get("flags"));
        row.put("BY_NETTING_SET", upper((List<Map<String, Object>>) v.get("nettingSets"), List.of("fcaTerms", "fbaTerms")));
        row.put("TERMS", upper((List<Map<String, Object>>) v.get("terms"), List.of()));
        Map<String, Object> pv = new LinkedHashMap<>();
        ((Map<String, Object>) v.get("provenance")).forEach((k, x) -> pv.put(name(k), x instanceof Map<?, ?> || x instanceof List<?> ? String.valueOf(x) : x));
        row.put("PROVENANCE", pv);
        return row;
    }

    private static List<Map<String, Object>> upper(List<Map<String, Object>> rows, List<String> skip) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> x = new LinkedHashMap<>();
            r.forEach((k, val) -> {
                if (!skip.contains(k)) {
                    x.put(name(k), val);
                }
            });
            out.add(x);
        }
        return out;
    }

    /** {@code eeStar} → {@code EE_STAR}, {@code fca} → {@code FCA}. */
    static String name(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
