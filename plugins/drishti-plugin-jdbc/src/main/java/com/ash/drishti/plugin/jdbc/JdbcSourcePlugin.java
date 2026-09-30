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
 * for the id. The first row becomes the document: each column is a field, except a column named
 * {@code json}, whose JSON text is used as the whole document. A column named {@code generation}, if
 * present, is the version. Connections are pooled in a small bounded queue.
 */
public final class JdbcSourcePlugin implements SourcePlugin {

    private final Map<String, String> queries = new LinkedHashMap<>();
    private BlockingQueue<Connection> pool;
    private SourceContext context;
    private String url;
    private String user;
    private String password;
    private String sourceName;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jdbc", "1.0", queries.keySet(), SourceCapabilities.FETCH_ONLY);
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
                queries.put(k.substring(6), v);
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
                ps.setString(1, ref.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(toDocument(ref, rs));
                }
            }
        } finally {
            pool.offer(c);
        }
    }

    private EntityDocument toDocument(EntityRef ref, ResultSet rs) throws Exception {
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
            } else {
                fields.put(camel(name), plain(v));
            }
        }
        return new EntityDocument(ref, doc != null ? doc : DataNode.of(fields), new Provenance(sourceName, generation, Instant.now(), false));
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
