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
package com.ash.drishti.engine.source.derived;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One group of a derived kind (a grouped page: a counterparty's trades, a book's P&amp;L) gathered across Drishti*net's nodes
 * (DRISHTI_NET.md section 8). Each node that holds members of the kind the group is built from sends its <em>share</em>: its page of the
 * group, its member count and, when it lists every member (a group of up to {@code max-members}), each member's values by field name
 * ({@code {memberCount, complete, members: {id: {fields: {name: value}, row: {column: value}}}, page}}), so the node's masks apply to it by
 * name as to any document. The gathering node merges the shares:
 *
 * <ul>
 *   <li>every share complete: the members are united by id (a member two nodes hold counts once, the first share's values win) and the
 *       page is built from them exactly as one node holding all of them would build it;</li>
 *   <li>a share incomplete (a node lists only the first {@code max-members} of a larger group): the pages' aggregates are combined
 *       (counts and sums added, extremes compared, distinct values united, the first member's value taken from the share whose first member
 *       sorts first, an average weighted by member count) and the listed members united; a member held by two such nodes is then counted
 *       on each.</li>
 * </ul>
 *
 * A value a node masked stays masked in the merged page.
 */
public final class GroupShares {

    /** A node's share as the gathering node reads it: members in {@link DerivedKind#compact} form, or none when it is incomplete. */
    public record Share(String node, long memberCount, boolean complete, Map<String, DataNode> members, DataNode page) {
        public Share {
            members = members == null ? Map.of() : members;
        }
    }

    private GroupShares() {
    }

    /** The share a node sends: its page, and each member by field name when it lists them all ({@code compacts} null otherwise). */
    static DataNode document(DerivedKind k, DataNode page, Map<String, DataNode> compacts) {
        Map<String, Object> out = new LinkedHashMap<>();
        long count = (long) page.get("memberCount").asDouble();
        out.put("memberCount", count);
        out.put("complete", compacts != null);
        if (compacts != null) {
            Map<String, Object> members = new LinkedHashMap<>();
            compacts.forEach((id, c) -> members.put(id, named(k, c)));
            out.put("members", members);
        }
        out.put("page", page);
        return DataNode.of(out);
    }

    /** A share read back: the members by field name turned into the compact form {@link DerivedKind#buildCompact} reads. */
    static Share read(DerivedKind k, String node, DataNode doc) {
        boolean complete = doc.get("complete").asBoolean();
        Map<String, DataNode> members = new LinkedHashMap<>();
        DataNode m = doc.get("members");
        if (complete && m instanceof DataNode.Obj o) {
            o.fields().forEach((id, named) -> members.put(id, compactOf(k, named)));
        }
        return new Share(node, (long) doc.get("memberCount").asDouble(), complete, members, doc.get("page"));
    }

    private static Map<String, Object> named(DerivedKind k, DataNode compact) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < k.fieldCount(); i++) {
            if (k.fieldReads(i)) {
                fields.put(k.fieldName(i), compact.get(DerivedKind.fieldKey(i)));
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        k.rowColumnNames().forEach(col -> row.put(col, compact.get(DerivedKind.rowKey(col))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fields", fields);
        out.put("row", row);
        return out;
    }

    private static DataNode compactOf(DerivedKind k, DataNode named) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < k.fieldCount(); i++) {
            if (k.fieldReads(i)) {
                out.put(DerivedKind.fieldKey(i), named.get("fields").get(k.fieldName(i)));
            }
        }
        k.rowColumnNames().forEach(col -> out.put(DerivedKind.rowKey(col), named.get("row").get(col)));
        return DataNode.of(out);
    }

    /** The group's page from every node's share (in the order asked: this node's first). */
    static DataNode merge(DerivedKind k, String key, List<Share> shares) {
        return shares.stream().allMatch(Share::complete) ? exact(k, key, shares) : combined(k, key, shares);
    }

    private static DataNode exact(DerivedKind k, String key, List<Share> shares) {
        Map<String, DataNode> all = new LinkedHashMap<>();
        shares.forEach(s -> s.members().forEach(all::putIfAbsent));
        Map<String, Object> doc = k.buildCompactMap(key, all);
        for (int i = 0; i < k.fieldCount(); i++) {
            String f = DerivedKind.fieldKey(i);
            if (k.fieldReads(i) && all.values().stream().anyMatch(m -> m.get(f).isMasked())) {
                doc.put(k.fieldName(i), DataNode.masked());
            }
        }
        return DataNode.of(doc);
    }

    private static DataNode combined(DerivedKind k, String key, List<Share> shares) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", key);
        doc.put(k.idField(), key);
        doc.put("derivedFrom", k.from());
        long count = shares.stream().mapToLong(Share::memberCount).sum();
        for (int i = 0; i < k.fieldCount(); i++) {
            doc.put(k.fieldName(i), combine(k, i, shares));
        }
        doc.put("memberCount", count);
        TreeMap<String, Object> rows = new TreeMap<>();
        for (Share s : shares) {
            DataNode listed = s.page().get(k.membersField());
            DataNode pageRows = s.page().get(k.rowsField());
            for (int j = 0; j < listed.size(); j++) {
                rows.putIfAbsent(listed.get(j).asText(), pageRows.isMissing() ? null : pageRows.get(j));
            }
        }
        List<String> ids = new ArrayList<>(rows.keySet());
        List<String> listed = ids.size() > k.maxMembers() ? ids.subList(0, k.maxMembers()) : ids;
        doc.put(k.membersField(), listed);
        if (!k.rowColumnNames().isEmpty()) {
            doc.put(k.rowsField(), listed.stream().map(rows::get).toList());
        }
        k.finish(key, doc);                                // the then fields and the tree, over the combined page
        return DataNode.of(doc);
    }

    private static Object combine(DerivedKind k, int i, List<Share> shares) {
        String name = k.fieldName(i);
        if (shares.stream().anyMatch(s -> s.page().get(name).isMasked())) {
            return DataNode.masked();
        }
        String op = k.fieldOp(i);
        switch (op) {
            case "distinct" -> {
                TreeSet<String> out = new TreeSet<>();
                shares.forEach(s -> {
                    DataNode v = s.page().get(name);
                    for (int j = 0; j < v.size(); j++) {
                        out.add(v.get(j).asText());
                    }
                });
                return List.copyOf(out);
            }
            case "first" -> {
                Share first = null;
                String firstId = null;
                for (Share s : shares) {
                    DataNode listed = s.page().get(k.membersField());
                    String id = listed.size() == 0 ? null : listed.get(0).asText();
                    if (id != null && (firstId == null || id.compareTo(firstId) < 0)) {
                        first = s;
                        firstId = id;
                    }
                }
                return first == null ? null : first.page().get(name);
            }
            case "median" -> {
                return shares.size() == 1 ? shares.get(0).page().get(name) : null;   // medians of parts do not make the whole's
            }
            default -> {
                double total = 0;
                double weight = 0;
                Double best = null;
                boolean any = false;
                for (Share s : shares) {
                    DataNode v = s.page().get(name);
                    if (!Values.isNumber(v)) {
                        continue;
                    }
                    double d = Values.number(v);
                    any = true;
                    switch (op) {
                        case "avg" -> {
                            total += d * s.memberCount();
                            weight += s.memberCount();
                        }
                        case "min" -> best = best == null ? d : Math.min(best, d);
                        case "max" -> best = best == null ? d : Math.max(best, d);
                        default -> total += d;             // count, sum
                    }
                }
                if (op.equals("count")) {
                    return (long) total;
                }
                if (!any) {
                    return op.equals("sum") ? Values.normalise(0) : null;
                }
                return switch (op) {
                    case "avg" -> weight == 0 ? null : Values.normalise(total / weight);
                    case "min", "max" -> Values.normalise(best);
                    default -> Values.normalise(total);
                };
            }
        }
    }
}
