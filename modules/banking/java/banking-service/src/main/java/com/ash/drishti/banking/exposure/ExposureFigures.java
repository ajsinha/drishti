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
package com.ash.drishti.banking.exposure;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.kernel.api.view.ViewAugmenter;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The exposure engine's figures on the netting-set and counterparty pages (EXPOSURE_GUIDE.md section 22.1): neither kind stores a profile, an EE
 * peak or a PFE peak any more, so when the page is opened the engine computes them as the caller and the view reads them under
 * {@code EXPOSURE_ENGINE}: {@code STATUS} ({@code ok}; {@code no-paths} when the date's run does not cover the netting set or any of the
 * counterparty's; {@code unavailable} with the reason), the scope, the date and run, {@code CE}, {@code EPE}, {@code EEPE},
 * {@code PEAK_EE}, {@code PEAK_PFE} and its date, {@code PROFILE} (tenor, EE, PFE 95 and 99) and a {@code NOTE} saying how it was made,
 * never stale stored numbers. R2: behind the exposure family's switch ({@code exposure.route}) the figures come from Drishti Risk's
 * {@code ccr} engine ({@link ExposureEnginePath#figures}: the run's id and paths, the scope's figures and profile at the default and at
 * 99%, its flags), the same page; proven equal in {@code ExposureHomesSideBySideTest}.
 */
final class ExposureFigures implements ViewAugmenter {

    static final String FIELD = "EXPOSURE_ENGINE";
    private final ExposureService svc;
    private final ExposureViews views;
    private final ExposureEnginePath engine;

    ExposureFigures(ExposureService svc, ExposureViews views, ExposureEnginePath engine) {
        this.svc = svc;
        this.views = views;
        this.engine = engine;
    }

    @Override
    public boolean augments(String kind) {
        return Set.of(svc.props().kinds().nettingSet(), svc.props().kinds().counterparty()).contains(kind);
    }

    @Override
    public EntityDocument augment(EntityDocument doc, AsOf asOf) {
        boolean onEngine = engine != null && engine.engine(null);     // R2: the exposure family's switch (exposure.route)
        if (!(doc.data() instanceof DataNode.Obj o) || doc.deleted() || !(onEngine ? engine.running() : svc.running())) {
            return doc;
        }
        String scope = (doc.ref().kind().equals(svc.props().kinds().nettingSet()) ? "NSET:" : "CPTY:") + doc.ref().id();
        Map<String, Object> figures;
        Optional<Principal> who = caller();
        LocalDate date = asOf == null ? null : asOf.businessDate();
        if (date == null) {
            date = doc.provenance().businessDate();
        }
        if (who.isEmpty()) {
            figures = note("unavailable", scope, "the exposure figures are computed for a signed-in viewer");
        } else {
            try {
                figures = figures(onEngine ? engine.figures(who.get(), scope, date) : views.scope(who.get(), scope, date,
                        ExposureService.Ask.of(svc.props(), null, null, null, null)));
            } catch (DrishtiException e) {
                String why = String.valueOf(e.getMessage()).replaceFirst("^DRS-\\d{4} ", "");
                boolean none = e.errorCode() == ErrorCode.ENTITY_NOT_FOUND && why.startsWith("no exposure paths for");
                figures = note(none ? "no-paths" : "unavailable", scope, why);
            } catch (IllegalArgumentException e) {
                figures = note("unavailable", scope, String.valueOf(e.getMessage()));
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
        m.put("PROFILE", List.of());
        return m;
    }

    private static Optional<Principal> caller() {
        Optional<Principal> p = NetCaller.current().map(NetCaller.Caller::principal).filter(java.util.Objects::nonNull);
        return p.isPresent() ? p : Viewer.current().map(v -> new Principal(v.user(), v.roles()));
    }

    /** The page's figures from a scope answer (the in-tree route's, or the engine path's {@link ExposureEnginePath#figures}: the same keys). */
    private static Map<String, Object> figures(Map<String, Object> h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("STATUS", "ok");
        m.put("SCOPE", h.get("canonical"));
        m.put("BUSINESS_DATE", h.get("businessDate"));
        m.put("RUN_ID", h.get("runId"));
        m.put("PATHS", h.get("paths"));
        m.put("CONFIDENCE", h.get("confidence"));
        m.put("CE", h.get("ce"));
        m.put("EPE", h.get("epe"));
        m.put("EEPE", h.get("eepe"));
        m.put("PEAK_EE", h.get("peakEe"));
        m.put("PEAK_PFE", h.get("peakPfe"));
        m.put("PEAK_PFE_DATE", h.get("peakPfeDate"));
        m.put("PEAK_PFE_99", h.get("peakPfe99"));
        List<Map<String, Object>> prof = new ArrayList<>();
        if (h.get("profile") instanceof List<?> l) {
            for (Object o : l) {
                Map<?, ?> r = (Map<?, ?>) o;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("TENOR", r.get("tenor"));
                x.put("DATE", r.get("date"));
                x.put("EE", r.get("ee"));
                x.put("ENE", r.get("ene"));
                x.put("PFE", r.get("pfe"));
                x.put("PFE_99", r.get("pfe99"));
                prof.add(x);
            }
        }
        m.put("PROFILE", prof);
        m.put("FLAGS", h.get("flags"));
        m.put("NOTE", "computed by the exposure engine as you opened the page (" + h.get("businessDate") + ", run " + h.get("runId") + ", "
                + h.get("paths") + " paths); EXP " + h.get("canonical") + " <GO>");
        return m;
    }
}
