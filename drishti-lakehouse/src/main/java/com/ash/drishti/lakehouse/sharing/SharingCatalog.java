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
package com.ash.drishti.lakehouse.sharing;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.lakehouse.JsonHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tables of one schema of a Delta Sharing share ({@code namespace} = {@code share.schema}, or {@code schema} with the {@code share}
 * setting), each read as an Iceberg table through {@link SharingTableOps}. No storage keys are needed: the server hands out pre-signed
 * URLs. Predicate hints per table ({@code hints.<share.schema.table>}, or {@code hints.<table>}): comparisons joined by {@code and}, such
 * as {@code business_date >= 2026-01-01 and region = EMEA}, sent as the protocol's {@code jsonPredicateHints} so the server may leave out
 * files that cannot match (a hint, never a filter: the server may ignore it). Read-only. Thread-safe.
 */
public final class SharingCatalog implements Catalog {

    private static final Pattern COMPARISON = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(<=|>=|!=|=|<|>)\\s*(.+?)\\s*");

    private final SharingClient client;
    private final List<String> namespace;
    private final Map<String, String> settings;
    private final FileAccess io;
    private final List<SharingTableOps> opened = new CopyOnWriteArrayList<>();
    private final Map<String, String> why = new ConcurrentHashMap<>();

    public SharingCatalog(SharingClient client, List<String> namespace, Map<String, String> settings, FileAccess io) {
        this.client = client;
        this.namespace = List.copyOf(namespace);
        this.settings = Map.copyOf(settings);
        this.io = io;
    }

    /** The share and schema of this catalog. */
    private String[] shareSchema() {
        String defaultShare = settings.getOrDefault("share", "").trim();
        if (namespace.size() == 2) {
            return new String[] {namespace.get(0), namespace.get(1)};
        }
        if (namespace.size() == 1 && !defaultShare.isEmpty()) {
            return new String[] {defaultShare, namespace.get(0)};
        }
        throw new IllegalArgumentException("a shared table is named share.schema.table (or schema.table with settings.share), not "
                + String.join(".", namespace) + (namespace.isEmpty() ? "" : ".") + "<table>");
    }

    @Override
    public Optional<Table> load(String table) throws IOException {
        String[] ss = shareSchema();
        try {
            if (!client.tables(ss[0], ss[1]).contains(table)) {
                why.put(table, "no table " + table + ": the share " + ss[0] + " has no table " + table + " in schema " + ss[1]
                        + " for this recipient");
                return Optional.empty();
            }
        } catch (IOException e) {
            why.put(table, e.getMessage());
            throw e;
        }
        String full = ss[0] + "." + ss[1] + "." + table;
        String hint = settings.getOrDefault("hints." + full, settings.getOrDefault("hints." + ss[1] + "." + table, settings.get("hints." + table)));
        SharingTableOps ops = new SharingTableOps(client, ss[0], ss[1], table, hint == null || hint.isBlank() ? null : hints(hint), io);
        opened.add(ops);
        try {
            return Optional.of(Table.load(full, ops));
        } catch (IOException | RuntimeException e) {
            ops.close();
            opened.remove(ops);
            throw e;
        }
    }

    /** {@code a >= 1 and b = x} as the protocol's JSON predicate (values typed by the server against the column's type: sent as text). */
    static JsonNode hints(String text) {
        List<ObjectNode> parts = new ArrayList<>();
        for (String c : text.split("(?i)\\s+and\\s+")) {
            Matcher m = COMPARISON.matcher(c);
            if (!m.matches()) {
                throw new IllegalArgumentException("a hint is comparisons joined by 'and' (business_date >= 2026-01-01 and region = EMEA), not '"
                        + c.trim() + "'");
            }
            String value = m.group(3).replaceAll("^['\"]|['\"]$", "");
            String type = value.matches("\\d{4}-\\d{2}-\\d{2}") ? "date" : value.matches("-?\\d+") ? "long"
                    : value.matches("-?\\d*\\.\\d+") ? "double" : "string";
            ObjectNode col = JsonHttp.JSON.createObjectNode().put("op", "column").put("name", m.group(1)).put("valueType", type);
            ObjectNode lit = JsonHttp.JSON.createObjectNode().put("op", "literal").put("value", value).put("valueType", type);
            String op = switch (m.group(2)) {
                case "=" -> "equal";
                case "<" -> "lessThan";
                case "<=" -> "lessThanOrEqual";
                case ">" -> "greaterThan";
                case ">=" -> "greaterThanOrEqual";
                default -> "not";
            };
            ObjectNode p = JsonHttp.JSON.createObjectNode();
            if (op.equals("not")) {
                ObjectNode eq = JsonHttp.JSON.createObjectNode().put("op", "equal");
                eq.putArray("children").add(col).add(lit);
                p.put("op", "not").putArray("children").add(eq);
            } else {
                p.put("op", op).putArray("children").add(col).add(lit);
            }
            parts.add(p);
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        ObjectNode and = JsonHttp.JSON.createObjectNode().put("op", "and");
        ArrayNode children = and.putArray("children");
        parts.forEach(children::add);
        return and;
    }

    @Override
    public String missing(String table) {
        return why.getOrDefault(table, "no table " + table);
    }

    @Override
    public List<String> tables() throws IOException {
        String[] ss = shareSchema();
        return client.tables(ss[0], ss[1]);
    }

    @Override
    public Table create(String name, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) {
        throw new UnsupportedOperationException("shared tables are read, never created");
    }

    @Override
    public boolean reachable() {
        try {
            client.shares();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String describe() {
        return "Delta Sharing " + client.profile().endpoint() + (namespace.isEmpty() ? "" : " " + String.join(".", namespace));
    }

    @Override
    public void close() {
        opened.forEach(SharingTableOps::close);
    }
}
