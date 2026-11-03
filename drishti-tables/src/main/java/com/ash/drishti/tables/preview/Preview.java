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
package com.ash.drishti.tables.preview;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.expr.Node;
import com.ash.drishti.tables.expr.Reader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * A table's first rows over sample documents ({@code drishti.py tables preview}, VIRTUAL_TABLES.md section 8), and the checks a sample
 * allows ({@code tables check --sample}): keys unique, every path present in some document. Documents are JSONL (plain or {@code .gz}),
 * one entity per line, either the document itself or {@code {id, business_date, doc}} as the lake keeps it. The grain is expanded here;
 * paths are walked here ({@code $}, {@code @}, {@code ^}, {@code ^^}); filters and calculated columns are evaluated by Rachana through
 * {@link RowEvaluator}. Bounded: at most {@link #MAX_DOCUMENTS} documents and {@link #MAX_ROWS} rows. A preview never reads a lake or a
 * connector: the server's preview (the switch) reads the source through the layer's compiler.
 */
public final class Preview {

    public static final int MAX_DOCUMENTS = 10_000;
    public static final int MAX_ROWS = 10_000;

    /** One sample document. */
    public record Doc(String id, String businessDate, DataNode doc) {}

    /** The rows: the built-in columns first ({@code id}, {@code business_date}, {@code index} under a grain), then the table's. */
    public record Rows(List<String> columns, List<List<Object>> rows, List<Problem> problems, int documents) {}

    private Preview() {}

    /** Reads up to {@code max} documents of a JSONL file ({@code .jsonl} or {@code .jsonl.gz}). */
    public static List<Doc> read(Path file, int max) throws IOException {
        List<Doc> out = new ArrayList<>();
        JsonCodec json = new JsonCodec();
        try (InputStream raw = Files.newInputStream(file);
                InputStream in = file.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && out.size() < Math.min(max, MAX_DOCUMENTS)) {
                n++;
                if (line.isBlank()) {
                    continue;
                }
                DataNode d = json.read(line);
                DataNode inner = d.get("doc");
                if (inner instanceof DataNode.Obj) {
                    out.add(new Doc(text(d.get("id"), "row-" + n), text(d.get("business_date"), null), inner));
                } else {
                    out.add(new Doc(text(d.get("id"), text(d.get("ID"), "row-" + n)), text(d.get("business_date"), text(d.get("BUSINESS_DATE"), null)), d));
                }
            }
        }
        return out;
    }

    private static String text(DataNode n, String dflt) {
        return n == null || n.isNull() ? dflt : n.asText();
    }

    /** The table's rows over {@code docs}, at most {@code limit}, with what could not be computed. */
    public static Rows rows(TableDef t, List<Doc> docs, int limit, RowEvaluator eval) {
        List<String> cols = new ArrayList<>(List.of("id", "business_date"));
        if (t.depth() > 0) {
            cols.add("index");
        }
        cols.addAll(t.columns().keySet());
        List<List<Object>> rows = new ArrayList<>();
        Map<String, Problem> problems = new LinkedHashMap<>();
        int max = Math.min(limit, MAX_ROWS);
        for (Doc d : docs) {
            for (Frame f : frames(t, d)) {
                if (rows.size() >= max) {
                    return new Rows(cols, rows, List.copyOf(problems.values()), docs.size());
                }
                if (t.filter() != null && !Boolean.TRUE.equals(evaluate(eval, t.filter(), f, "filter", problems))) {
                    continue;
                }
                List<Object> row = new ArrayList<>();
                row.add(d.id());
                row.add(d.businessDate());
                if (t.depth() > 0) {
                    row.add(f.index());
                }
                for (ColumnDef c : t.columns().values()) {
                    if (c.path() != null) {
                        row.add(plain(walk(c.path(), f)));
                    } else if (c.expr() != null) {
                        row.add(evaluate(eval, c.expr(), f, "columns." + c.name() + ".expr", problems));
                    } else {
                        row.add(null);
                        problems.putIfAbsent(c.name(), Problem.info(TableCodes.SAMPLE, "columns." + c.name(), "a Python column is computed by its runner, not in a preview"));
                    }
                }
                rows.add(row);
            }
        }
        return new Rows(cols, rows, List.copyOf(problems.values()), docs.size());
    }

    /** The checks a sample allows: keys unique per business date, and every column path found in some row. */
    public static List<Problem> check(TableDef t, List<Doc> docs, RowEvaluator eval) {
        List<Problem> out = new ArrayList<>();
        Rows r = rows(t, docs, MAX_ROWS, eval);
        out.addAll(r.problems());
        if (r.rows().isEmpty()) {
            out.add(Problem.warning(TableCodes.SAMPLE, "", "the sample gives no rows (" + docs.size() + " documents)"));
            return out;
        }
        for (ColumnDef c : t.columns().values()) {
            if (c.path() == null) {
                continue;
            }
            int i = r.columns().indexOf(c.name());
            if (r.rows().stream().allMatch(row -> row.get(i) == null)) {
                out.add(Problem.warning(TableCodes.SAMPLE, "columns." + c.name() + ".path", c.path() + " is empty in every row of the sample"));
            }
        }
        if (!t.keys().isEmpty() && t.keepLatest() == null) {
            List<Integer> at = new ArrayList<>(List.of(r.columns().indexOf("business_date")));
            t.keys().forEach(k -> at.add(r.columns().indexOf(k)));
            Set<List<Object>> seen = new HashSet<>();
            for (List<Object> row : r.rows()) {
                List<Object> key = at.stream().map(i -> i < 0 ? null : row.get(i)).toList();
                if (!seen.add(key)) {
                    out.add(Problem.error(TableCodes.SAMPLE, "keys", "the keys " + t.keys() + " are not unique in the sample: " + key.subList(1, key.size())
                            + " on " + key.get(0) + " more than once (add a key, or keep: { latest: ... })"));
                    break;
                }
            }
        }
        return out;
    }

    /** One row's place: the entity and the elements down the grain (the last is {@code @}). */
    public record Frame(DataNode entity, List<DataNode> elements, int index) {
        /** The node a root names: {@code $}, {@code @}, {@code ^}, {@code ^^}. */
        public DataNode root(String root) {
            int up = switch (root) {
                case "@" -> 0;
                case "^" -> 1;
                case "^^" -> 2;
                default -> -1;
            };
            if (up < 0) {
                return entity;
            }
            int at = elements.size() - 1 - up;
            return at >= 0 ? elements.get(at) : at == -1 ? entity : DataNode.missing();
        }
    }

    /** The frames of one document under the table's grain. */
    static List<Frame> frames(TableDef t, Doc d) {
        List<Frame> out = new ArrayList<>();
        if (t.grain() == null) {
            out.add(new Frame(d.doc(), List.of(), 0));
            return out;
        }
        String[] lists = t.grain().substring(1).split("\\[\\*]");
        expand(d.doc(), d.doc(), lists, 0, new ArrayList<>(), out);
        return out;
    }

    private static void expand(DataNode entity, DataNode at, String[] lists, int level, List<DataNode> chain, List<Frame> out) {
        if (level >= lists.length) {
            return;
        }
        String rel = lists[level].startsWith(".") ? lists[level].substring(1) : lists[level];
        DataNode list = rel.isEmpty() ? at : at.at(rel);
        for (int i = 0; i < list.size(); i++) {
            List<DataNode> next = new ArrayList<>(chain);
            next.add(list.get(i));
            if (level == lists.length - 1) {
                out.add(new Frame(entity, next, i));
            } else {
                expand(entity, list.get(i), lists, level + 1, next, out);
            }
        }
    }

    /** The value at a column's path in a frame. */
    static DataNode walk(String path, Frame f) {
        Node n = Reader.read(path);
        if (!(n instanceof Node.Path p)) {
            return DataNode.missing();
        }
        String rest = p.rest().startsWith(".") ? p.rest().substring(1) : p.rest();
        DataNode base = f.root(p.root());
        return rest.isEmpty() ? base : base.at(rest);
    }

    private static Object plain(DataNode n) {
        return n == null || n.isNull() ? null : n.unwrap();
    }

    private static Object evaluate(RowEvaluator eval, String expr, Frame f, String where, Map<String, Problem> problems) {
        try {
            return eval.eval(expr, f);
        } catch (RowEvaluator.Pending e) {
            problems.putIfAbsent(where, Problem.info(TableCodes.PENDING, where, e.getMessage()));
        } catch (RuntimeException e) {
            problems.putIfAbsent(where, Problem.warning(TableCodes.SAMPLE, where, "not computed: " + e.getMessage()));
        }
        return null;
    }
}
