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
package com.ash.drishti.server.data;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.edition.Edition;
import com.ash.drishti.server.edition.EditionCommands;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Data view, {@code DATA <GO>} (docs/guides/DATA_GUIDE.md "The Data view", docs/guides/API_GUIDE.md): the datasets the caller may
 * open, one dataset's facts, a selection's row count, and the selected rows of a day as JSON Lines, gzip-compressed, streamed and never
 * held whole. Download only, never a viewer. The caller's entitlements decide the datasets ({@link Entitlements#mayOpen}: role, pack on,
 * command on) and the edition or lens the commands shown; a masked column is left out (and may not be filtered on); a dataset marked
 * internal only is refused where the edition says so ({@code internal-only-data: off}) or the site does
 * ({@code drishti.data-view.internal-only-download: false}). Every download is audited: who, the dataset, the date, the selection, the rows.
 */
@RestController
@RequestMapping("/api/v1/data")
public class DataViewController {

    /** The command word (an edition's {@code commands.words.DATA}). */
    public static final String WORD = "DATA";
    /** The edition feature that, off, refuses datasets marked internal only. */
    public static final String INTERNAL_FEATURE = "internal-only-data";

    private final DataViewService data;
    private final Entitlements entitlements;
    private final EditionCommands edition;
    private final AuditLog audit;
    private final boolean enabled;
    private final boolean internalDownloads;
    private final Duration countBudget;

    public DataViewController(DataViewService data, Entitlements entitlements, EditionCommands edition, AuditLog audit,
            @Value("${drishti.data-view.enabled:true}") boolean enabled, @Value("${drishti.data-view.internal-only-download:true}") boolean internalDownloads,
            @Value("${drishti.data-view.count-budget:PT60S}") Duration countBudget) {
        this.data = data;
        this.entitlements = entitlements;
        this.edition = edition;
        this.audit = audit;
        this.enabled = enabled;
        this.internalDownloads = internalDownloads;
        this.countBudget = countBudget;
    }

    /** {@code {internalOnlyRefused, datasets[{kind, pack, code, title, description, class, connector, format, table, dated, dates, first, last, internalOnly, refused}]}}. */
    @GetMapping
    public Map<String, Object> datasets(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requireOn();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataViewService.Dataset d : data.datasets()) {
            if (!visible(p, d.kind())) {
                continue;
            }
            Map<String, Object> m = summary(d);
            List<LocalDate> dates = d.dated() ? data.dates(d.kind()) : List.of();
            m.put("dates", dates.size());
            m.put("first", dates.isEmpty() ? null : dates.get(0).toString());
            m.put("last", dates.isEmpty() ? null : dates.get(dates.size() - 1).toString());
            out.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("internalOnlyRefused", !internalAllowed());
        r.put("datasets", out);
        return r;
    }

    /** One dataset: its facts, the columns the caller may see ({@code masked}: how many are left out) and every date it holds. */
    @GetMapping("/{kind}")
    public Map<String, Object> dataset(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String kind) {
        DataViewService.Dataset d = open(p, kind, false);
        Map<String, Object> m = summary(d);
        m.put("grain", d.grain());
        m.put("key", d.key());
        List<Map<String, Object>> cols = new ArrayList<>();
        int masked = 0;
        for (DataViewService.Column c : d.columns()) {
            if (entitlements.masked(p, c.name())) {
                masked++;
                continue;
            }
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.name());
            cm.put("type", c.type());
            cm.put("description", c.description());
            cols.add(cm);
        }
        m.put("columns", cols);
        m.put("masked", masked);
        m.put("dates", d.dated() && !m.get("refused").equals(Boolean.TRUE) ? data.dates(kind).stream().map(LocalDate::toString).toList() : List.of());
        return m;
    }

    /**
     * The selection's row count on the date: {@code {rows, total, complete, bytes, gzipBytes, words, sql, pushdown, read, date, columns}};
     * {@code total} is every row of the day, {@code complete} false when {@code drishti.data-view.count-budget} ran out first (the counts are then
     * "at least").
     */
    @GetMapping("/{kind}/count")
    public Map<String, Object> count(@RequestAttribute(Principal.ATTRIBUTE) Principal p, AsOf asOf, @PathVariable String kind,
            @RequestParam(required = false) String date, @RequestParam(required = false) List<String> columns,
            @RequestParam(required = false) List<String> where) throws IOException {
        DataViewService.Dataset d = open(p, kind, true);
        DataSelection sel = DataSelection.parse(columns, where);
        List<String> out = chosen(p, d, sel);
        LocalDate day = day(d, date);
        DataViewService.Sizer size = new DataViewService.Sizer();
        DataViewService.Walk w = data.walk(d, day, sel, out, entitlements.masks(p) ? entitlements.redactor(p) : null, false,
                DataViewService.deadline(countBudget), row -> {
                    size.add(DataViewService.line(row));
                    return true;
                });
        Map<String, Object> m = describe(d, sel, out, day);
        m.put("pushdown", !sel.pushable(java.util.Set.of()).isEmpty() && data.columnRead(d, sel, out, entitlements.masks(p)));
        m.put("rows", w.matched());
        m.put("total", w.seen());
        m.put("complete", !w.stopped());
        m.put("bytes", size.raw());
        m.put("gzipBytes", size.gzip());
        m.put("read", w.how());
        return m;
    }

    private com.ash.drishti.server.admission.AdmissionGate gate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void gate(com.ash.drishti.server.admission.AdmissionGate g) {
        this.gate = g;
    }

    /** The selected rows of the day as {@code <kind>_<date>.jsonl.gz}: one JSON object a line, the chosen columns in order; audited. */
    @GetMapping("/{kind}/rows")
    public void rows(@RequestAttribute(Principal.ATTRIBUTE) Principal p, AsOf asOf, @PathVariable String kind,
            @RequestParam(required = false) String date, @RequestParam(required = false) List<String> columns,
            @RequestParam(required = false) List<String> where, HttpServletResponse res) throws IOException {
        DataViewService.Dataset d = open(p, kind, true);
        DataSelection sel = DataSelection.parse(columns, where);
        List<String> out = chosen(p, d, sel);
        LocalDate day = day(d, date);
        String file = kind + "_" + (day == null ? "current" : day.toString()) + ".jsonl.gz";
        if (gate != null) {                                            // busy protection: a new download may wait (nothing written yet)
            gate.admit(com.ash.drishti.server.admission.Work.DOWNLOAD, p.user(), "Download " + kind + (day == null ? "" : " " + day));
        }
        res.setStatus(200);
        res.setContentType("application/gzip");
        res.setHeader("Content-Disposition", "attachment; filename=\"" + file + "\"");
        res.setHeader("X-Drishti-Selection", sel.words(kind, day == null ? null : day.toString(), out.size(), sel.columns().isEmpty())
                .replaceAll("[^\\x20-\\x7E]", "?"));
        long[] written = {0};
        String outcome = "incomplete";
        try (OutputStream gz = new GZIPOutputStream(res.getOutputStream(), 65_536)) {
            DataViewService.Walk w = data.walk(d, day, sel, out, entitlements.masks(p) ? entitlements.redactor(p) : null, true, 0, row -> {
                gz.write(DataViewService.line(row));
                written[0]++;
                return true;
            });
            outcome = w.pushed() ? "complete, filtered by the connector" : "complete";
        } finally {
            audit.record(p.user(), "data.download", kind, "date=" + (day == null ? "current" : day) + "; columns=" + String.join(",", out)
                    + "; where=" + String.join(" AND ", sel.lines().stream().map(DataSelection.Line::text).toList()) + "; rows=" + written[0]
                    + "; " + outcome);
        }
    }

    // ---- checks --------------------------------------------------------------------------------------------------------------------

    private void requireOn() {
        if (!enabled) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the Data view is switched off on this server (drishti.data-view.enabled)");
        }
        Edition e = edition.edition();
        if (!e.wordOn(WORD)) {
            throw new DrishtiException(ErrorCode.EDITION_COMMAND_HIDDEN, "DATA is not part of the edition '" + e.name() + "' (FUNC lists what you can use)");
        }
    }

    private boolean visible(Principal p, String kind) {
        return entitlements.mayOpen(p, kind) && edition.kindShown(kind);
    }

    private boolean internalAllowed() {
        return internalDownloads && !"off".equals(edition.edition().feature(INTERNAL_FEATURE, "on"));
    }

    /** The dataset when the caller may open it; {@code read}: also refuse one marked internal only where that is refused. */
    private DataViewService.Dataset open(Principal p, String kind, boolean read) {
        requireOn();
        DataViewService.Dataset d = data.dataset(kind);
        entitlements.requireOpen(p, kind);
        edition.requireKind(kind);
        if (read && d.internalOnly() && !internalAllowed()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, kind + " is marked internal only (its source's terms restrict reuse) and this "
                    + (edition.edition().active() ? "edition" : "server") + " does not download it");
        }
        return d;
    }

    /** The columns to write: those named (each one known and not masked), else every column the caller may see; filter fields checked too. */
    private List<String> chosen(Principal p, DataViewService.Dataset d, DataSelection sel) {
        List<String> known = d.columns().stream().map(DataViewService.Column::name).toList();
        List<String> out = new ArrayList<>();
        for (String c : sel.columns().isEmpty() ? known : sel.columns()) {
            known(d, known, c);
            if (!entitlements.masked(p, c)) {                  // a masked column is left out, even when a shared link names it
                out.add(c);
            }
        }
        for (String f : sel.filterFields()) {
            known(d, known, f);
            if (entitlements.masked(p, f)) {                   // filtering on it would tell its values
                throw new DrishtiException(ErrorCode.FORBIDDEN, f + " is masked for you: it cannot be filtered on");
            }
        }
        if (out.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no column of " + d.kind() + " is left to download");
        }
        return out;
    }

    private static void known(DataViewService.Dataset d, List<String> known, String name) {
        if (!known.isEmpty() && !known.contains(name)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, d.kind() + " has no column '" + name + "': its columns are "
                    + String.join(", ", known.size() > 40 ? known.subList(0, 40) : known) + (known.size() > 40 ? " ..." : ""));
        }
    }

    /** The day to read: the one asked (it must be one the dataset holds, when it can say), else its latest; null for an undated dataset. */
    private LocalDate day(DataViewService.Dataset d, String date) {
        if (!d.dated()) {
            return null;
        }
        List<LocalDate> held = data.dates(d.kind());
        if (date == null || date.isBlank()) {
            return held.isEmpty() ? null : held.get(held.size() - 1);
        }
        LocalDate day;
        try {
            day = LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "not a date: '" + date + "' (write 2026-09-30)");
        }
        if (!held.isEmpty() && !held.contains(day)) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, d.kind() + " holds no data on " + day + ": its dates run from " + held.get(0)
                    + " to " + held.get(held.size() - 1) + " (" + held.size() + " dates)");
        }
        return day;
    }

    private Map<String, Object> summary(DataViewService.Dataset d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", d.kind());
        m.put("pack", d.pack());
        m.put("code", d.code());
        m.put("title", d.title());
        m.put("description", d.description());
        m.put("class", d.dataClass());
        m.put("connector", d.connector());
        m.put("format", d.format());
        m.put("table", d.table());
        m.put("dated", d.dated());
        m.put("columnCount", d.columns().size());
        m.put("internalOnly", d.internalOnly());
        m.put("refused", d.internalOnly() && !internalAllowed());
        return m;
    }

    private Map<String, Object> describe(DataViewService.Dataset d, DataSelection sel, List<String> out, LocalDate day) {
        Map<String, Object> m = new LinkedHashMap<>();
        String date = day == null ? null : day.toString();
        m.put("date", date);
        m.put("columns", out);
        m.put("lines", sel.lines().stream().map(DataSelection.Line::text).toList());
        m.put("words", sel.words(d.kind(), date, out.size(), sel.columns().isEmpty()));
        m.put("sql", sel.sql(d.kind(), date, sel.columns().isEmpty() ? List.of() : out));
        return m;
    }
}
