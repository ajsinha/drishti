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
package com.ash.drishti.plugin.jdbc;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Reads entities with one parameterised query per kind. The JDBC driver is supplied by the site (on the
 * class path or in the plugin directory).
 *
 * <p>Settings: {@code url}, {@code user}, {@code password} (from the environment), {@code source-name}
 * (default {@code jdbc}), {@code pool-size} (default 4), and {@code query.<kind>}: SQL with one {@code ?}
 * for the id, or named parameters {@code :id} and {@code :asOf} (the business date, a SQL {@code DATE}). A query
 * that uses {@code :asOf} makes the source dated, for example
 * {@code ... WHERE trade_id = :id AND business_date = (SELECT MAX(business_date) FROM trades
 * WHERE trade_id = :id AND business_date <= :asOf)}. The first row becomes the document: each column is a field,
 * except a column named {@code json}, whose JSON text is used as the whole document. A column named
 * {@code generation}, if present, is the version; {@code business_date}, if present, is the date the row is for.
 * Connections are pooled in a small bounded queue of slots, each opened on first use and reopened whenever it is
 * found broken, so the connector starts while the database is down and recovers by itself when it comes back.
 *
 * <p><b>Table mode</b> ({@code table: reference.entities}): every kind of a data domain in one PostgreSQL table of
 * {@code (kind, id, business_date, doc jsonb)} rows, dated, with search and reverse lookups; {@code mode.<kind>}
 * is {@code snapshot} (default) or {@code effective}. See {@link EntityTable} and {@code tools/samplegen/pgload.py}.
 */
public final class JdbcSourcePlugin implements SourcePlugin {

    private static final java.util.regex.Pattern NAMED = java.util.regex.Pattern.compile(":(id|asOf)\\b");
    private final Map<String, String> queries = new LinkedHashMap<>();
    private EntityTable table;
    private volatile java.util.List<String> tableKinds = java.util.List.of();   // discovered later when the database starts after us
    private final Map<String, java.util.List<String>> params = new LinkedHashMap<>();
    /** A pool slot: its connection is opened lazily and replaced when broken; only its borrower touches it. */
    private static final class Slot {
        Connection connection;
    }

    private BlockingQueue<Slot> pool;
    private volatile String lastError;
    private SourceContext context;
    private String url;
    private String user;
    private String password;
    private String sourceName;

    @Override
    public PluginManifest manifest() {
        if (table != null) {
            return new PluginManifest("jdbc", "1.0", new java.util.HashSet<>(tableKinds), new SourceCapabilities(false, true, true, true));
        }
        boolean dated = params.values().stream().anyMatch(l -> l.contains("asOf"));
        return new PluginManifest("jdbc", "1.0", queries.keySet(), new SourceCapabilities(false, false, false, dated));
    }

    /**
     * Runs {@code work} on a pooled connection, (re)connecting the slot when it has none or its connection is broken.
     * A failure to connect fails this call only: the slot goes back empty and the next call tries again.
     */
    private <T> T withConnection(SqlWork<T> work) throws Exception {
        Slot s = pool.poll(5, TimeUnit.SECONDS);
        if (s == null) {
            throw new SQLException("no free connection in " + sourceName + " pool");
        }
        try {
            if (s.connection == null || !s.connection.isValid(1)) {
                if (s.connection != null) {
                    try {
                        s.connection.close();
                    } catch (SQLException ignored) {
                        // already broken
                    }
                    s.connection = null;
                }
                try {
                    s.connection = DriverManager.getConnection(url, user, password);
                } catch (SQLException e) {
                    lastError = e.getMessage();
                    throw e;
                }
            }
            T out = work.run(s.connection);
            lastError = null;
            return out;
        } catch (SQLException e) {
            if (s.connection != null && !s.connection.isValid(1)) {   // the connection died mid-call: reconnect next time
                try {
                    s.connection.close();
                } catch (SQLException ignored) {
                    // already broken
                }
                s.connection = null;
            }
            lastError = e.getMessage();
            throw e;
        } finally {
            pool.offer(s);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection c) throws Exception;
    }

    @Override
    public java.util.List<com.ash.drishti.api.EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, com.ash.drishti.api.AsOf.LATEST);
    }

    @Override
    public java.util.List<com.ash.drishti.api.EntityRef> reverse(EntityRef target, String kind, com.ash.drishti.api.AsOf asOf) {
        if (table == null) {
            return java.util.List.of();
        }
        try {
            java.util.List<EntityRef> out = new java.util.ArrayList<>();
            for (String k : kind == null ? tableKinds : java.util.List.of(kind)) {
                withConnection(c -> table.reverse(c, k, target.id(), asOf.businessDate())).forEach(i -> out.add(EntityRef.of(k, i)));
            }
            return out;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    @Override
    public java.util.List<com.ash.drishti.api.EntityHit> search(String kind, String text, int limit) {
        if (table == null) {
            return java.util.List.of();
        }
        try {
            return withConnection(c -> table.search(c, kind, text, limit)).stream()
                    .map(r -> new com.ash.drishti.api.EntityHit(EntityRef.of(r[0], r[1]), r[1], r[0] + " · " + sourceName)).toList();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    @Override
    public void start(SourceContext ctx) throws SQLException {
        this.context = ctx;
        this.url = ctx.setting("url", "");
        if (url.isEmpty()) {
            throw new com.ash.drishti.api.PluginNotConfigured("jdbc needs settings.url");
        }
        for (String c : ctx.setting("json-columns", "").split(",")) {
            if (!c.isBlank()) {
                jsonColumns.add(c.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        this.user = ctx.setting("user", "");
        this.password = ctx.setting("password", "");
        this.sourceName = ctx.setting("source-name", "jdbc");
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("query.")) {
                java.util.List<String> names = new java.util.ArrayList<>();
                var m = NAMED.matcher(v);
                while (m.find()) {
                    names.add(m.group(1));
                }
                queries.put(k.substring(6), names.isEmpty() ? v : m.replaceAll("?"));
                params.put(k.substring(6), names.isEmpty() ? java.util.List.of("id") : names);
            }
        });
        int size = Integer.parseInt(ctx.setting("pool-size", "4"));
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            pool.add(new Slot());                    // connected on first use: the database may still be starting
        }
        String t = ctx.setting("table", "");
        if (!t.isBlank()) {
            Map<String, String> modes = new LinkedHashMap<>();
            ctx.settings().forEach((k, v) -> {
                if (k.startsWith("mode.")) {
                    modes.put(k.substring(5), v);
                }
            });
            table = new EntityTable(t, ctx.setting("kind-column", "kind"), ctx.setting("id-column", "id"),
                    ctx.setting("date-column", "business_date"), ctx.setting("doc-column", "doc"), modes,
                    Integer.parseInt(ctx.setting("lookback-days", "10")));
            String configured = ctx.setting("kinds", "");
            if (!configured.isBlank()) {
                tableKinds = java.util.Arrays.stream(configured.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
            } else if (!discoverKinds()) {
                // the database is not answering yet: keep asking in the background until it does
                java.util.concurrent.ScheduledFuture<?>[] retry = new java.util.concurrent.ScheduledFuture<?>[1];
                retry[0] = ctx.scheduler().scheduleWithFixedDelay(() -> {
                    if (discoverKinds() && retry[0] != null) {
                        retry[0].cancel(false);
                    }
                }, 10, 10, TimeUnit.SECONDS);
            }
        }
    }

    /** Lists the kinds the table holds; false (and nothing changes) when the database cannot answer yet. */
    private boolean discoverKinds() {
        try {
            tableKinds = java.util.List.copyOf(withConnection(table::kinds));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, com.ash.drishti.api.AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, com.ash.drishti.api.AsOf asOf) throws Exception {
        if (table != null) {
            if (!tableKinds.contains(ref.kind())) {
                return Optional.empty();
            }
            Optional<EntityTable.Hit> hit = withConnection(c -> table.fetch(c, ref.kind(), ref.id(), asOf.businessDate()));
            if (hit.isEmpty()) {
                return Optional.empty();
            }
            DataNode d = context.parseJson(new ByteArrayInputStream(hit.get().json().getBytes(StandardCharsets.UTF_8)));
            return Optional.of(new EntityDocument(ref, d, new Provenance(sourceName, hit.get().date().toEpochDay(), Instant.now(), false,
                    hit.get().date())));
        }
        String sql = queries.get(ref.kind());
        if (sql == null) {
            return Optional.empty();
        }
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                java.util.List<String> names = params.get(ref.kind());
                java.time.LocalDate date = asOf.businessDate() != null ? asOf.businessDate() : java.time.LocalDate.now();
                for (int i = 0; i < names.size(); i++) {
                    if ("asOf".equals(names.get(i))) {
                        ps.setObject(i + 1, java.sql.Date.valueOf(date));
                    } else {
                        ps.setString(i + 1, ref.id());
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(toDocument(ref, rs, names.contains("asOf") ? date : null));
                }
            }
        });
    }

    private final java.util.Set<String> jsonColumns = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private EntityDocument toDocument(EntityRef ref, ResultSet rs, java.time.LocalDate asked) throws Exception {
        java.time.LocalDate businessDate = asked;
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> fields = new LinkedHashMap<>();
        DataNode doc = null;
        long generation = System.currentTimeMillis();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = md.getColumnLabel(i);
            Object v = rs.getObject(i);
            if ("json".equalsIgnoreCase(name) && v != null) {
                doc = context.parseJson(new ByteArrayInputStream(v.toString().getBytes(StandardCharsets.UTF_8)));
            } else if ("generation".equalsIgnoreCase(name) && v instanceof Number n) {
                generation = n.longValue();
            } else if ("business_date".equalsIgnoreCase(name) && v instanceof java.sql.Date d) {
                businessDate = d.toLocalDate();
                fields.put("businessDate", businessDate.toString());
            } else if (v != null && isJson(md.getColumnTypeName(i), name)) {
                fields.put(camel(name), nested(v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v.toString()));  // nested, not text
            } else {
                fields.put(camel(name), plain(v));
            }
        }
        return new EntityDocument(ref, doc != null ? doc : DataNode.of(fields), new Provenance(sourceName, generation, Instant.now(), false, businessDate));
    }

    /** A json/jsonb column (PostgreSQL, MySQL), or one named in {@code json-columns} (JSON kept in a text column). */
    private boolean isJson(String typeName, String column) {
        if (typeName != null && (typeName.equalsIgnoreCase("json") || typeName.equalsIgnoreCase("jsonb"))) {
            return true;
        }
        return jsonColumns.contains(column.toLowerCase(java.util.Locale.ROOT));
    }

    /** Parsed JSON; a value that is not JSON stays as its text, so one bad cell never fails the document. */
    private Object nested(String text) {
        try {
            return context.parseJson(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return text;
        }
    }

    private static Object plain(Object v) {
        if (v instanceof BigDecimal b) {
            return b.scale() <= 0 ? (Object) b.longValue() : (Object) b.doubleValue();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant().toString();
        }
        return v instanceof Number || v instanceof Boolean || v == null ? v : v.toString();
    }

    /** {@code TRADE_ID} and {@code trade_id} become {@code tradeId}. */
    static String camel(String column) {
        if (!column.contains("_") && !column.equals(column.toUpperCase())) {
            return column;
        }
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : column.toLowerCase().toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }

    @Override
    public String health() {
        String e = lastError;
        return pool == null ? "DOWN: not started" : e == null ? "UP" : "DOWN: " + e + " (reconnecting)";
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.forEach(s -> {
                try {
                    if (s.connection != null) {
                        s.connection.close();
                    }
                } catch (SQLException ignored) {
                    // shutting down
                }
            });
        }
    }
}
