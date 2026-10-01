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

import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query mode: your own schema, read with your own SQL, several queries per kind (see
 * {@code docs/connectors/JDBC_QUERIES.md}). For each kind:
 *
 * <ul>
 *   <li>{@code query.<kind>}: the entity; its first row is the document (each column a field, or a column named
 *       {@code json} as the whole document).</li>
 *   <li>{@code query.<kind>.<part>}: more of the entity from other tables; its rows become the list {@code <part>} in the
 *       document ({@code part-shape.<kind>.<part>: object} takes the first row as an object). Parts run at once.</li>
 *   <li>{@code ids.<kind>}: a day's ids (and optionally a title) for type-ahead, kept in memory and read again every
 *       {@code refresh-seconds}.</li>
 *   <li>{@code columns.<kind>}: a day's id and promoted fields, one row per entity, for searches, pick lists, derived
 *       kinds, impact and reverse lookups without reading documents; kept by memory.</li>
 *   <li>{@code reverse.<kind>}: the ids of the kind's entities that reference {@code :target}.</li>
 * </ul>
 *
 * Named parameters: {@code :id}, {@code :asOf} (the business date, a SQL {@code DATE}) and {@code :target}; a query
 * with none takes the id as its only {@code ?}.
 */
final class QueryMode {

    /** SQL with named parameters replaced by {@code ?}, and the names in order. */
    record NamedSql(String sql, List<String> names) {
        private static final Pattern NAMED = Pattern.compile(":(id|asOf|target)\\b");

        /** An entity query: without named parameters, its one {@code ?} is the id. */
        static NamedSql of(String text) {
            return of(text, "id");
        }

        /** Without named parameters, each {@code ?} is {@code fallback} (and a query with none takes nothing). */
        static NamedSql of(String text, String fallback) {
            List<String> names = new ArrayList<>();
            Matcher m = NAMED.matcher(text);
            while (m.find()) {
                names.add(m.group(1));
            }
            if (!names.isEmpty()) {
                return new NamedSql(m.replaceAll("?"), List.copyOf(names));
            }
            List<String> plain = new ArrayList<>();
            text.chars().filter(ch -> ch == '?').forEach(ch -> plain.add(fallback));
            return new NamedSql(text, List.copyOf(plain));
        }

        boolean dated() {
            return names.contains("asOf");
        }

        void bind(PreparedStatement ps, String id, LocalDate asOf, String target) throws SQLException {
            for (int i = 0; i < names.size(); i++) {
                switch (names.get(i)) {
                    case "asOf" -> ps.setObject(i + 1, java.sql.Date.valueOf(asOf));
                    case "target" -> ps.setString(i + 1, target);
                    default -> ps.setString(i + 1, id);
                }
            }
        }
    }

    private record DayKey(String kind, LocalDate day) {}

    private final Map<String, NamedSql> main = new LinkedHashMap<>();
    private final Map<String, Map<String, NamedSql>> parts = new LinkedHashMap<>();     // kind -> part -> query
    private final Map<String, Boolean> objectParts = new HashMap<>();                 // "kind.part" -> first row as an object
    private final Map<String, NamedSql> ids = new LinkedHashMap<>();
    private final Map<String, NamedSql> columns = new LinkedHashMap<>();
    private final Map<String, NamedSql> reverse = new LinkedHashMap<>();
    private final Map<String, List<String>> declared = new LinkedHashMap<>();       // layout.<kind>.columns
    private final Map<String, List<String>> discovered = new ConcurrentHashMap<>(); // kind -> column paths of columns.<kind>
    private final Set<String> jsonColumns = ConcurrentHashMap.newKeySet();
    private final HitIndex index = new HitIndex();
    private final TableCatalog.Db db;
    private final SourceContext context;
    private final String sourceName;
    private final java.time.ZoneId zone;
    private final Cache<DayKey, ColumnSet> columnSets;

    QueryMode(SourceContext ctx, TableCatalog.Db db, String sourceName) {
        this.context = ctx;
        this.db = db;
        this.sourceName = sourceName;
        this.zone = java.time.ZoneId.of(ctx.setting("zone", "America/New_York"));
        for (String c : ctx.setting("json-columns", "").split(",")) {
            if (!c.isBlank()) {
                jsonColumns.add(c.trim().toLowerCase(Locale.ROOT));
            }
        }
        ctx.settings().forEach((k, v) -> {
            String[] p = k.split("\\.", 3);
            switch (p[0]) {
                case "query" -> {
                    if (p.length == 2) {
                        main.put(p[1], NamedSql.of(v));
                    } else if (p.length == 3) {
                        parts.computeIfAbsent(p[1], x -> new LinkedHashMap<>()).put(p[2], NamedSql.of(v));
                    }
                }
                case "part-shape" -> {
                    if (p.length == 3) {
                        objectParts.put(p[1] + "." + p[2], "object".equalsIgnoreCase(v.trim()));
                    }
                }
                case "ids" -> ids.put(k.substring(4), NamedSql.of(v, "asOf"));
                case "columns" -> columns.put(k.substring(8), NamedSql.of(v, "asOf"));
                case "reverse" -> reverse.put(k.substring(8), NamedSql.of(v, "target"));
                case "layout" -> {
                    if (k.endsWith(".columns")) {
                        declared.put(k.substring(7, k.length() - 8), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
                    }
                }
                default -> {
                    // not a query-mode setting
                }
            }
        });
        this.columnSets = Caffeine.newBuilder().maximumWeight(Long.parseLong(ctx.setting("columns-cache-mb", "1024")) * 1024 * 1024)
                .weigher((DayKey k, ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, 64L + 64L * v.size() * (1 + v.numbers().size() + v.texts().size())))
                .expireAfterWrite(Duration.ofSeconds(Long.parseLong(ctx.setting("columns-seconds", "300")))).build();
    }

    Set<String> kinds() {
        return main.keySet();
    }

    boolean dated() {
        return main.values().stream().anyMatch(NamedSql::dated) || parts.values().stream().flatMap(m -> m.values().stream()).anyMatch(NamedSql::dated);
    }

    boolean searches() {
        return !ids.isEmpty();
    }

    boolean reverses() {
        return !reverse.isEmpty() || !columns.isEmpty();
    }

    /** The business date a query runs for: the date asked, else today in the business day's zone. */
    LocalDate day(LocalDate asked) {
        return asked != null ? asked : LocalDate.now(zone);
    }

    // ---- the entity ----

    Optional<EntityDocument> fetch(EntityRef ref, LocalDate asked) throws Exception {
        NamedSql q = main.get(ref.kind());
        if (q == null) {
            return Optional.empty();
        }
        LocalDate date = day(asked);
        Map<String, NamedSql> extra = parts.getOrDefault(ref.kind(), Map.of());
        Map<String, Future<Object>> running = new LinkedHashMap<>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            // the parts run at once with the main query, each on its own pooled connection
            extra.forEach((part, sql) -> running.put(part, pool.submit(() -> db.with(c -> part(c, sql, ref, date, objectParts.getOrDefault(ref.kind() + "." + part, false))))));
            Optional<EntityDocument> doc = db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(q.sql())) {
                    q.bind(ps, ref.id(), date, null);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(toDocument(ref, rs, q.dated() ? date : null)) : Optional.<EntityDocument>empty();
                    }
                }
            });
            if (doc.isEmpty() || running.isEmpty()) {
                running.values().forEach(f -> f.cancel(true));
                return doc;
            }
            Map<String, Object> merged = new LinkedHashMap<>();
            if (doc.get().data().unwrap() instanceof Map<?, ?> m) {
                m.forEach((k, v) -> merged.put(String.valueOf(k), v));
            }
            for (var e : running.entrySet()) {
                merged.put(e.getKey(), e.getValue().get());
            }
            return Optional.of(new EntityDocument(ref, DataNode.of(merged), doc.get().provenance()));
        }
    }

    /** One part of an entity: its rows as a list of objects, or the first as an object. */
    private Object part(Connection c, NamedSql sql, EntityRef ref, LocalDate date, boolean object) throws Exception {
        List<Object> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.sql())) {
            sql.bind(ps, ref.id(), date, null);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    rows.add(fields(rs, md, null));
                    if (object) {
                        break;
                    }
                }
            }
        }
        return object ? (rows.isEmpty() ? null : rows.get(0)) : rows;
    }

    private EntityDocument toDocument(EntityRef ref, ResultSet rs, LocalDate asked) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        LocalDate[] businessDate = {asked};
        long[] generation = {System.currentTimeMillis()};
        DataNode[] whole = {null};
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = md.getColumnLabel(i);
            Object v = rs.getObject(i);
            if ("json".equalsIgnoreCase(name) && v != null) {
                whole[0] = context.parseJson(new ByteArrayInputStream(v.toString().getBytes(StandardCharsets.UTF_8)));
            } else if ("generation".equalsIgnoreCase(name) && v instanceof Number n) {
                generation[0] = n.longValue();
            } else if ("business_date".equalsIgnoreCase(name) && v instanceof java.sql.Date d) {
                businessDate[0] = d.toLocalDate();
                fields.put("businessDate", businessDate[0].toString());
            } else {
                fields.put(camel(name), value(md, i, name, v));
            }
        }
        DataNode data = whole[0] != null ? whole[0] : DataNode.of(fields);
        return new EntityDocument(ref, data, new Provenance(sourceName, generation[0], Instant.now(), false, businessDate[0]));
    }

    private Map<String, Object> fields(ResultSet rs, ResultSetMetaData md, Map<String, Object> into) throws Exception {
        Map<String, Object> out = into != null ? into : new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = md.getColumnLabel(i);
            out.put(camel(name), value(md, i, name, rs.getObject(i)));
        }
        return out;
    }

    private Object value(ResultSetMetaData md, int i, String name, Object v) throws SQLException {
        if (v != null && isJson(md.getColumnTypeName(i), name)) {
            return nested(v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v.toString());   // nested, not text
        }
        return plain(v);
    }

    /** A json/jsonb column (PostgreSQL, MySQL), or one named in {@code json-columns} (JSON kept in a text column). */
    private boolean isJson(String typeName, String column) {
        if (typeName != null && (typeName.equalsIgnoreCase("json") || typeName.equalsIgnoreCase("jsonb"))) {
            return true;
        }
        return jsonColumns.contains(column.toLowerCase(Locale.ROOT));
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

    /** {@code TRADE_ID} and {@code trade_id} become {@code tradeId}; {@code counterparty__id} keeps its {@code __}. */
    static String camel(String column) {
        if (!column.contains("_") && !column.equals(column.toUpperCase(Locale.ROOT))) {
            return column;
        }
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        String lower = column.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch == '_' && i + 1 < lower.length() && lower.charAt(i + 1) == '_') {
                sb.append("__");
                i++;
                up = false;
            } else if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }

    // ---- type-ahead ----

    /** Reads every kind's ids again (the date: today); false when a query failed (the last ids stay). */
    boolean refreshIds() {
        if (ids.isEmpty()) {
            return true;
        }
        List<EntityHit> hits = new ArrayList<>();
        boolean ok = true;
        for (var e : ids.entrySet()) {
            String kind = e.getKey();
            try {
                db.with(c -> {
                    try (PreparedStatement ps = c.prepareStatement(e.getValue().sql())) {
                        ps.setFetchSize(10_000);
                        e.getValue().bind(ps, null, day(null), null);
                        try (ResultSet rs = ps.executeQuery()) {
                            boolean titled = rs.getMetaData().getColumnCount() > 1;
                            while (rs.next()) {
                                String id = rs.getString(1);
                                if (id != null) {
                                    String title = titled && rs.getString(2) != null ? rs.getString(2) : id;
                                    hits.add(new EntityHit(EntityRef.of(kind, id), title, kind + " · " + sourceName));
                                }
                            }
                        }
                    }
                    return null;
                });
            } catch (Exception ex) {
                ok = false;
            }
        }
        if (ok || !hits.isEmpty()) {
            index.replaceAll(hits);
        }
        return ok;
    }

    List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    int indexed() {
        return index.size();
    }

    // ---- columns ----

    /**
     * The paths {@code columns.<kind>} returns: the pack's declared paths when it declares them (a column matches a
     * path by name, ignoring case, dots and underscores: {@code counterparty.id}, {@code COUNTERPARTY_ID},
     * {@code counterparty__id}), else the query's own columns (learned on its first run).
     */
    Set<String> columnar(String kind) {
        if (!columns.containsKey(kind)) {
            return Set.of();
        }
        List<String> d = declared.get(kind);
        return new LinkedHashSet<>(d != null && !d.isEmpty() ? d : discovered.getOrDefault(kind, List.of()));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    Optional<ColumnSet> columns(String kind, Collection<String> paths, LocalDate asked) {
        NamedSql q = columns.get(kind);
        if (q == null) {
            return Optional.empty();
        }
        LocalDate date = day(asked);
        ColumnSet all = columnSets.get(new DayKey(kind, date), k -> readDay(kind, q, date));
        if (all == null || all.size() == 0) {
            return Optional.empty();                                   // a day this database does not hold: another store may
        }
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (String p : paths) {
            if (all.numbers().containsKey(p)) {
                nums.put(p, all.numbers().get(p));
            } else if (all.texts().containsKey(p)) {
                texts.put(p, all.texts().get(p));
            } else {
                return Optional.empty();                               // a path the query does not return
            }
        }
        return Optional.of(new ColumnSet(all.ids(), nums, texts, all.businessDate()));
    }

    /** A day's rows of {@code columns.<kind>}: the first column is the id, each other column a path. */
    private ColumnSet readDay(String kind, NamedSql q, LocalDate date) {
        try {
            return db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(q.sql())) {
                    ps.setFetchSize(20_000);
                    q.bind(ps, null, date, null);
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        Map<String, String> byKey = new HashMap<>();
                        declared.getOrDefault(kind, List.of()).forEach(p -> byKey.put(key(p), p));
                        String[] path = new String[n + 1];
                        boolean[] number = new boolean[n + 1];
                        List<String> learned = new ArrayList<>();
                        for (int i = 2; i <= n; i++) {
                            String label = md.getColumnLabel(i);
                            path[i] = byKey.isEmpty() ? camel(label).replace("__", ".") : byKey.get(key(label));
                            number[i] = isNumber(md.getColumnType(i));
                            if (path[i] != null) {
                                learned.add(path[i]);
                            }
                        }
                        discovered.put(kind, List.copyOf(learned));
                        List<String> idList = new ArrayList<>();
                        Map<Integer, double[]> nums = new TreeMap<>();
                        Map<Integer, List<String>> texts = new TreeMap<>();
                        Map<String, String> shared = new HashMap<>();
                        int row = 0;
                        while (rs.next()) {
                            idList.add(rs.getString(1));
                            for (int i = 2; i <= n; i++) {
                                if (path[i] == null) {
                                    continue;
                                }
                                if (number[i]) {
                                    double[] col = nums.computeIfAbsent(i, x -> new double[1024]);
                                    if (row == col.length) {
                                        col = java.util.Arrays.copyOf(col, col.length * 2);
                                        nums.put(i, col);
                                    }
                                    double v = rs.getDouble(i);
                                    col[row] = rs.wasNull() ? Double.NaN : v;
                                } else {
                                    String v = rs.getString(i);
                                    texts.computeIfAbsent(i, x -> new ArrayList<>()).add(v == null || shared.size() > 200_000 ? v : shared.computeIfAbsent(v, x -> x));
                                }
                            }
                            row++;
                        }
                        int size = row;
                        Map<String, double[]> numbers = new LinkedHashMap<>();
                        Map<String, String[]> words = new LinkedHashMap<>();
                        nums.forEach((i, col) -> numbers.put(path[i], java.util.Arrays.copyOf(col, size)));
                        texts.forEach((i, col) -> words.put(path[i], col.toArray(new String[0])));
                        for (int i = 2; i <= n; i++) {                 // a day with no rows still knows its columns
                            if (path[i] != null && !numbers.containsKey(path[i]) && !words.containsKey(path[i])) {
                                if (number[i]) {
                                    numbers.put(path[i], new double[0]);
                                } else {
                                    words.put(path[i], new String[0]);
                                }
                            }
                        }
                        return new ColumnSet(idList.toArray(new String[0]), numbers, words, date);
                    }
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("columns." + kind + " for " + date + ": " + e.getMessage(), e);
        }
    }

    private static boolean isNumber(int sqlType) {
        return switch (sqlType) {
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL, java.sql.Types.DOUBLE, java.sql.Types.FLOAT, java.sql.Types.REAL,
                    java.sql.Types.INTEGER, java.sql.Types.BIGINT, java.sql.Types.SMALLINT, java.sql.Types.TINYINT -> true;
            default -> false;
        };
    }

    // ---- reverse lookups ----

    /** Ids of {@code kind} that reference {@code target}: its {@code reverse.<kind>} query, else its columns' text values. */
    Collection<String> reverse(String kind, String target, LocalDate asked) throws Exception {
        NamedSql q = reverse.get(kind);
        if (q != null) {
            LocalDate date = day(asked);
            return db.with(c -> {
                Set<String> out = new TreeSet<>();
                try (PreparedStatement ps = c.prepareStatement(q.sql())) {
                    q.bind(ps, null, date, target);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(rs.getString(1));
                        }
                    }
                }
                return out;
            });
        }
        Set<String> cols = columnar(kind);
        Optional<ColumnSet> c = cols.isEmpty() ? Optional.empty() : columns(kind, cols, asked);
        if (c.isEmpty()) {
            return List.of();
        }
        Set<String> found = new TreeSet<>();
        c.get().texts().values().forEach(values -> {
            for (int i = 0; i < values.length; i++) {
                if (target.equals(values[i])) {
                    found.add(c.get().ids()[i]);
                }
            }
        });
        return found;
    }

    Set<String> reverseKinds() {
        Set<String> out = new LinkedHashSet<>(reverse.keySet());
        out.addAll(columns.keySet());
        return out;
    }

    Map<String, Object> stats() {
        return Map.of("kinds", main.size(), "parts", parts.values().stream().mapToInt(Map::size).sum(), "ids", index.size(),
                "columnSets", columnSets.estimatedSize());
    }

    void clear() {
        columnSets.invalidateAll();
    }
}
