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
package com.ash.drishti.tables.materialize;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.preview.Preview;
import com.ash.drishti.tables.preview.RowEvaluator;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The layer's on-load materialisation (docs/architecture/ARCHITECTURE.md §28 3.4): a table's rows for a business date written as Parquet under
 * {@link StoreLayout} ({@code <store>/<domain>/<name>/v<major>/<date>/part-*.parquet}), each build a file of its own moved in with
 * {@link FileSwap} (a query reading the previous file goes on; a replaced file is swept later). The rows are the table's own definition
 * over its source's documents for the date ({@link Source}), as a preview computes them; DuckDB writes the Parquet. A cache: rebuilt at
 * will, never the truth, never written into a lake's own tables.
 */
public final class ParquetOnLoad implements Materialization.OnLoad {

    /** The documents of a table's source for a business date. */
    @FunctionalInterface
    public interface Source {
        List<Preview.Doc> documents(TableDef t, LocalDate date);
    }

    private final StoreLayout layout;
    private final Source source;
    private final RowEvaluator eval;
    private final Clock clock;

    public ParquetOnLoad(StoreLayout layout, Source source, RowEvaluator eval, Clock clock) {
        this.layout = layout;
        this.source = source;
        this.eval = eval;
        this.clock = clock;
    }

    @Override
    public Optional<Materialization.Partition> current(TableDef t, LocalDate date) {
        Path dir = layout.partition(t, date);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            Optional<Path> newest = files.filter(p -> p.getFileName().toString().matches("part-.*\\.parquet"))
                    .max(Comparator.comparing(p -> p.toFile().lastModified()));
            return newest.map(p -> new Materialization.Partition(t.name(), t.major(), date, p, count(p), Instant.ofEpochMilli(p.toFile().lastModified()), null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void loaded(TableDef t, LocalDate date) {
        build(t, date);                                                     // the server runs it on its build pool; here, in the caller's thread
    }

    @Override
    public Materialization.Partition build(TableDef t, LocalDate date) {
        List<Preview.Doc> docs = source.documents(t, date);
        List<DataNode> rows = Preview.objects(Preview.rows(t, docs, Preview.MAX_ROWS, eval));
        Path target = layout.newFile(t, date);
        try {
            Files.createDirectories(target.getParent());
            Path json = target.resolveSibling(target.getFileName() + ".jsonl");
            JsonCodec codec = new JsonCodec();
            try (BufferedWriter w = Files.newBufferedWriter(json, StandardCharsets.UTF_8)) {
                for (DataNode r : rows) {
                    w.write(codec.toJson(r));
                    w.newLine();
                }
            }
            Path tmp = FileSwap.temp(target);
            try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); Statement s = db.createStatement()) {
                String from = rows.isEmpty() ? "(SELECT NULL AS id WHERE false)" : "read_json_auto('" + sql(json) + "', format = 'newline_delimited')";
                s.execute("COPY (SELECT * FROM " + from + ") TO '" + sql(tmp) + "' (FORMAT PARQUET)");
            } finally {
                Files.deleteIfExists(json);
            }
            FileSwap.replace(tmp, target);
            FileSwap.sweep(target.getParent(), target.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("the Parquet of " + t.name() + " for " + date + " was not written: " + e.getMessage(), e);
        }
        return new Materialization.Partition(t.name(), t.major(), date, target, rows.size(), clock.instant(), null);
    }

    @Override
    public List<LocalDate> held(TableDef t) {
        Path dir = layout.tableDir(t);
        List<LocalDate> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> dates = Files.list(dir)) {
            dates.map(p -> p.getFileName().toString()).filter(n -> n.matches("\\d{4}-\\d{2}-\\d{2}")).sorted().forEach(n -> out.add(LocalDate.parse(n)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static long count(Path parquet) {
        try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); Statement s = db.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*) FROM read_parquet('" + sql(parquet) + "')")) {
            return r.next() ? r.getLong(1) : 0;
        } catch (SQLException e) {
            return -1;
        }
    }

    private static String sql(Path p) {
        return p.toAbsolutePath().toString().replace("'", "''");
    }
}
