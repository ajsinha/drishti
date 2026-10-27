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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.collab.snapshot.SnapshotLayout;
import com.ash.drishti.server.collab.snapshot.SnapshotModel;
import com.ash.drishti.server.collab.snapshot.SnapshotPainter;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Exports a BI report, page or visual, or a Sutra view, as one reader sees it, to PDF, PNG, Excel, CSV or JSON Lines (and the table form that an email
 * body is made from). The numbers come from the same places as the screen, as that reader, so masks and row rules apply; the limits are
 * settings ({@code drishti.distribution.export.*}).
 */
public final class ContentExporter implements AutoCloseable {

    /** The formats. {@code HTML} is the inline email body (not served as a download). */
    public enum Format {
        PDF("application/pdf", "pdf"),
        PNG("image/png", "png"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        CSV("text/csv; charset=utf-8", "csv"),
        JSONL(JsonlWriter.MEDIA_TYPE + "; charset=utf-8", "jsonl"),
        HTML("text/html; charset=utf-8", "html");

        private final String type;
        private final String extension;

        Format(String type, String extension) {
            this.type = type;
            this.extension = extension;
        }

        public String contentType() {
            return type;
        }

        public String extension() {
            return extension;
        }

        public static Format of(String s) {
            try {
                return valueOf(s == null ? "" : s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new DrishtiException(ErrorCode.EXPORT_FAILED, "format is pdf, png, xlsx, csv or jsonl");
            }
        }
    }

    /**
     * What to export. {@code type} is {@code report} (id = the report's catalogue id; page and visual optional; {@code state} the report view state) or
     * {@code view} (id = {@code kind/entity id}; {@code view} the control values and column filters as query text).
     */
    public record Target(String type, String id, String page, String visual, JsonNode state, String date, String view) {

        public boolean isReport() {
            return "report".equals(type);
        }
    }

    /**
     * A made file, and the table form it came from (for the email body and for tests).
     *
     * @param pages the pages of a statement's PDF (2.6.0); 0 for any other file
     */
    public record Output(byte[] bytes, String contentType, String filename, int rows, ExportDoc doc, int pages) {

        /** A file that is not a statement. */
        public Output(byte[] bytes, String contentType, String filename, int rows, ExportDoc doc) {
            this(bytes, contentType, filename, rows, doc, 0);
        }

        public boolean statement() {
            return pages > 0;
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BiExporter bi;
    private final ViewExporter views;
    private final DistProperties.Export limits;
    private final Clock clock;
    private final ZoneId zone;
    private final String consoleUrl;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile StatementService statements;

    public ContentExporter(BiExporter bi, ViewExporter views, DistProperties.Export limits, Clock clock, ZoneId zone, String consoleUrl) {
        this.bi = bi;
        this.views = views;
        this.limits = limits;
        this.clock = clock;
        this.zone = zone;
        this.consoleUrl = consoleUrl == null ? "" : consoleUrl.replaceAll("/+$", "");
    }

    public DistProperties.Export limits() {
        return limits;
    }

    /** Gives the exporter the statements (2.6.0): an export or a subscription of a statement page is then made page-exact, as a statement. */
    public ContentExporter with(StatementService statements) {
        this.statements = statements;
        return this;
    }

    /** Throws when {@code who} cannot use the target at all (a report they cannot read, a view they may not open); nothing is rendered. */
    public void check(Target t, Principal who) {
        if (t.isReport()) {
            bi.check(who, t.id());
        } else {
            int slash = t.id() == null ? -1 : t.id().indexOf('/');
            if (slash < 1) {
                throw new DrishtiException(ErrorCode.EXPORT_FAILED, "a view is named kind/id");
            }
            views.check(who, t.id().substring(0, slash));
        }
    }

    /** Makes the file for {@code who}; the whole job is bounded by {@code export.timeout}. */
    public Output render(Target target, Format format, Principal who) {
        if (!limits.enabled()) {
            throw new DrishtiException(ErrorCode.EXPORT_FAILED, "exports are turned off on this server (drishti.distribution.export.enabled)");
        }
        Future<Output> job = executor.submit(() -> make(target, format, who));
        try {
            return job.get(limits.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            job.cancel(true);
            throw new DrishtiException(ErrorCode.EXPORT_FAILED, "the export took longer than " + limits.timeout().toSeconds() + " s (drishti.distribution.export.timeout)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.cancel(true);
            throw new DrishtiException(ErrorCode.EXPORT_FAILED, "the export was interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new DrishtiException(ErrorCode.EXPORT_FAILED, "the export failed: " + e.getCause(), e.getCause());
        }
    }

    private Output make(Target t, Format f, Principal who) throws IOException {
        String stamp = STAMP.format(clock.instant().atZone(zone));
        String footer = "Made for " + who.user() + " on " + stamp + " · masks and row rules of " + who.user() + " applied";
        ExportDoc doc;
        byte[] bytes;
        ViewExporter.Built view = null;
        StatementService st = statements;
        if (st != null && t.isReport() && f != Format.CSV && f != Format.JSONL && f != Format.PNG && st.isStatement(who, t)) {
            return statement(st, t, f, who);
        }
        if (t.isReport()) {
            doc = bi.build(who, t.id(), t.page(), t.visual(), t.state(), t.date());
        } else {
            int slash = t.id() == null ? -1 : t.id().indexOf('/');
            if (slash < 1 || slash == t.id().length() - 1) {
                throw new DrishtiException(ErrorCode.EXPORT_FAILED, "a view is named kind/id");
            }
            view = views.build(who, t.id().substring(0, slash), t.id().substring(slash + 1), t.date(), t.view());
            doc = view.doc();
        }
        bytes = switch (f) {
            case XLSX -> XlsxWriter.write(doc);
            case CSV -> CsvWriter.write(doc);
            case JSONL -> JsonlWriter.write(doc);           // one row a line, the CSV's column names as keys
            case HTML -> new byte[0];                       // the table form (Output.doc) is the result; the caller writes the HTML it wants
            case PDF -> pdf(doc, view, who, footer, stamp, t);
            case PNG -> png(doc, view, who, stamp, t);
        };
        if (bytes.length > limits.maxBytes()) {
            throw new DrishtiException(ErrorCode.EXPORT_FAILED, "the file is " + bytes.length + " bytes, over the limit of " + limits.maxBytes()
                    + " (drishti.distribution.export.max-bytes): export one page or one visual, or narrow the filters");
        }
        return new Output(bytes, f.contentType(), filename(doc.title(), view == null ? t.date() : view.asOf(), f), doc.rowCount(), doc);
    }

    /** A statement for one reader: page-exact PDF, Excel with the paper's print setup, or (an email body) its tables. */
    private Output statement(StatementService st, Target t, Format f, Principal who) {
        StatementService.Run run = st.run(who, t.id(), null, t.page(), t.state(), t.date());
        byte[] bytes = switch (f) {
            case PDF -> st.pdf(run);
            case XLSX -> st.xlsx(run);
            default -> new byte[0];
        };
        if (bytes.length > limits.maxBytes()) {
            throw new DrishtiException(ErrorCode.EXPORT_FAILED, "the statement is " + bytes.length + " bytes, over the limit of " + limits.maxBytes()
                    + " (drishti.distribution.export.max-bytes): narrow it with filters or parameters");
        }
        return new Output(bytes, f.contentType(), StatementService.filename(run, who, f), run.doc().rowCount(), run.doc(), run.pages().pages().size());
    }

    private byte[] pdf(ExportDoc doc, ViewExporter.Built view, Principal who, String footer, String stamp, Target t) {
        PdfWriter.Geometry g = PdfWriter.Geometry.of(limits.landscape(), limits.width());
        SnapshotModel model = view == null ? new DocLayout(g.width(), g.slice(), limits.pdfMaxRows(), limits.pdfChartRows(), 0).build(doc)
                : new SnapshotLayout(g.width(), 80_000, 80, limits.pdfMaxRows()).build(view.view(), null, watermark(view, who, stamp, t));
        return PdfWriter.write(model, g, doc.title(), doc.subtitle(), footer);
    }

    private byte[] png(ExportDoc doc, ViewExporter.Built view, Principal who, String stamp, Target t) throws IOException {
        SnapshotModel model = view == null ? new DocLayout(limits.width(), 0, limits.pdfMaxRows(), limits.pdfChartRows(), limits.pngMaxHeight()).build(doc)
                : new SnapshotLayout(limits.width(), limits.pngMaxHeight(), 40, limits.pdfMaxRows()).build(view.view(), null, watermark(view, who, stamp, t));
        return new SnapshotPainter().png(model);
    }

    private SnapshotLayout.Watermark watermark(ViewExporter.Built view, Principal who, String stamp, Target t) {
        ViewModel.Provenance pv = view.view().provenance();
        return new SnapshotLayout.Watermark(who.user(), "", stamp, view.asOf(), pv == null ? 0 : pv.generation(),
                consoleUrl + "/v/" + t.id() + (view.state().isEmpty() ? "" : "?" + view.state().text()), "Exported by");
    }

    /** {@code Daily risk · 2026-10-09.pdf}: the title and date, without characters a file name or a header cannot hold. */
    static String filename(String title, String asOf, Format f) {
        String base = (title == null || title.isBlank() ? "export" : title).replaceAll("[^\\p{L}\\p{N} ._-]+", "-").replaceAll("\\s+", " ").strip();
        base = base.length() > 80 ? base.substring(0, 80).strip() : base;
        return base + (asOf == null || asOf.isBlank() ? "" : " " + asOf.replaceAll("[^0-9a-zA-Z-]", "")) + "." + f.extension();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
