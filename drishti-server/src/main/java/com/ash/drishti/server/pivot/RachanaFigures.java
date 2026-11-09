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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.FigureSource;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The server's figures for Rachana's {@code measure(...)} and {@code cell(...)} (docs/architecture/ARCHITECTURE.md part 3), registered into the engine at
 * start ({@link PivotConfiguration}). Both are answered by the pivot engine for the person of the request being answered (their
 * entitlements, masks and the aggregate-only minimum apply as on the Pivot screen); nothing is computed here:
 *
 * <ul>
 *   <li>{@code measure('MEASURE', 'GROUP:G-1')}: the compute engine whose pivot provider offers the measure as its own (
 *       {@code es}, {@code pv} of the VaR engine …) answers one cell, {@code DESK = DESK-COMM}, exactly as its engine computes it: a
 *       holistic figure from the scope's own trades, never a sum of other figures. Options: {@code date} (the business date),
 *       {@code source} (when two engines offer the same name).</li>
 *   <li>{@code cell('owner/desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')}: a saved pivot view the person may open, the cell at
 *       those coordinates, the view's first value (or the one named under {@code value}).</li>
 * </ul>
 */
public final class RachanaFigures implements FigureSource {

    private final PivotCore core;
    private final PivotViews views;
    private final java.util.function.BooleanSupplier on;

    /** @param on true while the pivot engine runs (config/engines/pivot.yaml) */
    public RachanaFigures(PivotCore core, PivotViews views, java.util.function.BooleanSupplier on) {
        this.core = core;
        this.views = views;
        this.on = on;
    }

    @Override
    public Object measure(String name, String scope, Map<String, String> options) {
        Principal p = principal("measure");
        Viewer v = new Viewer(p.user(), p);
        String fn = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        String source = source(v, fn, options.get("source"));
        Map<String, String> coords = new LinkedHashMap<>();
        if (scope != null && !scope.isBlank()) {
            for (String part : scope.split(",")) {
                int colon = part.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("the scope '" + scope + "' is written LEVEL:MEMBER, for example DESK:DESK-COMM");
                }
                coords.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("source", source);
        if (options.get("date") != null) {
            view.put("date", options.get("date"));
        }
        view.put("rows", new ArrayList<>(coords.keySet()));
        view.put("columns", List.of());
        view.put("values", List.of(fn + "()"));
        return figure(PivotView.parse(view), v, coords, null);
    }

    @Override
    public Object cell(String viewId, Map<String, String> coordinates) {
        Principal p = principal("cell");
        Map<String, String> coords = new LinkedHashMap<>(coordinates);
        String value = coords.remove("value");
        PivotView view;
        try {
            view = views.open(p, viewId);
        } catch (DrishtiException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        return figure(view, new Viewer(p.user(), p), coords, value);
    }

    /** The engine whose provider offers {@code fn} as its own measure (a compute engine's), or the one named. */
    private String source(Viewer v, String fn, String named) {
        List<String> offered = new ArrayList<>();
        for (PivotProvider provider : core.providers()) {
            if (provider.nativeFunction(fn, List.of()).isEmpty()) {
                continue;
            }
            for (String s : provider.sources(v).keySet()) {
                if (named == null || named.equals(s)) {
                    offered.add(s);
                }
            }
        }
        if (offered.isEmpty()) {
            throw new IllegalArgumentException("no compute engine on this server offers the measure '" + fn + "' to you"
                    + (named == null ? "" : " from '" + named + "'") + " (the VaR engine's: var, es, pv, trades)");
        }
        if (offered.size() > 1) {
            throw new IllegalArgumentException("'" + fn + "' is offered by " + offered + ": name one with the option source=...");
        }
        return offered.get(0);
    }

    private Object figure(PivotView view, Viewer v, Map<String, String> coords, String value) {
        Map<String, Object> cell;
        try {
            cell = core.cell(v, view, coords);
        } catch (DrishtiException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if (!Boolean.TRUE.equals(cell.get("found"))) {
            return null;
        }
        if (!(cell.get("values") instanceof Map<?, ?> values) || values.isEmpty()) {
            return null;
        }
        if (value == null) {
            return values.values().iterator().next();
        }
        if (!values.containsKey(value)) {
            throw new IllegalArgumentException("the view has no value '" + value + "'; its values: " + values.keySet());
        }
        return values.get(value);
    }

    /** The person of the request being answered: a figure is never answered for nobody. */
    private Principal principal(String fn) {
        if (!on.getAsBoolean()) {
            throw new IllegalArgumentException(fn + "(...) is answered by the pivot engine, which is switched off on this server");
        }
        RequestAttributes a = RequestContextHolder.getRequestAttributes();
        if (a != null && a.getAttribute(Principal.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof Principal p) {
            return p;
        }
        throw new IllegalArgumentException(fn + "(...) is answered for a signed-in person; this evaluation has none");
    }
}
