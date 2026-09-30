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
 * Connections are pooled in a small bounded queue.
 */
public final class JdbcSourcePlugin implements SourcePlugin {

    private static final java.util.regex.Pattern NAMED = java.util.regex.Pattern.compile(":(id|asOf)\\b");
    private final Map<String, String> queries = new LinkedHashMap<>();
    private final Map<String, java.util.List<String>> params = new LinkedHashMap<>();
    private BlockingQueue<Connection> pool;
    private SourceContext context;
    private String url;
    private String user;
    private String password;
    private String sourceName;

    @Override
    public PluginManifest manifest() {
        boolean dated = params.values().stream().anyMatch(l -> l.contains("asOf"));
        return new PluginManifest("jdbc", "1.0", queries.keySet(), new SourceCapabilities(false, false, false, dated));
    }

    @Override
    public void start(SourceContext ctx) throws SQLException {
        this.context = ctx;
        this.url = ctx.setting("url", "");
        if (url.isEmpty()) {
            throw new IllegalStateException("jdbc plugin needs settings.url");
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
            pool.add(DriverManager.getConnection(url, user, password));
        }
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, com.ash.drishti.api.AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, com.ash.drishti.api.AsOf asOf) throws Exception {
        String sql = queries.get(ref.kind());
        if (sql == null) {
            return Optional.empty();
        }
        Connection c = pool.poll(5, TimeUnit.SECONDS);
        if (c == null) {
            throw new SQLException("no free connection in " + sourceName + " pool");
        }
        try {
            if (!c.isValid(1)) {
                c.close();
                c = DriverManager.getConnection(url, user, password);
            }
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
        } finally {
            pool.offer(c);
        }
    }

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
            } else {
                fields.put(camel(name), plain(v));
            }
        }
        return new EntityDocument(ref, doc != null ? doc : DataNode.of(fields), new Provenance(sourceName, generation, Instant.now(), false, businessDate));
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
        return pool == null ? "DOWN: not started" : "UP (" + pool.size() + " idle connections)";
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.forEach(c -> {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // shutting down
                }
            });
        }
    }
}
