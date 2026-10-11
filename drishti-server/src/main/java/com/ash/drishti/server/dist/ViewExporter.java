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
package com.ash.drishti.server.dist;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.control.ViewState;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.collab.snapshot.ColumnFilters;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Sutra view of an entity, as one reader sees it: built by the view pipeline with that reader's masks and gate, under the control values
 * and column filters the person had on screen (the same grammar as a share's view state), and turned into an {@link ExportDoc} (a section per
 * table-like panel: tables, fields, pivots, metrics). Chart panels are left out of the tables and named in a note; the PDF and PNG draw the view
 * itself through the snapshot layout, which draws them.
 */
public final class ViewExporter {

    /** A built view with its table form. */
    public record Built(ViewModel view, ExportDoc doc, String asOf, ViewState state) {}

    // a number as a view shows it: sign (ASCII or Unicode minus), digits with thousands separators, decimals, maybe a percent sign
    private static final Pattern SHOWN = Pattern.compile("^([+\\-−]?)(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?(%?)$");

    private final ViewPipeline pipeline;
    private final Entitlements entitlements;

    public ViewExporter(ViewPipeline pipeline, Entitlements entitlements) {
        this.pipeline = pipeline;
        this.entitlements = entitlements;
    }

    /** Throws unless {@code who} may open views of the kind. */
    public void check(Principal who, String kind) {
        entitlements.requireOpen(who, kind);
    }

    /**
     * @param date a business date (yyyy-mm-dd), or blank for the latest
     * @param view the view state as query text ({@code ctl.x=..&f.panel.col=..}), or blank
     */
    public Built build(Principal who, String kind, String id, String date, String view) {
        entitlements.requireOpen(who, kind);
        ViewState state;
        try {
            state = ViewState.parse(view, Integer.MAX_VALUE);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the view state is not readable: " + e.getMessage());
        }
        AsOf asOf;
        try {
            asOf = date == null || date.isBlank() ? AsOf.LATEST : AsOf.of(LocalDate.parse(date));
        } catch (java.time.format.DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the date is yyyy-mm-dd");
        }
        EntityRef ref = EntityRef.of(kind, id);
        ViewModel v = state.isEmpty() ? pipeline.view(ref, asOf, entitlements.redactor(who), k -> entitlements.mayOpen(who, k))
                : pipeline.view(ref, asOf, entitlements.redactor(who), k -> entitlements.mayOpen(who, k), state.controls());
        v = ColumnFilters.apply(entitlements.restrict(who, v), state, LocalDate.now(ZoneOffset.UTC));      // the filters run after the masks: a masked cell never matches
        ViewModel.Provenance pv = v.provenance();
        String as = pv != null && pv.businessDate() != null ? pv.businessDate() : date == null || date.isBlank() ? "live" : date;
        return new Built(v, doc(v, as, state), as, state);
    }

    private ExportDoc doc(ViewModel v, String asOf, ViewState state) {
        ViewModel.TitleView t = v.title();
        String head = (t.pill() == null || t.pill().isBlank() ? "" : t.pill() + " ") + (t.id() == null ? "" : t.id());
        List<String> notes = new ArrayList<>();
        if (!state.isEmpty()) {
            notes.add("View settings: " + state.text());
        }
        List<ExportDoc.Section> sections = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (v.strip() != null && !v.strip().isEmpty()) {
            sections.add(new ExportDoc.Section("strip", "Summary", ExportDoc.TABLE, List.of("Field", "Value"),
                    v.strip().stream().map(c -> List.<Object>of(nz(c.label()), typed(c.text()))).toList(), null, List.of()));
        }
        for (ViewModel.PanelView p : v.panels()) {
            panel(p, "", sections, skipped);
        }
        if (!skipped.isEmpty()) {
            notes.add("Charts and other graphic panels are not in the table form: " + String.join(", ", skipped) + ".");
        }
        return new ExportDoc(head.isBlank() ? "View" : head, "Data as of " + asOf, notes, sections);
    }

    private void panel(ViewModel.PanelView p, String prefix, List<ExportDoc.Section> out, List<String> skipped) {
        if (p.denied() != null) {
            return;
        }
        String title = prefix + (p.title() == null || p.title().isBlank() ? p.id() : p.title());
        if (p.error() != null) {
            out.add(new ExportDoc.Section(p.id(), title, ExportDoc.TABLE, List.of(), List.of(), "This panel could not be built.", List.of()));
            return;
        }
        if (p.empty() || p.data() == null) {
            return;
        }
        switch (p.data()) {
            case PanelData.Table t -> out.add(table(p.id(), title, t));
            case PanelData.Fields f -> out.add(new ExportDoc.Section(p.id(), title, ExportDoc.TABLE, List.of("Field", "Value"),
                    f.fields().stream().map(c -> List.<Object>of(nz(c.label()), typed(c.text()))).toList(), null, List.of()));
            case PanelData.Pivot pv -> out.add(pivot(p.id(), title, pv));
            case PanelData.Metric m -> out.add(new ExportDoc.Section(p.id(), title, ExportDoc.CARD, List.of(m.caption() == null || m.caption().isBlank() ? title : m.caption()),
                    List.of(List.<Object>of(typed(m.value() == null ? "" : m.value().text()))), null, List.of()));
            case PanelData.Tabs tabs -> {
                for (PanelData.Tab tab : tabs.tabs()) {
                    String tt = title + " · " + nz(tab.title());
                    if (tab.fields() != null && !tab.fields().isEmpty()) {
                        out.add(new ExportDoc.Section(p.id() + "-" + tt, tt, ExportDoc.TABLE, List.of("Field", "Value"),
                                tab.fields().stream().map(c -> List.<Object>of(nz(c.label()), typed(c.text()))).toList(), null, List.of()));
                    }
                    for (ViewModel.PanelView part : tab.panels() == null ? List.<ViewModel.PanelView>of() : tab.panels()) {
                        panel(part, tt + " · ", out, skipped);
                    }
                }
            }
            default -> skipped.add(title);
        }
    }

    private static ExportDoc.Section table(String id, String title, PanelData.Table t) {
        List<List<Object>> rows = new ArrayList<>();
        walk(t.rows() == null ? List.of() : t.rows(), rows);
        if (t.total() != null) {
            rows.add(cells(t.total()));
        }
        return new ExportDoc.Section(id, title, ExportDoc.TABLE, t.columns() == null ? List.of() : t.columns(), rows, t.more(), List.of());
    }

    private static void walk(List<PanelData.Row> rows, List<List<Object>> out) {
        for (PanelData.Row r : rows) {
            out.add(cells(r));
            if (r.children() != null) {
                walk(r.children(), out);
            }
        }
    }

    private static List<Object> cells(PanelData.Row r) {
        List<Object> out = new ArrayList<>();
        for (ViewModel.Cell c : r.cells()) {
            out.add(typed(c.text()));
        }
        return out;
    }

    private static ExportDoc.Section pivot(String id, String title, PanelData.Pivot pv) {
        List<String> cols = new ArrayList<>();
        cols.add(pv.by() == null ? "" : pv.by());
        cols.addAll(pv.columns());
        boolean totals = pv.totals() != null && !pv.totals().isEmpty();
        if (totals) {
            cols.add("Total");
        }
        List<List<Object>> rows = new ArrayList<>();
        for (PanelData.PivotRow r : pv.rows()) {
            List<Object> row = new ArrayList<>();
            row.add(r.label());
            for (int i = 0; i < pv.columns().size(); i++) {
                row.add(r.values() != null && i < r.values().size() && r.values().get(i) != null ? r.values().get(i)
                        : r.cells() != null && i < r.cells().size() ? typed(r.cells().get(i).text()) : null);
            }
            if (totals) {
                row.add(r.total() == null ? null : typed(r.total().text()));
            }
            rows.add(row);
        }
        if (totals) {
            List<Object> row = new ArrayList<>();
            row.add("Total");
            pv.totals().forEach(c -> row.add(typed(c.text())));
            rows.add(row);
        }
        return new ExportDoc.Section(id, title, ExportDoc.TABLE, cols, rows, null, List.of());
    }

    /** Text as a view shows it ({@code -412,580}, {@code 4.25%}) as a number a spreadsheet reads as a number (-412580, 0.0425); anything else stays text. */
    static Object typed(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = SHOWN.matcher(text.strip());
        if (!m.matches()) {
            return text;
        }
        boolean neg = !m.group(1).isEmpty() && !m.group(1).equals("+");
        String digits = m.group(2).replace(",", "");
        boolean pct = !m.group(4).isEmpty();
        if (m.group(3) == null && !pct && digits.length() > 1 && digits.charAt(0) == '0') {
            return text;                                          // an identifier such as 00123 keeps its zeros
        }
        if (m.group(3) == null && !pct && digits.length() < 18) {
            long v = Long.parseLong(digits);
            return neg ? -v : v;
        }
        double d = Double.parseDouble(digits + (m.group(3) == null ? "" : m.group(3)));
        d = pct ? d / 100 : d;
        return neg ? -d : d;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
