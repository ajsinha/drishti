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
package com.ash.drishti.server.tables;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.data.DataSelection;
import com.ash.drishti.server.data.DataViewService;
import com.ash.drishti.server.edition.EditionCommands;
import com.ash.drishti.engine.ViewerContext;
import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.answer.ModelAnswers;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.catalog.ItemStore;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.preview.Preview;
import com.ash.drishti.tables.read.SourceRows;
import com.ash.drishti.tables.read.TableReads;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's virtual tables (VIRTUAL_TABLES.md section 10; TABLES_GUIDE.md section 7a): the catalogue of {@code drishti.tables.root},
 * read again when a file under it changes, and a read per request for one person ({@link #reads}) whose source is the kind read through its
 * connector as the Data view reads it ({@link DataViewService#walk}): the person's entitlement to the kind, the edition's kinds, and their
 * masks on every document. Nothing here calls a SQL engine: consumers stage the rows through their own executor (the pivot's), V14.
 *
 * <p>A table named like a stored table (a kind or a lake table the Data view lists) is refused at load and never read (V12).
 */
public final class TablesService {

    private static final Logger LOG = LoggerFactory.getLogger(TablesService.class);

    private final Path root;
    private final DataViewService data;
    private final com.ash.drishti.engine.source.SourceRouter router;
    private final EditionCommands edition;
    private final java.util.function.BooleanSupplier enforce;
    private final long maxRows;
    private final long scanWarnRows;
    private final Clock clock;
    private volatile Loaded loaded = new Loaded("", Catalogue.of(List.of(), List.of()), List.of(), Set.of());

    /** The catalogue as last read, with the stamp of the files it was read from and the names refused. */
    private record Loaded(String stamp, Catalogue catalogue, List<Problem> problems, Set<String> refused) {}

    public TablesService(Path root, DataViewService data, com.ash.drishti.engine.source.SourceRouter router,
            EditionCommands edition, java.util.function.BooleanSupplier enforce, long maxRows, long scanWarnRows, Clock clock) {
        this.root = root;
        this.data = data;
        this.router = router;
        this.edition = edition;
        this.enforce = enforce;
        this.maxRows = maxRows;
        this.scanWarnRows = scanWarnRows;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** The catalogue, read again when a definition under the root changed (added, edited, removed). */
    public Catalogue catalogue() {
        return current().catalogue();
    }

    /** What the last read of the catalogue found: each file's and definition's problems, and the name clashes. */
    public List<Problem> problems() {
        return current().problems();
    }

    /** The rows above which an unfiltered side of a join across connectors is warned about. */
    public long scanWarnRows() {
        return scanWarnRows;
    }

    /** The stored tables' names a virtual table may not take: every kind and lake table the Data view lists. */
    public Set<String> storedNames() {
        Set<String> out = new LinkedHashSet<>();
        for (DataViewService.Dataset d : data.datasets()) {
            out.add(d.kind());
            if (d.table() != null && !d.table().isBlank()) {
                out.add(d.table());
            }
        }
        return out;
    }

    /** The tables {@code p} may read: their names and titles, in the catalogue's order (a table whose source they may not open is left out). */
    public Map<String, String> visible(ViewerContext who) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Loaded l = current();
        for (TableDef t : l.catalogue().tables()) {
            if (l.refused().contains(t.name())) {
                continue;
            }
            String kind = rootKind(l.catalogue(), t);
            if (kind != null && who.mayOpen().test(kind) && edition.kindShown(kind)) {
                Object title = t.about().get("title");
                out.put(t.name(), title == null ? t.name() : String.valueOf(title));
            }
        }
        return out;
    }

    /** The reads of one request for {@code p}: one per pivot view, Sutra page or Flight call, so a source is read once per date (V7). */
    public TableReads reads(ViewerContext who) {
        Loaded l = current();
        TableReads r = new TableReads(l.catalogue(), new KindRows(who), null, maxRows, clock);
        return enforce.getAsBoolean() ? r : r.rulesOff();
    }

    /**
     * The version of what a read of {@code table} for {@code p} on {@code date} would give, without reading it: the catalogue's files, the
     * table, the business date, the source kind's data version, and who reads (user, roles, attributes, raw, rules on or off). Null when it
     * cannot be known without reading (a source that keeps no data version, a source that is not a kind): the caller then reads. Two reads
     * with one version give the same rows, so a consumer may keep what it staged (the pivot's per-view cache).
     */
    public String version(ViewerContext who, String table, String date) {
        Loaded l = current();
        String name = l.catalogue().renamed(table) != null ? l.catalogue().renamed(table) : table;
        TableDef t = l.catalogue().table(name).orElse(null);
        String kind = t == null || l.refused().contains(name) || router == null ? null : rootKind(l.catalogue(), t);
        if (kind == null) {
            return null;
        }
        LocalDate day;
        try {
            day = reads(who).day(name, date);
        } catch (ModelAnswers.Refused e) {
            return null;
        }
        java.util.Optional<String> dv;
        try {
            dv = router.dataVersion(kind, data.dataset(kind).dated() ? com.ash.drishti.api.AsOf.of(day) : com.ash.drishti.api.AsOf.LATEST);
        } catch (RuntimeException e) {
            return null;
        }
        if (dv.isEmpty()) {
            return null;
        }
        if (!who.named()) {
            return null;
        }
        return l.stamp() + "|" + name + "|" + day + "|" + dv.get() + "|" + who.user() + "|" + new java.util.TreeSet<>(who.roles()) + "|"
                + who.attributes() + "|" + who.raw() + "|" + enforce.getAsBoolean();
    }

    /** Reads {@code table} for {@code p} through {@code reads}; the layer's refusals become the server's codes. */
    public TableReads.Read read(TableReads reads, ViewerContext who, String table, String date, Instant knownAt, String where) {
        Loaded l = current();
        String name = l.catalogue().renamed(table) != null ? l.catalogue().renamed(table) : table;
        if (l.refused().contains(name)) {
            throw new DrishtiException(ErrorCode.TABLE_NAME_CLASH, name + " is a stored table's name: the virtual table is not served (choose another"
                    + " <domain>.<name>)");
        }
        try {
            if (!who.named()) {
                throw new DrishtiException(ErrorCode.TABLE_READ, name + " is read only for a signed-in person: its rules are theirs");
            }
            return reads.read(name, date, knownAt, reader(who), where);
        } catch (ModelAnswers.Refused e) {
            throw refused(e);
        }
    }

    /** What a table's row rules know of the viewer. */
    static ModelAnswers.Reader reader(ViewerContext who) {
        return new ModelAnswers.Reader(who.roles(), who.attributes(), who.raw());
    }

    /** A refusal of the layer as the server's error: its code kept. */
    public static DrishtiException refused(ModelAnswers.Refused e) {
        ErrorCode code = switch (e.code()) {
            case TableCodes.REFERENCE -> ErrorCode.TABLE_NOT_FOUND;
            case TableCodes.NAME_CLASH -> ErrorCode.TABLE_NAME_CLASH;
            default -> ErrorCode.TABLE_READ;
        };
        return new DrishtiException(code, e.getMessage());
    }

    /** The kind a table is rooted in (through tables over tables), or null for a source that is not a kind. */
    static String rootKind(Catalogue c, TableDef t) {
        TableDef at = t;
        for (int depth = 0; at != null && depth < 10; depth++) {
            if (at.from() == null) {
                return null;
            }
            if (at.from().form() == TableDef.Source.Form.KIND) {
                return at.from().kind();
            }
            if (at.from().form() != TableDef.Source.Form.TABLE) {
                return null;
            }
            at = c.table(at.from().table()).orElse(null);
        }
        return null;
    }

    // ---- the catalogue ---------------------------------------------------------------------------------------------------------

    private Loaded current() {
        String stamp = stamp();
        Loaded l = loaded;
        if (stamp.equals(l.stamp())) {
            return l;
        }
        synchronized (this) {
            if (stamp.equals(loaded.stamp())) {
                return loaded;
            }
            Catalogue c;
            List<Problem> problems = new ArrayList<>();
            try {
                c = Catalogue.load(root, ItemStore.NONE);
                problems.addAll(c.check());
            } catch (IOException | RuntimeException e) {
                LOG.warn("virtual tables under {} could not be read: {}", root, e.getMessage());
                c = Catalogue.of(List.of(), List.of());
            }
            Set<String> refused = new LinkedHashSet<>();
            for (Problem p : c.clashes(storedNames())) {
                problems.add(p);
                refused.add(p.message().substring(1, p.message().indexOf('\'', 1)));
            }
            problems.stream().filter(Problem::isError).forEach(p -> LOG.warn("virtual tables: {}", p.line()));
            loaded = new Loaded(stamp, c, List.copyOf(problems), Set.copyOf(refused));
            return loaded;
        }
    }

    /** The files' names, sizes and times, so an edit, an addition or a removal reads the catalogue again. */
    private String stamp() {
        if (root == null || !Files.isDirectory(root)) {
            return "none";
        }
        StringBuilder b = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root, 2)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                b.append(f).append(':').append(Files.size(f)).append(':').append(Files.getLastModifiedTime(f).toMillis()).append(';');
            }
        } catch (IOException e) {
            return "unreadable";
        }
        return Integer.toHexString(b.toString().hashCode()) + ":" + b.length();
    }

    // ---- the source: a kind as the person may see it ------------------------------------------------------------------------

    /** A kind read through its connector for one person (their entitlement, the edition's kinds, their masks on every document). */
    final class KindRows implements SourceRows {
        private final ViewerContext who;

        KindRows(ViewerContext who) {
            this.who = who;
        }

        private DataViewService.Dataset dataset(TableDef.Source s) {
            if (s.form() != TableDef.Source.Form.KIND) {
                throw new ModelAnswers.Refused(TableCodes.READ, "a lake table is read through the kind its pack serves here: write from: { kind: ... }");
            }
            if (!who.mayOpen().test(s.kind())) {
                throw new ModelAnswers.Refused(TableCodes.READ, "no access to " + s.kind());
            }
            edition.requireKind(s.kind());
            return data.dataset(s.kind());
        }

        @Override
        public List<LocalDate> dates(TableDef.Source s) {
            DataViewService.Dataset d = dataset(s);
            return d.dated() ? data.dates(d.kind()) : List.of();
        }

        @Override
        public void read(TableDef.Source s, LocalDate date, Instant knownAt, Predicate<Preview.Doc> sink) {
            DataViewService.Dataset d = dataset(s);
            List<String> out = new ArrayList<>();
            if (d.key() != null) {
                out.add(d.key());
            }
            out.add(DataViewService.DOCUMENT);
            java.util.function.UnaryOperator<DataNode> redactor = who.raw() ? null : who.redact();
            String day = date == null ? null : date.toString();
            try {
                data.walk(d, date, DataSelection.parse(List.of(), List.of()), out, redactor, false, 0, row -> {
                    Object id = d.key() == null ? null : row.get(d.key());
                    DataNode doc = DataNode.of(row.get(DataViewService.DOCUMENT));
                    return sink.test(new Preview.Doc(id == null ? null : String.valueOf(id), day, doc));
                });
            } catch (IOException e) {
                throw new DrishtiException(ErrorCode.SOURCE_FAILED, d.connector() + " could not read " + d.kind() + ": " + e.getMessage());
            }
        }

        @Override
        public long estimate(TableDef.Source s, LocalDate date) {
            return -1;
        }

        @Override
        public String connector(TableDef.Source s) {
            if (s.connector() != null) {
                return s.connector();
            }
            try {
                return s.form() == TableDef.Source.Form.KIND ? data.dataset(s.kind()).connector() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
