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
package com.ash.drishti.engine.design;

import com.ash.drishti.engine.shape.BuilderProperties;
import com.ash.drishti.inference.Role;
import com.ash.drishti.inference.Semantics;
import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.SourceLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The panels a document's structure calls for, the way a booking system's deal screen shows a trade: the Details panel (every
 * top-level field with a value, grouped: identity, product, parties, dates, amounts; links open their entity, lists of values
 * read as one line), a list of records that holds lists of its own (legs with their cash flows and resets) as tabs whose every tab
 * shows its terms, a chart and a table per nested list (a tree table when that list nests one more: cash flows opening to their
 * resets) and a chart of the innermost dated values (fixings), the nested lists across all records in one schedule, and a list
 * of dated amounts (events, payments) as a table, a ladder of amounts by date and, with a label, a timeline. Every option names
 * fields the shape has; the reasons say why. Stateless apart from its inputs: thread-safe.
 */
final class StructurePanels {

    private static final SourceLocation DRAFT = new SourceLocation("auto-design", 0, 0);
    /** An amount that flows on a date: summed by date into a ladder. */
    private static final Pattern FLOW_AMOUNT = Pattern.compile("(?i)amount|payment|cash_?flow|interest|coupon|premium|(^|_)pv$|pnl|(^|_)value$");
    /** An amount outstanding over time: drawn as a step line (a notional profile). */
    private static final Pattern PROFILE = Pattern.compile("(?i)notional|principal|balance|outstanding|nominal|exposure");
    /** The date a period starts (a profile steps on it). */
    private static final Pattern START = Pattern.compile("(?i)start|from|begin|effective");
    /** The date money moves. */
    private static final Pattern PAY = Pattern.compile("(?i)pay|settle|value_?date|event_?date|cash");
    /** The date a value is observed (a fixing). */
    private static final Pattern OBSERVED = Pattern.compile("(?i)reset|fixing|observ|determination");
    /** A value observed on a date: a rate, a price, a level. */
    private static final Pattern FIXING = Pattern.compile("(?i)rate|price|fixing|level|index|value");
    /** A short text that says what a row is. */
    private static final Pattern LABEL = Pattern.compile("(?i)((^|_)(type|event|action|description|label|kind)$|[a-z]Type$)");
    private static final int TABLE_COLUMNS = 10;
    private static final int TREE_COLUMNS = 12;
    private static final int TERMS = 24;
    private static final int MONTHS_OVER = 24;

    /** One drafted panel: its source (the packer keeps one per source), and its ranked choices. */
    record Planned(String source, List<PanelChoice> choices) {}

    private final BuilderProperties props;
    private final SampleStats stats;
    private final PanelChooser chooser;
    private final List<Pattern> groups;

    StructurePanels(BuilderProperties props, SampleStats stats, PanelChooser chooser) {
        this.props = props;
        this.stats = stats;
        this.chooser = chooser;
        this.groups = props.detailGroups().stream().map(g -> Pattern.compile(g, Pattern.CASE_INSENSITIVE)).toList();
    }

    // ------------------------------------------------------------------------------------------------------- details

    /**
     * The document's own fields as one panel of labelled values, the way a ticket groups them: the id, then each of
     * {@code drishti.builder.detail-groups} in turn, then the links, the dates and the amounts, then the rest; a field blank in
     * every sample is left out, a link opens its entity, a list of plain values reads as one line. Null with fewer than two.
     */
    PanelChoice details(FieldNode root, String selfKind) {
        record Shown(FieldNode f, int group, int order) {}
        List<Shown> shown = new ArrayList<>();
        int order = 0;
        for (FieldNode p : root.props().values()) {
            order++;
            boolean list = p.array() && p.props().isEmpty() && !p.is("link") && !p.is("distribution");
            if (!(p.scalar() || list || p.array() && p.is("link")) || p.is("text") || !hasValue(p)) {
                continue;
            }
            shown.add(new Shown(p, groupOf(p, selfKind), order));
        }
        if (shown.size() < 2) {
            return null;
        }
        shown.sort(Comparator.comparingInt(Shown::group).thenComparingInt(Shown::order));
        List<Column> cols = new ArrayList<>();
        for (Shown s : shown.subList(0, Math.min(shown.size(), props.detailsMax()))) {
            cols.add(column(s.f(), "$.", selfKind));
        }
        return new PanelChoice("kv", 0.95, cols.size() + " top-level fields with a value in the samples, as labelled values, grouped: identity, "
                + "product, parties, dates, amounts", Area.MAIN, "Details", Map.of(), cols);
    }

    private int groupOf(FieldNode p, String selfKind) {
        if (p.is("id") && idNamed(p.name()) || p.is("link") && selfKind.equalsIgnoreCase(p.role().kind())) {
            return 0;
        }
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).matcher(p.name()).find()) {
                return 1 + i;
            }
        }
        if (p.is("link")) {
            return 1 + groups.size();
        }
        if (p.date()) {
            return 2 + groups.size();
        }
        return p.is("measure") ? 3 + groups.size() : 4 + groups.size();
    }

    // ------------------------------------------------------------------------------------------------------ lists

    /**
     * The panels for a list of records, or nothing when the general rules suit it better: a list whose records hold lists of
     * records (at most {@code tabs-max} of them) is tabs with panels inside; a list of dated amounts with a text saying what each is
     * is a table, a ladder by date and a timeline.
     */
    List<Planned> forList(FieldNode p) {
        if (!p.array() || p.tree() || p.props().isEmpty() || List.of("tree", "graph", "ohlc", "grid", "steps", "distribution").contains(p.roleName())) {
            return List.of();
        }
        List<FieldNode> nested = recordLists(p);
        int typical = stats.count() > 0 ? stats.typicalLength(p.path()) : p.maxItems();
        if (!nested.isEmpty() && typical >= 1 && typical <= props.tabsMax()) {
            return masterDetail(p, nested, typical);
        }
        if (nested.isEmpty()) {
            return dated(p);
        }
        return List.of();
    }

    private List<Planned> masterDetail(FieldNode p, List<FieldNode> nested, int typical) {
        String one = singular(Semantics.humanize(p.name()));
        String prefix = slug(p.name());
        List<Panel> parts = new ArrayList<>();
        List<Column> terms = terms(p);
        if (!terms.isEmpty()) {
            parts.add(part(prefix + "-terms", PanelKind.KV, "Terms", terms, Map.of()));
        }
        StringBuilder what = new StringBuilder();
        for (FieldNode q : nested) {
            String rows = "@." + q.name();
            Panel chart = chart(q, rows, prefix + "-" + slug(q.name()) + "-chart");
            if (chart != null) {
                parts.add(chart);
            }
            FieldNode inner = recordLists(q).stream().findFirst().orElse(null);
            parts.add(table(q, rows, inner, prefix + "-" + slug(q.name())));
            Panel fixings = inner == null ? null : observed(q, inner, prefix + "-" + slug(inner.name()) + "-chart");
            if (fixings != null) {
                parts.add(fixings);
            }
            what.append(what.isEmpty() ? "" : ", ").append("'").append(q.name()).append("'").append(inner == null ? "" : " (opening to '" + inner.name() + "')");
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("each", p.path());
        o.put("tabTitle", tabTitle(p, one));
        if (typical <= 3) {
            o.put("layout", "columns");
        }
        List<Planned> out = new ArrayList<>();
        out.add(new Planned(p.path(), List.of(new PanelChoice("tabs", 0.92, "records holding lists of their own (" + what + "): one tab per "
                + one.toLowerCase(Locale.ROOT) + " with its terms, a chart and a table per list" + (typical <= 3 ? ", side by side" : ""),
                Area.MAIN, Semantics.humanize(p.name()), o, List.of(), parts))));
        if (typical >= 2) {                         // every record's list in one schedule
            for (FieldNode q : nested) {
                List<Column> cols = columns(q, null, TABLE_COLUMNS);
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("rows", "flatten(" + p.path() + "[*]." + q.name() + ")");
                out.add(new Planned(q.path(), List.of(new PanelChoice("table", 0.7, "every " + one.toLowerCase(Locale.ROOT) + "'s '" + q.name()
                        + "' in one schedule", Area.MAIN, Semantics.humanize(q.name()) + ", every " + one.toLowerCase(Locale.ROOT), t, cols))));
            }
        }
        return out;
    }

    /** A record's own fields, its records' fields ({@code INDEX.NAME} as "Index name") and its lists of plain values, those with a value. */
    private List<Column> terms(FieldNode p) {
        List<Column> out = new ArrayList<>();
        for (FieldNode f : p.props().values()) {
            if (out.size() >= TERMS) {
                break;
            }
            if ((f.scalar() || f.array() && f.props().isEmpty()) && hasValue(f) && !f.is("text")) {
                out.add(column(f, "@.", null));
            } else if (f.object() && !f.is("link")) {
                for (FieldNode g : f.props().values()) {
                    if (g.scalar() && hasValue(g) && out.size() < TERMS) {
                        Column c = column(g, "@." + f.name() + ".", null);
                        out.add(new Column(Semantics.humanize(f.name()) + " " + c.label().toLowerCase(Locale.ROOT), c.bind(), c.fmt(), c.tone(), false, false));
                    }
                }
            }
        }
        return out;
    }

    private String tabTitle(FieldNode p, String one) {
        FieldNode id = p.props().values().stream().filter(f -> f.scalar() && (f.is("id") || idNamed(f.name()))).findFirst().orElse(null);
        FieldNode kind = p.props().values().stream().filter(f -> f.scalar() && "string".equals(f.type()) && (f.is("dimension") || f.is("status"))
                && LABEL.matcher(f.name()).find()).findFirst().orElse(null);
        String head = "'" + one.replace("'", "") + " ' + " + (id == null ? "(#index + 1)" : "nonblank(@." + id.name() + ", #index + 1)");
        return kind == null ? head : head + " + (nonblank(@." + kind.name() + ") ? ' · ' + @." + kind.name() + " : '')";
    }

    // ------------------------------------------------------------------------------------------- the nested lists

    /** A dated list's chart: amounts that move on a date as a ladder by date, an amount outstanding as a step line; null otherwise. */
    private Panel chart(FieldNode q, String rows, String id) {
        int typical = stats.count() > 0 ? stats.typicalLength(q.path()) : -1;
        List<FieldNode> dates = q.props().values().stream().filter(f -> f.scalar() && f.date()).toList();
        if (dates.isEmpty() || typical >= 0 && typical < 2) {
            return null;
        }
        FieldNode flow = measure(q, FLOW_AMOUNT);
        if (flow != null) {
            FieldNode d = pick(dates, PAY);
            return part(id, PanelKind.HBAR, Semantics.humanize(flow.name()) + " by " + Semantics.humanize(d.name()).toLowerCase(Locale.ROOT), List.of(),
                    ladder(rows, d, flow));
        }
        FieldNode profile = measure(q, PROFILE);
        FieldNode y = profile != null ? profile : best(q);
        if (y == null) {
            return null;
        }
        FieldNode d = pick(dates, profile != null ? START : PAY);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rows", rows);
        o.put("x", d.name());
        o.put("y", y.name());
        if (profile != null) {
            o.put("step", true);
        }
        o.put("fmt", fmtOf(y, "amount0"));
        return part(id, PanelKind.LINE, Semantics.humanize(y.name()) + (profile != null ? " profile" : ""), List.of(), o);
    }

    /** The innermost list's observed values (fixings) across the middle list, shown only when there are any. */
    private Panel observed(FieldNode q, FieldNode inner, String id) {
        List<FieldNode> dates = inner.props().values().stream().filter(f -> f.scalar() && f.date()).toList();
        FieldNode y = measure(inner, FIXING);
        if (dates.isEmpty() || y == null) {
            return null;
        }
        String rows = "flatten(@." + q.name() + "[*]." + inner.name() + ")[?nonblank(@." + y.name() + ")]";
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("when", "size(" + rows + ") > 0");
        o.put("rows", rows);
        o.put("x", pick(dates, OBSERVED).name());
        o.put("y", y.name());
        o.put("fmt", fmtOf(y, "price4"));
        return part(id, PanelKind.LINE, Semantics.humanize(inner.name()) + " (" + Semantics.humanize(y.name()).toLowerCase(Locale.ROOT) + ")", List.of(), o);
    }

    /** The nested list as a table; with {@code inner}, a tree whose rows open to it, the first column naming either. */
    private Panel table(FieldNode q, String rows, FieldNode inner, String id) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rows", rows);
        if (inner != null) {
            o.put("children", "@." + inner.name());
            o.put("expand", 1);
        }
        return part(id, PanelKind.TABLE, Semantics.humanize(q.name()) + (inner == null ? "" : " and " + Semantics.humanize(inner.name()).toLowerCase(Locale.ROOT)),
                columns(q, inner, inner == null ? TABLE_COLUMNS : TREE_COLUMNS), o);
    }

    // ------------------------------------------------------------------------------------------- dated amounts

    /** A list of dated amounts with a text saying what each row is: a table, a ladder of the amounts by date and a timeline. */
    private List<Planned> dated(FieldNode p) {
        List<FieldNode> dates = p.props().values().stream().filter(f -> f.scalar() && f.date()).toList();
        FieldNode amount = measure(p, FLOW_AMOUNT);
        List<FieldNode> texts = p.props().values().stream().filter(f -> f.scalar() && "string".equals(f.type()) && !f.date() && hasValue(f)).toList();
        if (dates.isEmpty() || amount == null || texts.isEmpty()) {
            return List.of();
        }
        FieldNode d = pick(dates, PAY);
        String what = "dated amounts ('" + amount.name() + "' on '" + d.name() + "')";
        List<Planned> out = new ArrayList<>();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("rows", p.path());
        List<PanelChoice> first = new ArrayList<>();
        first.add(new PanelChoice("table", 0.85, what + ": every row, the amounts totalled", Area.MAIN, Semantics.humanize(p.name()), t,
                columns(p, null, TABLE_COLUMNS)));
        out.add(new Planned(p.path(), first));
        boolean byMonth = stats.count() > 0 && stats.distinct(d.path(), MONTHS_OVER + 1) > MONTHS_OVER;
        Map<String, Object> ladder = ladder(p.path(), d, amount);
        if (byMonth) {
            ladder.put("rows", ((String) ladder.get("rows")).replaceFirst("\\)$", ", 'month')"));
        }
        out.add(new Planned(p.path() + "#by-date", List.of(new PanelChoice("hbar", 0.8, what + ": summed by " + (byMonth ? "month" : "date")
                + ", paid below the line, received above", Area.RIGHT, Semantics.humanize(amount.name()) + " by " + (byMonth ? "month"
                : Semantics.humanize(d.name()).toLowerCase(Locale.ROOT)), ladder, List.of()))));
        FieldNode label = texts.stream().filter(f -> LABEL.matcher(f.name()).find() || props.labelNames().stream().anyMatch(n -> n.equalsIgnoreCase(f.name())))
                .findFirst().orElse(null);
        if (label != null) {
            Map<String, Object> tl = new LinkedHashMap<>();
            tl.put("rows", p.path() + "[?nonblank(@." + d.name() + ")]");
            tl.put("date", d.name());
            tl.put("label", label.name());
            texts.stream().filter(f -> f != label && !f.is("status") && !f.is("id") && !idNamed(f.name())).findFirst().ifPresent(f -> tl.put("detail", f.name()));
            texts.stream().filter(f -> f.is("status")).findFirst().ifPresent(f -> tl.put("status", f.name()));
            out.add(new Planned(p.path() + "#timeline", List.of(new PanelChoice("timeline", 0.75, what + " named by '" + label.name() + "': in date order",
                    Area.MAIN, Semantics.humanize(p.name()) + " by date", tl, List.of()))));
        }
        return out;
    }

    private static Map<String, Object> ladder(String rows, FieldNode d, FieldNode amount) {
        Map<String, Object> o = new LinkedHashMap<>();
        String filtered = rows + "[?nonblank(@." + amount.name() + ") && nonblank(@." + d.name() + ")]";
        o.put("rows", "group(" + filtered + ", '" + d.name() + "', '" + amount.name() + "')");
        o.put("fmt", "signed0");
        o.put("tone", "sign");
        return o;
    }

    // ------------------------------------------------------------------------------------------------- columns

    /**
     * A list's columns, as a trader reads them: what identifies the row, its dates, what it is, then its amounts and rates;
     * fields blank in every sample are left out. With {@code inner} (a tree) the first column names either level, and the inner
     * level's own fields follow.
     */
    private List<Column> columns(FieldNode rows, FieldNode inner, int max) {
        List<FieldNode> fields = rows.props().values().stream().filter(f -> f.scalar() && hasValue(f)).sorted(Comparator.comparingInt(this::rank)).toList();
        List<Column> out = new ArrayList<>();
        FieldNode innerId = inner == null ? null : inner.props().values().stream().filter(f -> f.scalar() && (f.is("id") || idNamed(f.name()))).findFirst().orElse(null);
        for (FieldNode f : fields) {
            if (out.size() >= max) {
                break;
            }
            Column c = column(f, "@.", null);
            if (innerId != null && out.isEmpty() && (f.is("id") || idNamed(f.name()))) {
                c = new Column(stem(f.name()) + " / " + stem(innerId.name()).toLowerCase(Locale.ROOT), "nonblank(@." + f.name() + ", @." + innerId.name() + ")",
                        null, null, false, false);
            }
            out.add(c);
        }
        if (inner != null) {
            for (FieldNode g : inner.props().values()) {
                if (out.size() < max && g.scalar() && hasValue(g) && g != innerId && !rows.props().containsKey(g.name())) {
                    out.add(column(g, "@.", null));
                }
            }
        }
        return out;
    }

    /** Identifiers first, then dates, then labels and states, then numbers. */
    private int rank(FieldNode f) {
        if (f.is("id") || idNamed(f.name())) {
            return 0;
        }
        if (f.date()) {
            return 1;
        }
        return "number".equals(f.type()) && !f.is("dimension") ? 3 : 2;
    }

    /** One labelled value: a link opens its entity, a list of plain values reads as one line, amounts and rates keep their format. */
    private Column column(FieldNode f, String prefix, String selfKind) {
        String bind = prefix + f.name();
        String label = Semantics.humanize(f.name());
        if (f.is("link") && f.role().kind() != null && !f.array() && (selfKind == null || !selfKind.equalsIgnoreCase(f.role().kind()))) {
            return new Column(label, "link(" + bind + ", '" + f.role().kind() + "')", null, null, false, false);
        }
        if (f.array()) {
            return new Column(label, "reduce(" + bind + ", '', concat(@acc, nonblank(@acc) ? ', ' : '', @))", null, null, false, false);
        }
        if (f.is("status")) {
            return new Column(label, bind, null, "status", false, false);
        }
        if (!"number".equals(f.type())) {
            return new Column(label, bind, f.date() ? "date" : null, null, false, false);
        }
        if (f.is("dimension") || f.is("id")) {
            return new Column(label, bind, null, null, false, false);
        }
        Role r = chooser.roleOf(f);
        boolean signed = !Double.isNaN(f.min()) && f.min() < 0;
        boolean flow = FLOW_AMOUNT.matcher(f.name()).find() && !PROFILE.matcher(f.name()).find();
        String fmt = signed && (r.fmt() == null || r.fmt().startsWith("amount")) ? "signed2" : fmtOf(f, "amount2");
        return new Column(label, bind, fmt, signed ? "sign" : r.tone(), flow && prefix.startsWith("@"), false);
    }

    private String fmtOf(FieldNode f, String fallback) {
        Role r = chooser.roleOf(f);
        return r.fmt() == null || "date".equals(r.fmt()) ? fallback : r.fmt();
    }

    // -------------------------------------------------------------------------------------------------- helpers

    /** The lists of records directly under the records of {@code p}. */
    private List<FieldNode> recordLists(FieldNode p) {
        return p.props().values().stream().filter(f -> f.array() && !f.props().isEmpty() && !f.tree() && hasValue(f)).toList();
    }

    /** A number of {@code rows} named like {@code names}, the heaviest role first; null when none. */
    private FieldNode measure(FieldNode rows, Pattern names) {
        return rows.props().values().stream().filter(f -> "number".equals(f.type()) && !f.is("dimension") && !f.is("id") && names.matcher(f.name()).find()
                && hasValue(f)).max(Comparator.comparingInt((FieldNode f) -> chooser.roleOf(f).weight())).orElse(null);
    }

    private FieldNode best(FieldNode rows) {
        List<FieldNode> nums = rows.props().values().stream().filter(f -> "number".equals(f.type()) && f.is("measure") && hasValue(f)).toList();
        return nums.isEmpty() ? null : chooser.best(nums);
    }

    private static FieldNode pick(List<FieldNode> dates, Pattern preferred) {
        return dates.stream().filter(f -> preferred.matcher(f.name()).find()).findFirst().orElse(dates.get(0));
    }

    private boolean hasValue(FieldNode f) {
        return stats.count() == 0 ? f.presence() >= props.rareBelow() : stats.present(f.path()) > 0;
    }

    private static boolean idNamed(String name) {
        return name.matches("(?i).*(^|_)id$") || name.matches(".*[a-z0-9]Id$");
    }

    /** {@code FLOW_ID} -> "Flow". */
    private static String stem(String idName) {
        String s = idName.replaceAll("(?i)_?id$", "");
        return Semantics.humanize(s.isEmpty() ? idName : s);
    }

    private static String singular(String words) {
        if (words.endsWith("ies")) {
            return words.substring(0, words.length() - 3) + "y";
        }
        return words.endsWith("s") && !words.endsWith("ss") ? words.substring(0, words.length() - 1) : words;
    }

    private static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "list" : s;
    }

    private static Panel part(String id, PanelKind kind, String title, List<Column> cols, Map<String, Object> options) {
        return new Panel(id, kind, title, null, null, Area.MAIN, false, cols, null, options, DRAFT);
    }
}
