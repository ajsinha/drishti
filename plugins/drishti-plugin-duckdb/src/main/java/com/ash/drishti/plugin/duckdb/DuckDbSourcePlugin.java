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
package com.ash.drishti.plugin.duckdb;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Reads a data domain from a DuckDB file laid out by {@link DuckDbLoader} ({@link DuckDbLayout}): {@code
 * <domain>.entities}, a row per entity per business date with the pack's promoted fields as columns. The file is opened
 * read-only and shared by every connector on it ({@link DuckDbFile}); a load writes a new file and renames it over the
 * old one, and the connector reopens it within {@code refresh-seconds}.
 *
 * <p>Settings: {@code path} (the database file), {@code table} ({@code trading.entities}), {@code mode.<kind>}
 * ({@code snapshot}, the default, or {@code effective}), {@code lookback-days} (10), {@code layout.<kind>.columns},
 * {@code kinds}, {@code pool-size} (4), {@code refresh-seconds} (10), {@code scan-threads} (4),
 * {@code columns-cache-mb} (1024), {@code columns-seconds} (300), {@code max-load-rows} (200000), {@code reverse-index}
 * (true), {@code memory-limit} (1GB), {@code threads} (DuckDB's default: every core) and {@code source-name}
 * ({@code duckdb}). See {@code docs/connectors/DUCKDB_CONNECTOR.md}.
 */
public final class DuckDbSourcePlugin implements SourcePlugin {

    private final Map<String, String> modes = new LinkedHashMap<>();
    private DuckDbFile file;
    private DuckDbCatalog catalog;
    private String table;
    private String sourceName = "duckdb";
    private SourceContext context;
    private int poolSize;
    private int maxLoadRows = 200_000;
    private int lookbackDays = 10;
    private boolean reverseIndex = true;
    private volatile List<String> kinds = List.of();
    private volatile String lastError;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("duckdb", "1.0", new HashSet<>(kinds), new SourceCapabilities(false, reverseIndex, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        String path = ctx.setting("path", "");
        String t = ctx.setting("table", "");
        if (path.isBlank() || t.isBlank()) {
            throw new PluginNotConfigured("duckdb needs settings.path (the database file) and settings.table (schema.entities)");
        }
        this.table = DuckDbLayout.checked(t);
        if (!table.contains(".")) {
            throw new PluginNotConfigured("duckdb settings.table is schema.entities, not " + t);
        }
        this.sourceName = ctx.setting("source-name", "duckdb");
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("mode.")) {
                modes.put(k.substring(5), v);
            }
        });
        Map<String, List<String>> promoted = new LinkedHashMap<>();
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("layout.") && k.endsWith(".columns")) {
                promoted.put(k.substring(7, k.length() - 8), Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            }
        });
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
        this.reverseIndex = Boolean.parseBoolean(ctx.setting("reverse-index", "true"));
        this.poolSize = Integer.parseInt(ctx.setting("pool-size", "4"));
        this.file = DuckDbFile.attach(Path.of(path), ctx.setting("memory-limit", "1GB"), Integer.parseInt(ctx.setting("threads", "0")), poolSize);
        this.catalog = new DuckDbCatalog(file, table.substring(0, table.indexOf('.')), sourceName, k -> "effective".equals(modes.get(k)), lookbackDays,
                promoted, Integer.parseInt(ctx.setting("scan-threads", "4")), Long.parseLong(ctx.setting("columns-cache-mb", "1024")),
                Duration.ofSeconds(Long.parseLong(ctx.setting("columns-seconds", "300"))));
        String configured = ctx.setting("kinds", "");
        List<String> fixed = configured.isBlank() ? List.of() : Arrays.stream(configured.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        if (!fixed.isEmpty()) {
            kinds = fixed;
        }
        // the file opened (or reopened after a load), its dates, the newest day's ids and columns; a stat when nothing changed
        Runnable refresh = () -> {
            if (catalog.refresh(fixed) && fixed.isEmpty()) {
                kinds = List.copyOf(catalog.kinds());
            }
        };
        refresh.run();
        long every = Long.parseLong(ctx.setting("refresh-seconds", "10"));
        if (ctx.scheduler() != null) {
            ctx.scheduler().scheduleWithFixedDelay(refresh, every, every, TimeUnit.SECONDS);
        }
    }

    private boolean effective(String kind) {
        return "effective".equals(modes.get(kind));
    }

    private <T> T with(DuckDbFile.Work<T> work) throws Exception {
        try {
            T out = file.with(work);
            lastError = null;
            return out;
        } catch (SQLException e) {
            lastError = e.getMessage();
            throw e;
        }
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        if (!kinds.contains(ref.kind())) {
            return Optional.empty();
        }
        Optional<Object[]> hit;
        if (!effective(ref.kind())) {
            // the snapshot date from memory, then one row: the zone maps of (kind, business_date, id) leave one row group to read
            Optional<LocalDate> day = catalog.snapshotDate(ref.kind(), asOf.businessDate());
            if (day.isEmpty()) {
                return Optional.empty();
            }
            hit = with(c -> one(c, "SELECT doc, CAST(business_date AS VARCHAR) FROM " + catalog.table()
                    + " WHERE kind = ? AND business_date = CAST(? AS DATE) AND id = ?", ref.kind(), day.get().toString(), ref.id()));
        } else {
            LocalDate on = asOf.businessDate() == null ? LocalDate.of(9999, 12, 31) : asOf.businessDate();
            hit = with(c -> one(c, "SELECT doc, CAST(business_date AS VARCHAR) FROM " + catalog.table()
                    + " WHERE kind = ? AND business_date <= CAST(? AS DATE) AND id = ? ORDER BY business_date DESC LIMIT 1", ref.kind(), on.toString(), ref.id()));
        }
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        LocalDate date = LocalDate.parse((String) hit.get()[1]);
        DataNode d = context.parseJson(new ByteArrayInputStream(((String) hit.get()[0]).getBytes(StandardCharsets.UTF_8)));
        return Optional.of(new EntityDocument(ref, d, new Provenance(sourceName, date.toEpochDay(), Instant.now(), false, date)));
    }

    private static Optional<Object[]> one(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Object[] {rs.getString(1), rs.getString(2)}) : Optional.empty();
            }
        }
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        if (!reverseIndex) {
            return List.of();
        }
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? kinds : List.of(kind)) {
            if (!kinds.contains(k)) {
                continue;                                      // a kind this file does not hold: no query
            }
            try {
                // promoted link columns (nettingSet, book, counterparty.id …) of the day: no document is read
                Optional<Set<String>> fromColumns = catalog.referrers(k, target.id(), asOf.businessDate());
                Collection<String> ids = fromColumns.isPresent() ? fromColumns.get() : with(c -> referrersFromDocuments(c, k, target.id(), asOf.businessDate()));
                ids.forEach(i -> out.add(EntityRef.of(k, i)));
            } catch (Exception e) {
                // no referrers from here, not an error page
            }
        }
        return out;
    }

    /**
     * Entities of a kind whose document holds {@code target} as a string value anywhere, reading at most
     * {@code max-load-rows} documents (a kind of millions a day is answered from its promoted link columns instead).
     */
    private List<String> referrersFromDocuments(Connection c, String kind, String target, LocalDate asked) throws SQLException {
        String rows;
        List<String> params = new ArrayList<>();
        params.add(kind);
        if (effective(kind)) {
            LocalDate on = asked == null ? LocalDate.of(9999, 12, 31) : asked;
            rows = "SELECT id, arg_max(doc, business_date) AS doc FROM " + catalog.table() + " WHERE kind = ? AND business_date <= CAST(? AS DATE) GROUP BY id";
            params.add(on.toString());
        } else {
            Optional<LocalDate> day = catalog.snapshotDate(kind, asked);
            if (day.isEmpty()) {
                return List.of();
            }
            rows = "SELECT id, doc FROM " + catalog.table() + " WHERE kind = ? AND business_date = CAST(? AS DATE)";
            params.add(day.get().toString());
        }
        params.add("\"" + target.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM (" + rows + " LIMIT " + maxLoadRows + ") WHERE contains(doc, ?) ORDER BY id")) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return catalog == null ? List.of() : catalog.search(kind, text, limit);   // in memory: the newest day's ids
    }

    @Override
    public Set<String> columnar(String kind) {
        return catalog == null ? Set.of() : catalog.columnar(kind);
    }

    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
        return catalog == null ? Optional.empty() : catalog.columns(kind, paths, asOf.businessDate());
    }

    @Override
    public Instant lastUpdate() {
        return catalog == null ? null : catalog.loadedAt();
    }

    @Override
    public Map<String, Object> cacheStats() {
        return catalog == null ? Map.of() : catalog.stats();
    }

    @Override
    public void purgeCaches() {
        if (catalog != null) {
            catalog.clear();
        }
    }

    @Override
    public String health() {
        if (file == null) {
            return "DOWN: not started";
        }
        if (file.generation() == 0) {
            return "DOWN: " + (file.problem() != null ? file.problem() : "no DuckDB file at " + file.path());
        }
        String e = lastError;
        if (e != null) {
            return "DOWN: " + e;
        }
        if (catalog.problem() != null) {
            return "UP (" + catalog.problem() + ")";
        }
        if (file.problem() != null) {
            return "UP (" + file.problem() + ")";
        }
        List<String> notLaidOut = catalog.notLaidOut();
        return notLaidOut.isEmpty() ? "UP" : "UP (not laid out as the pack declares: " + String.join(", ", notLaidOut) + "; searches read documents)";
    }

    @Override
    public void close() {
        if (file != null) {
            file.detach(poolSize);
            file = null;
        }
    }
}
