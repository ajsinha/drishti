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
package com.ash.drishti.engine.control;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.engine.view.ViewModel.ControlView;
import com.ash.drishti.engine.view.ViewModel.Opt;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.model.Control;
import com.ash.drishti.rachana.model.ControlKind;
import com.ash.drishti.rachana.model.ControlRules;
import com.ash.drishti.rachana.model.Sutra;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns the controls a Sutra declares and the values a request carries into what the view is built with: each control's value
 * ({@code ctl.<id>} in expressions) and its {@link ControlView} (options the reader may pick, the value now).
 *
 * <p>Options are read from the record <em>as the caller may see it</em>: a masked value is never an option, so a reader without
 * the raw right cannot learn a value from a dropdown. A value the request carries that is not among the options falls back to
 * the default. A cascading control's options are read after its parent's value is known. Stateless and thread-safe.
 */
public final class ControlResolver {

    private final ElCompiler el;
    private final BusinessDates dates;
    private final SourceRouter router;
    private final Duration lookupBudget;

    public ControlResolver(ElCompiler el, BusinessDates dates, SourceRouter router, Duration lookupBudget) {
        this.el = el;
        this.dates = dates;
        this.router = router;
        this.lookupBudget = lookupBudget;
    }

    /**
     * What the controls came to for one build.
     *
     * @param ctl the values for {@code ctl.<id>}
     * @param views the controls as the reader sees them
     */
    public record Resolved(Map<String, Object> ctl, List<ControlView> views) {

        public static final Resolved NONE = new Resolved(Map.of(), List.of());

        /** The context with the control values in force. */
        public EvalContext apply(EvalContext eval) {
            return ctl.isEmpty() ? eval : eval.withControls(ctl);
        }
    }

    /**
     * @param s the Sutra
     * @param seen the document as the caller may see it (masked values read the mask)
     * @param values what the request carried
     * @param asOf the request's business date (relative dates are read from it)
     * @param mayOpen the kinds the caller may open (a lookup of another kind lists nothing)
     */
    public Resolved resolve(Sutra s, EvalContext seen, ControlValues values, AsOf asOf, Predicate<String> mayOpen) {
        if (s.controls().isEmpty()) {
            return Resolved.NONE;
        }
        LocalDate today = dates.resolve(asOf).businessDate();
        BusinessCalendar cal = dates.calendar();
        Map<String, Object> ctl = new LinkedHashMap<>();
        List<ControlView> views = new ArrayList<>();
        for (Control c : s.controls()) {
            EvalContext at = seen.withControls(ctl);
            views.add(one(s, c, at, values.get(c.id()), today, cal, asOf, mayOpen, ctl));
        }
        return new Resolved(ctl, views);
    }

    private ControlView one(Sutra s, Control c, EvalContext at, String raw, LocalDate today, BusinessCalendar cal, AsOf asOf,
            Predicate<String> mayOpen, Map<String, Object> ctl) {
        List<Opt> options = List.of();
        boolean truncated = false;
        String value = "";
        List<String> multi = List.of();
        String min = null;
        String max = null;
        switch (c.kind()) {
            case DROPDOWN, CASCADING, TOGGLE, SEGMENTED, MULTI_SELECT, TOP_N -> {
                List<Opt> all = options(c, at, asOf, mayOpen);
                truncated = all.size() > Control.MAX_OPTIONS;
                options = truncated ? all.subList(0, Control.MAX_OPTIONS) : all;
                List<String> known = options.stream().map(Opt::value).toList();
                if (c.kind().multiple()) {
                    multi = multiple(c, raw, known);
                    value = String.join(",", multi);
                    ctl.put(c.id(), DataNode.of(multi));
                } else {
                    value = single(c, raw, known);
                    ctl.put(c.id(), c.kind() == ControlKind.TOP_N && !value.isEmpty() ? (Object) Long.valueOf(value) : value);
                }
            }
            case DATE -> {
                value = date(c, raw, today, cal);
                ctl.put(c.id(), value);
            }
            case DATE_RANGE -> {
                value = dateRange(c, raw, today, cal);
                ctl.put(c.id(), value);
                options = ControlDates.presets(today, cal).stream().map(p -> new Opt(p[0], p[1])).toList();
            }
            case NUMBER_RANGE -> {
                min = bound(c.min(), at);
                max = bound(c.max(), at);
                value = numberRange(c, raw);
                ctl.put(c.id(), value);
            }
            case SEARCH -> {
                value = raw != null ? raw.trim() : c.defaultValue() == null ? "" : c.defaultValue();
                if (value.length() > 200) {
                    value = value.substring(0, 200);
                }
                ctl.put(c.id(), value);
            }
            default -> { }
        }
        List<String> reads = new ArrayList<>(ControlReads.affected(s, c.id()));
        return new ControlView(c.id(), c.kind().id(), c.display(), c.placement().id(), c.panel(), c.parent(), options, value,
                multi.isEmpty() ? null : multi, c.required(), c.remember(), min, max, reads, truncated);
    }

    // ---- options -------------------------------------------------------------------------------------------------------------

    private List<Opt> options(Control c, EvalContext at, AsOf asOf, Predicate<String> mayOpen) {
        if (!c.choices().isEmpty()) {
            return c.choices().stream().map(x -> new Opt(x.value(), x.label())).toList();
        }
        Set<String> seen = new LinkedHashSet<>();
        if (c.from() != null) {
            Object v;
            try {
                v = el.compile(c.from()).eval(at);
            } catch (RuntimeException e) {
                return List.of();                                    // options that cannot be read leave the control empty, never fail the view
            }
            collect(v, seen);
        } else if (c.lookup() != null && mayOpen.test(c.lookup())) {
            router.search(c.lookup(), "", Control.MAX_OPTIONS + 1, lookupBudget, asOf).forEach(h -> seen.add(h.ref().id()));
        }
        List<Opt> out = new ArrayList<>(seen.size());
        seen.forEach(v -> out.add(new Opt(v, v)));
        return out;
    }

    /** The scalar values of a list (or one value), without nulls, empties and masked values. */
    private static void collect(Object v, Set<String> into) {
        Object s = Values.simplify(v);
        if (Values.masked(v) || s == null) {
            return;
        }
        if (s instanceof DataNode n && n.type() == NodeType.ARRAY) {
            for (int i = 0; i < n.size(); i++) {
                DataNode e = n.get(i);
                if (e.type() == NodeType.OBJECT) {
                    e = e.get("id");                                 // a list of records offers their ids
                }
                add(e, into);
            }
        } else if (!(s instanceof DataNode)) {
            add(v, into);
        }
    }

    private static void add(Object e, Set<String> into) {
        Object s = Values.simplify(e);
        if (s == null || Values.masked(e) || s instanceof DataNode) {
            return;
        }
        String t = Values.text(s);
        if (!t.isEmpty() && !DataNode.MASK.equals(t)) {
            into.add(t);
        }
    }

    // ---- values --------------------------------------------------------------------------------------------------------------

    private static String single(Control c, String raw, List<String> known) {
        if (raw != null && known.contains(raw.trim())) {
            return raw.trim();
        }
        if (raw != null && raw.isEmpty() && !c.required() && !c.kind().equals(ControlKind.TOGGLE) && !c.kind().equals(ControlKind.SEGMENTED)
                && c.kind() != ControlKind.TOP_N) {
            return "";                                               // the reader cleared it
        }
        String def = c.defaultValue();
        if (def != null && !def.equals("first") && known.contains(def)) {
            return def;
        }
        boolean mustHave = c.required() || "first".equals(def) || c.kind() == ControlKind.TOGGLE || c.kind() == ControlKind.SEGMENTED
                || c.kind() == ControlKind.TOP_N;
        return mustHave && !known.isEmpty() ? known.get(0) : "";
    }

    private static List<String> multiple(Control c, String raw, List<String> known) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String one : raw.split(",")) {
                if (known.contains(one.trim()) && !out.contains(one.trim())) {
                    out.add(one.trim());
                }
            }
            if (!out.isEmpty() || raw.isEmpty()) {
                return out;
            }
        }
        String def = c.defaultValue();
        if ("all".equals(def)) {
            return new ArrayList<>(known);
        }
        if ("first".equals(def) && !known.isEmpty()) {
            return List.of(known.get(0));
        }
        if (def != null) {
            for (String one : def.split(",")) {
                if (known.contains(one.trim())) {
                    out.add(one.trim());
                }
            }
        }
        return out;
    }

    private static String date(Control c, String raw, LocalDate today, BusinessCalendar cal) {
        if (raw != null) {
            if (raw.isEmpty()) {
                return "";
            }
            var d = ControlDates.date(raw, today, cal);
            if (d.isPresent()) {
                return d.get().toString();
            }
        }
        return c.defaultValue() == null ? "" : ControlDates.date(c.defaultValue(), today, cal).map(LocalDate::toString).orElse("");
    }

    private static String dateRange(Control c, String raw, LocalDate today, BusinessCalendar cal) {
        if (raw != null) {
            if (raw.isEmpty() && !c.required()) {
                return "";
            }
            var r = ControlDates.range(raw, today, cal);
            if (r.isPresent()) {
                return r.get().text();
            }
        }
        return c.defaultValue() == null ? "" : ControlDates.range(c.defaultValue(), today, cal).map(ControlDates.Span::text).orElse("");
    }

    private static String numberRange(Control c, String raw) {
        if (raw != null && ControlRules.isNumberRange(raw.trim())) {
            return raw.trim();
        }
        return c.defaultValue() != null && ControlRules.isNumberRange(c.defaultValue()) ? c.defaultValue() : "";
    }

    private String bound(String spec, EvalContext at) {
        if (spec == null) {
            return null;
        }
        if (spec.matches("-?\\d+(\\.\\d+)?")) {
            return spec;
        }
        try {
            Object v = el.compile(spec).eval(at);
            double d = Values.number(v);
            return Double.isNaN(d) || Values.masked(v) ? null : Values.text(Values.normalise(d));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
