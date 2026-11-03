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
package com.ash.drishti.server.saccr;

import com.ash.drishti.api.compute.ComputeEngine;
import com.ash.drishti.api.compute.EngineException;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineManifest;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.compute.saccr.Saccr;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The computed kind {@code saccr-result} (docs/contracts/SACCR.md section 9.1): a scope's SA-CCR answered by the server's SA-CCR engine
 * through the counterparty-risk pack's reactive connector {@code saccr-engine} ({@code engine.type: java}), so SA-CCR reads as an ordinary
 * kind for a Calc snippet, BI and the entities API ({@code drishti.get_async("saccr-result", "CPTY:CP-HALCYON")}), computed as the viewer.
 * The id is the scope ({@code NSET:<id>}, {@code CPTY:<id>}, {@code LE:<id>}, {@code BANK}). The record: {@code SCOPE}, {@code SCOPE_KIND},
 * {@code BUSINESS_DATE}, {@code NETTING_SETS}, {@code TRADES}, {@code EXCLUDED_TRADES}, {@code UNCLASSIFIED_TRADES}, {@code RC},
 * {@code PFE}, {@code EAD}, {@code RWA}, {@code RISK_WEIGHT}; for one netting set also {@code V}, {@code C}, {@code NICA}, {@code ADD_ON},
 * {@code MULTIPLIER}, {@code EAD_UNMARGINED}, {@code CAPPED}, {@code ALPHA}, {@code MPOR_DAYS}, {@code MARGINED}, {@code ADD_ONS} and
 * {@code HEDGING_SETS}; {@code BY_NETTING_SET}, {@code PROVENANCE}, {@code STATUS}. Never stored.
 */
public final class SaccrResultEngine implements ComputeEngine {

    public static final String KIND = "saccr-result";
    private static final AtomicReference<SaccrService> INSTALLED = new AtomicReference<>();

    static void install(SaccrService svc) {
        INSTALLED.set(svc);
    }

    @Override
    public EngineManifest manifest() {
        return new EngineManifest("saccr-engine", Saccr.ENGINE, List.of(KIND));
    }

    @Override
    public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) throws Exception {
        SaccrService svc = INSTALLED.get();
        if (svc == null) {
            throw new EngineException("the SA-CCR engine is not running on this server (config/engines/sa-ccr.yaml, Admin → Engines)");
        }
        String scope = req.id().trim();
        Principal p = new Principal(req.viewer().user(), req.viewer().roles());
        LocalDate date = req.asOf() == null ? null : req.asOf().businessDate();
        SaccrService.Run run;
        try {
            run = svc.run(p, scope, date, Saccr.Choices.REGULATORY, false);
        } catch (DrishtiException e) {
            throw new EngineException(e.getMessage());
        }
        progress.fraction(1, 1);
        return new EngineResult(List.of(row(run, svc)), Map.of("nettingSets", bySet(run)), Map.of("engine", "saccr-engine"));
    }

    static Map<String, Object> row(SaccrService.Run run, SaccrService svc) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("STATUS", "ok");
        row.put("SCOPE", run.scope());
        row.put("SCOPE_KIND", run.kind());
        row.put("BUSINESS_DATE", run.date() == null ? null : run.date().toString());
        double rc = 0;
        double pfe = 0;
        int trades = 0;
        int excluded = 0;
        int unclassified = 0;
        for (SaccrService.SetRun s : run.sets()) {
            if (s.result() != null) {
                rc += s.result().rc();
                pfe += s.result().pfe();
                trades += s.result().terms().size();
            }
            excluded += s.excluded();
            unclassified += s.unclassified().size();
        }
        row.put("NETTING_SETS", run.sets().size());
        row.put("TRADES", trades);
        row.put("EXCLUDED_TRADES", excluded);
        row.put("UNCLASSIFIED_TRADES", unclassified);
        row.put("RC", rc);
        row.put("PFE", pfe);
        row.put("EAD", run.ead());
        row.put("RISK_WEIGHT", svc.props().riskWeight());
        row.put("RWA", run.ead() * svc.props().riskWeight());
        if (run.sets().size() == 1 && run.sets().get(0).result() != null) {
            Saccr.Result r = run.sets().get(0).result();
            Saccr.Basis b = r.binding();
            row.put("V", r.v());
            row.put("C", r.set().c());
            row.put("NICA", r.set().nica());
            row.put("ADD_ON", b.addOnAgg());
            row.put("MULTIPLIER", b.multiplier());
            row.put("EAD_UNMARGINED", r.unmargined().ead());
            row.put("CAPPED", r.capped());
            row.put("ALPHA", r.params().get("alpha"));
            row.put("MPOR_DAYS", r.mpor());
            row.put("MARGINED", r.set().margined());
            List<Map<String, Object>> addOns = new ArrayList<>();
            b.addOns().forEach((ac, a) -> {
                if (a > 0) {
                    addOns.add(Map.of("ASSET_CLASS", ac, "ADD_ON", a));
                }
            });
            row.put("ADD_ONS", addOns);
            boolean marg = r.margined() != null && !r.capped();
            List<Map<String, Object>> hs = new ArrayList<>();
            for (Saccr.HedgingSet h : r.hedgingSets()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("ASSET_CLASS", h.assetClass());
                x.put("HEDGING_SET", h.hedgingSet());
                x.put("EFFECTIVE_NOTIONAL", Double.isFinite(marg ? h.enMargined() : h.enUnmargined()) ? (marg ? h.enMargined() : h.enUnmargined()) : null);
                x.put("ADD_ON", marg ? h.addOnMargined() : h.addOnUnmargined());
                hs.add(x);
            }
            row.put("HEDGING_SETS", hs);
        }
        row.put("BY_NETTING_SET", bySet(run));
        Map<String, Object> pv = new LinkedHashMap<>();
        run.provenance().forEach((k, v) -> pv.put(k.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_"), v instanceof Map<?, ?> || v instanceof List<?>
                ? String.valueOf(v) : v));
        row.put("PROVENANCE", pv);
        return row;
    }

    static List<Map<String, Object>> bySet(SaccrService.Run run) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SaccrService.SetRun s : run.sets()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("NETTING_SET", s.id());
            x.put("COUNTERPARTY", s.counterparty());
            x.put("STATUS", s.status());
            x.put("RC", s.result() == null ? 0.0 : s.result().rc());
            x.put("PFE", s.result() == null ? 0.0 : s.result().pfe());
            x.put("MULTIPLIER", s.result() == null ? null : s.result().multiplier());
            x.put("EAD", s.ead());
            x.put("MARGINED", s.result() != null && s.result().set().margined());
            out.add(x);
        }
        return out;
    }
}
