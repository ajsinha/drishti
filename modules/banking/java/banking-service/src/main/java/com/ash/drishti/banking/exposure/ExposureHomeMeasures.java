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

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.home.HomeData;
import com.ash.drishti.server.home.MeasureSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

/**
 * What the exposure engine gives a role Home: {@code PFE} (the scope's {@code peakPfe}) as a KPI, and {@code EXPOSURE} by counterparty
 * (the explorer tree's counterparty nodes: {@code peakPfe} and the credit {@code limit}), read as the person, so the views' own rules
 * decide which counterparties and details they see. TODO(R2): {@link #drivers} needs the engine's what-if overlays; hosted engines
 * replace these view calls.
 */
@Component
final class ExposureHomeMeasures implements MeasureSource {

    private final ExposureService svc;
    private final ExposureViews views;

    ExposureHomeMeasures(ExposureService svc, ExposureViews views) {
        this.svc = svc;
        this.views = views;
    }

    @Override
    public String engine() {
        return "exposure";
    }

    @Override
    public boolean on() {
        return svc.running();
    }

    private ExposureService.Ask ask(Map<String, Object> args) {
        Object q = args.get("quantile");
        return ExposureService.Ask.of(svc.props(), q == null ? null : String.valueOf(q), null, null, null);
    }

    @Override
    public OptionalDouble kpi(Principal p, String measure, Map<String, Object> args, LocalDate date) {
        if (!"PFE".equals(measure)) {
            return OptionalDouble.empty();
        }
        try {
            Object s = args.get("scope");
            String scope = s == null || "firm".equals(s) ? "BANK" : String.valueOf(s);
            return views.scope(p, scope, date, ask(args)).get("peakPfe") instanceof Number n ? OptionalDouble.of(n.doubleValue()) : OptionalDouble.empty();
        } catch (RuntimeException e) {
            return OptionalDouble.empty();
        }
    }

    @Override
    public List<HomeData.MeasureValue> values(Principal p, String measure, String subjectKind, LocalDate date) {
        List<HomeData.MeasureValue> out = new ArrayList<>();
        if ("EXPOSURE".equals(measure) && "counterparty".equals(subjectKind)) {
            for (Counterparty c : counterparties(p, date)) {
                out.add(new HomeData.MeasureValue(subjectKind, c.id, measure, c.value));
            }
        }
        return out;
    }

    @Override
    public List<HomeData.LimitRow> limits(Principal p, String measure, String subjectKind, LocalDate date) {
        List<HomeData.LimitRow> out = new ArrayList<>();
        if ("EXPOSURE".equals(measure) && "counterparty".equals(subjectKind)) {
            for (Counterparty c : counterparties(p, date)) {
                out.add(new HomeData.LimitRow(c.id, c.value, c.limit));
            }
        }
        return out;
    }

    private record Counterparty(String id, double value, Double limit) {}

    @SuppressWarnings("unchecked")
    private List<Counterparty> counterparties(Principal p, LocalDate date) {
        List<Counterparty> out = new ArrayList<>();
        try {
            Map<String, Object> tree = views.tree(p, date, ask(Map.of()));
            for (Object g : tree.get("groups") instanceof List<?> gs ? gs : List.of()) {
                for (Object c : ((Map<String, Object>) g).get("children") instanceof List<?> cs ? cs : List.of()) {
                    Map<String, Object> cp = (Map<String, Object>) c;
                    if (cp.get("peakPfe") instanceof Number v) {
                        Object l = cp.get("limit") instanceof Map<?, ?> m ? m.get("limit") : cp.get("limit");
                        out.add(new Counterparty(String.valueOf(cp.get("id")), v.doubleValue(), l instanceof Number n ? n.doubleValue() : null));
                    }
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }
}
