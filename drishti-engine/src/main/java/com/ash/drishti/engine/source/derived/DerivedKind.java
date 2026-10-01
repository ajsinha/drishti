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
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One derived kind: the entities of another kind ({@code from}) grouped by an expression ({@code group-by}), each group
 * one entity whose id is the group's key, with fields aggregated over its members. Declared in a pack's connector
 * settings:
 *
 * <pre>
 * book-pnl:
 *   from: trade
 *   group-by: $.book
 *   where: $.mtm != null          # optional: which members count
 *   id-field: book                # a field holding the key besides id (a link to the book, here)
 *   members: trades               # the field listing the members' ids (default: members)
 *   fields:
 *     tradeCount: count
 *     mtm: sum $.mtm
 *     largest: max $.notional
 *     currencies: distinct $.currency
 *   rows:                         # optional: one row per member, for a table (in the field `rows`, or rows-field)
 *     trade: $.tradeId
 *     mtm: $.mtm
 * </pre>
 *
 * Aggregates: {@code count}, {@code sum}, {@code avg}, {@code min}, {@code max}, {@code distinct} (sorted values),
 * {@code first} (the first member's value, members in id order). Numbers that are missing or not numbers are skipped.
 */
public final class DerivedKind {

    enum Op {
        COUNT, SUM, AVG, MIN, MAX, DISTINCT, FIRST
    }

    record Field(String name, Op op, Expr expr, String source) {}

    private final String kind;
    private final String from;
    private final Expr groupBy;
    private final Expr where;
    private final String idField;
    private final String membersField;
    private final List<Field> fields;
    private final Map<String, Expr> rowColumns;
    private final String rowsField;
    private final Formats formats;

    private DerivedKind(String kind, String from, Expr groupBy, Expr where, String idField, String membersField, List<Field> fields,
            Map<String, Expr> rowColumns, String rowsField, Formats formats) {
        this.kind = kind;
        this.from = from;
        this.groupBy = groupBy;
        this.where = where;
        this.idField = idField;
        this.membersField = membersField;
        this.fields = fields;
        this.rowColumns = rowColumns;
        this.rowsField = rowsField;
        this.formats = formats;
    }

    /** The derived kinds in a connector's settings ({@code <kind>.from} names one), each checked: a mistake fails the start. */
    static List<DerivedKind> parse(Map<String, String> settings, ElCompiler el, Formats formats) {
        List<DerivedKind> out = new ArrayList<>();
        for (String key : settings.keySet()) {
            if (!key.endsWith(".from")) {
                continue;
            }
            String kind = key.substring(0, key.length() - ".from".length());
            String p = kind + ".";
            String from = settings.get(key).trim();
            if (from.equals(kind)) {
                throw new IllegalArgumentException(kind + ": a derived kind cannot be built from itself");
            }
            String group = settings.get(p + "group-by");
            if (group == null || group.isBlank()) {
                throw new IllegalArgumentException(kind + ": group-by is required (an expression over a " + from + ", such as $.book)");
            }
            List<Field> fields = new ArrayList<>();
            String fp = p + "fields.";
            settings.forEach((k, v) -> {
                if (k.startsWith(fp)) {
                    fields.add(field(kind, k.substring(fp.length()), v, el));
                }
            });
            fields.sort(java.util.Comparator.comparing(Field::name));
            Map<String, Expr> rowColumns = new LinkedHashMap<>();
            String rp = p + "rows.";
            settings.forEach((k, v) -> {
                if (k.startsWith(rp)) {
                    rowColumns.put(k.substring(rp.length()), compile(kind, "rows." + k.substring(rp.length()), v, el));
                }
            });
            String where = settings.get(p + "where");
            out.add(new DerivedKind(kind, from, compile(kind, "group-by", group, el), where == null || where.isBlank() ? null : compile(kind, "where", where, el),
                    settings.getOrDefault(p + "id-field", "id"), settings.getOrDefault(p + "members", "members"), List.copyOf(fields),
                    rowColumns, settings.getOrDefault(p + "rows-field", "rows"), formats));
        }
        return out;
    }

    private static Field field(String kind, String name, String spec, ElCompiler el) {
        String s = spec.trim();
        int space = s.indexOf(' ');
        String opName = (space < 0 ? s : s.substring(0, space)).toUpperCase(Locale.ROOT);
        Op op;
        try {
            op = Op.valueOf(opName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(kind + ".fields." + name + ": '" + s + "' must start with count, sum, avg, min, max, distinct or first");
        }
        String expr = space < 0 ? null : s.substring(space + 1).trim();
        if (op != Op.COUNT && (expr == null || expr.isEmpty())) {
            throw new IllegalArgumentException(kind + ".fields." + name + ": " + opName.toLowerCase(Locale.ROOT) + " needs an expression, such as $.mtm");
        }
        return new Field(name, op, expr == null || expr.isEmpty() ? null : compile(kind, "fields." + name, expr, el), s);
    }

    private static Expr compile(String kind, String what, String src, ElCompiler el) {
        try {
            return el.compile(src);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(kind + "." + what + ": '" + src + "' is not an expression: " + e.getMessage(), e);
        }
    }

    public String kind() {
        return kind;
    }

    public String from() {
        return from;
    }

    /** The group a member falls in, or null when it is filtered out or has no key. */
    String keyOf(DataNode member) {
        EvalContext c = EvalContext.of(member, formats);
        if (where != null && !Values.truthy(safe(where, c))) {
            return null;
        }
        Object k = Values.simplify(safe(groupBy, c));
        if (Values.isNull(k)) {
            return null;
        }
        String key = Values.text(k).trim();
        return key.isEmpty() ? null : key;
    }

    /** The derived entity for one group: its key, the members' ids (sorted) and each aggregate. */
    DataNode build(String key, Map<String, DataNode> members) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", key);                               // the entity's id, always (layouts title a view by it)
        doc.put(idField, key);
        doc.put("derivedFrom", from);
        List<String> ids = new ArrayList<>(members.keySet());
        java.util.Collections.sort(ids);
        for (Field f : fields) {
            doc.put(f.name(), aggregate(f, ids, members));
        }
        doc.put(membersField, ids);
        if (!rowColumns.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>(ids.size());
            for (String id : ids) {
                EvalContext c = EvalContext.of(members.get(id), formats);
                Map<String, Object> row = new LinkedHashMap<>();
                rowColumns.forEach((col, e) -> {
                    Object v = Values.simplify(safe(e, c));
                    row.put(col, v instanceof DataNode n ? (n.isNull() ? null : n.unwrap()) : v);
                });
                rows.add(row);
            }
            doc.put(rowsField, rows);
        }
        return DataNode.of(doc);
    }

    private Object aggregate(Field f, List<String> ids, Map<String, DataNode> members) {
        if (f.op() == Op.COUNT) {
            return (long) ids.size();
        }
        double total = 0;
        int n = 0;
        Double best = null;
        Set<String> distinct = new LinkedHashSet<>();
        for (String id : ids) {
            Object v = Values.simplify(safe(f.expr(), EvalContext.of(members.get(id), formats)));
            if (f.op() == Op.FIRST) {
                return v instanceof DataNode node ? (node.isNull() ? null : node.unwrap()) : v;
            }
            if (f.op() == Op.DISTINCT) {
                if (!Values.isNull(v)) {
                    distinct.add(Values.text(v));
                }
                continue;
            }
            if (!Values.isNumber(v)) {
                continue;
            }
            double d = Values.number(v);
            total += d;
            n++;
            best = best == null ? d : f.op() == Op.MIN ? Math.min(best, d) : Math.max(best, d);
        }
        return switch (f.op()) {
            case SUM -> Values.normalise(total);
            case AVG -> n == 0 ? null : Values.normalise(total / n);
            case MIN, MAX -> best == null ? null : Values.normalise(best);
            case DISTINCT -> distinct.stream().sorted().toList();
            default -> null;
        };
    }

    private static Object safe(Expr e, EvalContext c) {
        try {
            return e.eval(c);
        } catch (RuntimeException ex) {
            return null;                                  // one odd member never spoils the group
        }
    }
}
