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
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.compute.ccr.FvaMath;
import com.ash.drishti.engine.ViewAugmenter;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FVA's figures on the netting-set and counterparty pages (docs/guides/FVA_GUIDE.md section 5.1, the decided mock-up A): when the page is
 * opened the FVA engine computes the page's scope as the caller and the view reads it under {@code FVA_ENGINE}: {@code STATUS}
 * ({@code ok}, {@code off}, {@code unavailable} with the reason), {@code FCA}, {@code FBA}, {@code FVA}, {@code CVA_STATUS}
 * ({@code planned}), the funding curve, the flags, {@code TERMS} (per primary date: tenor, the FCA and FBA terms) and a {@code NOTE} naming
 * the command ({@code FVA NSET:<id> <GO>}). The same engine and answer as /api/v1/fva; the page computes nothing.
 */
final class FvaFigures implements ViewAugmenter {

    static final String FIELD = "FVA_ENGINE";
    private final FvaService svc;

    FvaFigures(FvaService svc) {
        this.svc = svc;
    }

    @Override
    public boolean augments(String kind) {
        ExposureProperties p = svc.exposure().props();
        return Set.of(p.kinds().nettingSet(), p.kinds().counterparty()).contains(kind);
    }

    @Override
    public EntityDocument augment(EntityDocument doc, AsOf asOf) {
        if (!(doc.data() instanceof DataNode.Obj o) || doc.deleted() || !svc.running() || !svc.exposure().running()) {
            return doc;
        }
        String scope = (doc.ref().kind().equals(svc.exposure().props().kinds().nettingSet()) ? "NSET:" : "CPTY:") + doc.ref().id();
        LocalDate date = asOf == null ? null : asOf.businessDate();
        if (date == null) {
            date = doc.provenance().businessDate();
        }
        Map<String, Object> figures;
        Optional<Principal> who = caller();
        if (who.isEmpty()) {
            figures = note("unavailable", scope, "FVA is computed for a signed-in viewer");
        } else {
            try {
                figures = figures(svc.answer(who.get(), scope, date, FvaService.Choices.none(), null, null), scope);
            } catch (RuntimeException e) {                         // the page opens whatever FVA says: the reason is shown, never an error
                figures = note("unavailable", scope, String.valueOf(e.getMessage()).replaceFirst("^DRS-\\d{4} ", ""));
            }
        }
        Map<String, DataNode> fields = new LinkedHashMap<>(o.fields());
        fields.put(FIELD, DataNode.of(figures));
        return new EntityDocument(doc.ref(), new DataNode.Obj(fields), doc.provenance(), doc.deleted());
    }

    private static Map<String, Object> note(String status, String scope, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("STATUS", status);
        m.put("SCOPE", scope);
        m.put("NOTE", note);
        m.put("CVA_STATUS", "planned");
        m.put("TERMS", List.of());
        return m;
    }

    private static Optional<Principal> caller() {
        Optional<Principal> p = NetCaller.current().map(NetCaller.Caller::principal).filter(java.util.Objects::nonNull);
        return p.isPresent() ? p : Viewer.current().map(v -> new Principal(v.user(), v.roles()));
    }

    /**
     * A scope's FVA as the official end-of-day record keeps it beside its exposure (FVA_GUIDE.md section 6.2): the figures, the settings that
     * priced them and the flags; the same engine and settings as every answer.
     */
    static Map<String, Object> official(FvaService.Answer a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("fca", a.total().fca());
        m.put("fba", a.total().fba());
        m.put("fva", a.total().fva());
        m.put("epeLife", a.total().epeLife());
        m.put("fundingSpread", a.borrow().id());
        m.put("fundingSource", a.borrow().source());
        m.put("discountCurve", a.discount() == null ? "none (discounted paths)" : a.discount().id());
        m.put("eePoint", a.point().text());
        m.put("fbaOn", a.fba());
        m.put("fundingSurvival", a.survivalOn());
        m.put("flags", a.flags());
        return m;
    }

    static Map<String, Object> figures(FvaService.Answer a, String scope) {
        FvaMath.Result r = a.total();
        var g = a.scoped().date().book().grid();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("STATUS", "ok");
        m.put("SCOPE", a.scoped().res().canonical());
        m.put("BUSINESS_DATE", a.scoped().date().date().toString());
        m.put("RUN_ID", a.scoped().date().book().runId());
        m.put("FCA", r.fca());
        m.put("FBA", r.fba());
        m.put("FVA", r.fva());
        m.put("EPE_LIFE", r.epeLife());
        m.put("CVA_STATUS", "planned");
        m.put("FUNDING_CURVE", a.borrow().id());
        m.put("FLAGS", a.flags());
        List<Map<String, Object>> terms = new ArrayList<>();
        for (int i = 0; i < r.t().length; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("TENOR", g.tenor(g.primaryAt(i)));
            t.put("DATE", g.date(g.primaryAt(i)).toString());
            t.put("FCA", r.fcaTerm()[i]);
            t.put("FBA", r.fbaTerm()[i]);
            terms.add(t);
        }
        m.put("TERMS", terms);
        m.put("NOTE", "FCA on EE and FBA on ENE by the funding spread " + a.borrow().id() + ", discounted (" + a.scoped().date().date() + ", run "
                + a.scoped().date().book().runId() + (a.flags().isEmpty() ? "" : "; " + String.join(", ", a.flags())) + "); CVA (planned); FVA " + scope
                + " <GO>, PLAY FVA " + scope + " <GO>");
        return m;
    }
}
