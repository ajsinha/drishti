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
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The computed kind {@code exposure-result} (EXPOSURE_GUIDE.md section 19.1, as built in 2.9.0): a scope's exposure answered by the server's
 * exposure engine through the counterparty-risk pack's reactive connector {@code exposure-engine} ({@code plugin: reactive},
 * {@code engine.type: java}), so exposure opens as an ordinary kind: {@code EXP NSET:NS-ALDERSHOT-FRA <GO>},
 * {@code /v/exposure-result/CPTY:CP-ALDERSHOT}, the entities API, the CLI, search and the AI tools, with the explorer's entitlements
 * (computed as the viewer: their counterparties, their masks, their audit entry). The id is the scope text; parameters after {@code ~}
 * ({@code CPTY:CP-ALDERSHOT~q=0.99}): {@code q}, {@code collateral}, {@code run}.
 *
 * <p>The record: {@code SCOPE}, {@code CANONICAL}, {@code SCOPE_KIND}, {@code LABEL}, {@code BUSINESS_DATE}, {@code RUN_ID},
 * {@code PATHS}, {@code CALIBRATION}, {@code CONFIDENCE}, {@code CE}, {@code EPE}, {@code EEPE}, {@code PEAK_EE}, {@code PEAK_PFE},
 * {@code PEAK_PFE_DATE}, {@code PEAK_PFE_99}, {@code EAD_IMM}, {@code MPOR_DAYS}, {@code COLLATERAL_MODEL}, {@code PROFILE} (per primary
 * date: {@code TENOR}, {@code DATE}, {@code T}, {@code EE}, {@code ENE}, {@code EEE}, {@code PFE}, {@code PFE_95}, {@code PFE_99},
 * {@code EE_UNCOLL}), {@code BY_NETTING_SET}, {@code FLAGS}, {@code PROVENANCE}, {@code STATUS} ({@code ok}, or {@code no-paths}: a
 * netting set or counterparty the reference data knows that the date's run does not cover, with {@code NOTE}); the tables
 * {@code profile} and {@code nettingSets} for panels. Never stored.
 */
public final class ExposureResultEngine implements ComputeEngine {

    public static final String KIND = "exposure-result";
    private static final AtomicReference<ExposureViews> INSTALLED = new AtomicReference<>();

    static void install(ExposureViews views) {
        INSTALLED.set(views);
    }

    @Override
    public EngineManifest manifest() {
        return new EngineManifest("exposure-engine", ExposureViews.ENGINE_VERSION, List.of(KIND));
    }

    @Override
    public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) throws Exception {
        ExposureViews views = INSTALLED.get();
        if (views == null) {
            throw new EngineException("the exposure engine is not running on this server (drishti.ccr.exposure.enabled)");
        }
        String asked = req.id().trim();
        String scope = ExposureService.scopeText(asked);             // EXP-ALDERSHOT-FRA (retired) is NSET:NS-ALDERSHOT-FRA
        if (scope.isEmpty()) {
            throw new EngineException("give a scope as the id: NSET:<netting set>, CPTY:<counterparty>, GROUP:<group>, LE:<legal entity>, TRD:<trade>");
        }
        Principal p = new Principal(req.viewer().user(), req.viewer().roles());
        LocalDate date = req.asOf() == null ? null : req.asOf().businessDate();
        ExposureService svc = views.service();
        Object q = req.param("q", null);
        Object c = req.param("collateral", null);
        Object run = req.param("run", null);
        Object detail = req.param("detail", null);
        Object whatIfSets = req.param("sets", null);
        Map<String, Object> head;
        Map<String, Object> whatIf = new LinkedHashMap<>();
        try {
            ExposureService.Ask ask = ExposureService.Ask.of(svc.props(), q == null ? null : String.valueOf(q), c == null ? null : String.valueOf(c),
                    run == null ? null : String.valueOf(run), null);
            head = views.scope(p, scope, date, ask);
            String dt = detail == null ? "" : String.valueOf(detail).trim().toLowerCase(java.util.Locale.ROOT);
            if (dt.equals("whatif") || dt.equals("set")) {                // the Python what-if engine's inputs (ExposureWhatIfInputs)
                ExposureService.Scoped s = svc.scoped(p, scope, date, ask);
                if (dt.equals("whatif")) {
                    ExposureWhatIfInputs.base(s, whatIfSets == null ? "" : String.valueOf(whatIfSets), svc.props().rankRule(), whatIf);
                } else {
                    ExposureWhatIfInputs.set(s, whatIf);
                }
            } else if (!dt.isEmpty() && !dt.equals("none")) {
                throw new EngineException("detail is none, whatif or set, not " + detail);
            }
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.ENTITY_NOT_FOUND && e.getMessage().contains("no exposure paths for")) {
                return noPaths(scope, e.getMessage().replaceFirst("^DRS-\\d{4} ", ""), date);
            }
            throw new EngineException(e.getMessage());
        }
        progress.fraction(1, 1);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("STATUS", "ok");
        row.put("SCOPE", head.get("scope"));
        row.put("CANONICAL", head.get("canonical"));
        row.put("SCOPE_KIND", head.get("kind"));
        row.put("LABEL", head.get("label"));
        for (String[] f : new String[][] {{"BUSINESS_DATE", "businessDate"}, {"RUN_ID", "runId"}, {"PATHS", "paths"}, {"CALIBRATION", "calibration"},
            {"CONFIDENCE", "confidence"}, {"CE", "ce"}, {"EPE", "epe"}, {"EEPE", "eepe"}, {"PEAK_EE", "peakEe"}, {"PEAK_PFE", "peakPfe"},
            {"PEAK_PFE_DATE", "peakPfeDate"}, {"PEAK_PFE_TENOR", "peakPfeTenor"}, {"PEAK_PFE_99", "peakPfe99"}, {"EAD_IMM", "eadImm"},
            {"HORIZON_YEARS", "horizonYears"}, {"MPOR_DAYS", "mporDays"}, {"COLLATERAL_MODEL", "collateralModel"}}) {
            row.put(f[0], head.get(f[1]));
        }
        List<Map<String, Object>> profile = new ArrayList<>();
        if (head.get("profile") instanceof List<?> l) {
            for (Object o : l) {
                Map<?, ?> r = (Map<?, ?>) o;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("TENOR", r.get("tenor"));
                x.put("DATE", r.get("date"));
                x.put("T", r.get("t"));
                x.put("EE", r.get("ee"));
                x.put("ENE", r.get("ene"));
                x.put("EEE", r.get("eee"));
                x.put("PFE", r.get("pfe"));
                x.put("PFE_95", r.get("pfe95"));
                x.put("PFE_99", r.get("pfe99"));
                x.put("EE_UNCOLL", r.get("eeUncoll"));
                profile.add(x);
            }
        }
        row.put("PROFILE", profile);
        List<Map<String, Object>> sets = new ArrayList<>();
        if (head.get("byNettingSet") instanceof List<?> l) {
            for (Object o : l) {
                Map<?, ?> r = (Map<?, ?>) o;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("NETTING_SET", r.get("nettingSet"));
                x.put("COUNTERPARTY", r.get("counterparty"));
                x.put("LABEL", r.get("label"));
                x.put("PARTIAL", r.get("partial"));
                x.put("EPE", r.get("epe"));
                x.put("PEAK_EE", r.get("peakEe"));
                x.put("PEAK_PFE", r.get("peakPfe"));
                x.put("PFE_CONTRIBUTION", r.get("pfeContribution"));
                sets.add(x);
            }
        }
        row.put("BY_NETTING_SET", sets);
        row.put("FLAGS", head.get("flags"));
        if (!scope.equals(asked) && asked.toUpperCase(java.util.Locale.ROOT).startsWith("EXP-")) {
            row.put("NOTE", asked + " was a stored exposure profile, retired in 2.9.0: this is " + scope + ", computed from the paths");
        }
        row.put("PROVENANCE", upper(head.get("provenance")));
        row.putAll(whatIf);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("engine", "exposure-engine");
        meta.put("tier", head.get("provenance") instanceof Map<?, ?> m ? m.get("tier") : null);
        return new EngineResult(List.of(row), Map.of("profile", profile, "nettingSets", sets), meta);
    }

    /** camelCase keys to UPPER_SNAKE, nested maps and lists of maps too ({@code wholeSets} is {@code WHOLE_SETS}). */
    static Object upper(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k).replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT), upper(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(upper(x)));
            return out;
        }
        return v;
    }

    private static EngineResult noPaths(String scope, String why, LocalDate date) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("STATUS", "no-paths");
        row.put("SCOPE", scope);
        row.put("CANONICAL", scope);
        row.put("BUSINESS_DATE", date == null ? null : date.toString());
        row.put("NOTE", why);
        row.put("PROFILE", List.of());
        row.put("BY_NETTING_SET", List.of());
        row.put("FLAGS", List.of("no-paths"));
        return new EngineResult(List.of(row), Map.of("profile", List.of(), "nettingSets", List.of()), Map.of("engine", "exposure-engine"));
    }
}
