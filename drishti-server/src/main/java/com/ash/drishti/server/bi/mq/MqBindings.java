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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.expr.DelPrinter;
import com.ash.drishti.server.bi.expr.Expressions;
import com.ash.drishti.server.bi.layer.ModelDef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Set;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bindings of {@code mq: 2} (docs/architecture/BI_DESIGN.md sections 3.3.5 and 3.6), applied before and after a query's local measures:
 * <ul>
 * <li><b>field parameters</b>: a field written {@code param.breakdown} is the field the query's {@code params} chose (or the parameter's default); a field
 * parameter whose choices are measures ({@code metric: { kind: field, fields: [MTM, Notional] }}) is named the same way among the measures (2.6.0);</li>
 * <li><b>perspective</b>: the query may name only the fields and measures of that perspective ({@code DRS-9153});</li>
 * <li><b>what-if parameters</b>: every {@code param.name} in the model's measures (and the local ones) is bound to the query's value or the default, checked
 * against the parameter's kind, range and values ({@code DRS-9164}); the values are part of the plan, so of every cache key;</li>
 * <li><b>calculation groups</b>: {@code calc: { Time: YTD }} rewrites each selected measure {@code M} as the item with {@code SELECTED} meaning the measure
 * as the model defines it ({@code DRS-9165}); groups apply by precedence, the highest innermost. The answer keeps the measure's name.</li>
 * </ul>
 * Pure.
 */
final class MqBindings {

    /** The name a measure the calculation group rewrites keeps its own definition under. */
    static final String BASE = "__base:";
    static final String PARAM = "param.";

    private MqBindings() {}

    /** Field parameters resolved and the perspective checked: the query as the later steps see it. */
    static ModelQuery select(ModelView v, ModelQuery q) {
        Map<String, ModelDef.Parameter> params = v.def().calculation().parameters();
        List<MqException.Problem> out = new ArrayList<>();
        checkValues(params, q.params(), out);
        Set<String> measureNames = new java.util.HashSet<>(v.flat().measures().keySet());
        measureNames.addAll(q.define().keySet());
        List<String> fields = chosen(params, q, q.fields(), "select.fields", measureNames, out);
        List<String> measures = chosen(params, q, q.measures(), "select.measures", measureNames, out);
        if (!out.isEmpty()) {
            throw new MqException(out);
        }
        ModelQuery bound = fields.equals(q.fields()) && measures.equals(q.measures()) ? q : q.withSelect(fields, measures);
        if (bound.sort().stream().anyMatch(s -> s.by() != null && s.by().startsWith(PARAM))) {
            List<ModelQuery.Sort> sort = bound.sort().stream().map(s -> s.by() != null && s.by().startsWith(PARAM) && params.containsKey(s.by().substring(PARAM.length()))
                    ? new ModelQuery.Sort(String.valueOf(choice(params.get(s.by().substring(PARAM.length())), q)), s.desc()) : s).toList();
            bound = new ModelQuery(bound.mq(), bound.model(), bound.define(), bound.fields(), bound.measures(), bound.where(), bound.having(), bound.time(), sort, bound.limit(),
                    bound.totals(), bound.drill(), bound.shape(), bound.params(), bound.options(), bound.ext());
        }
        Map<String, Object> values = values(v, q);
        if (!values.isEmpty() && q.define().values().stream().anyMatch(t -> t.contains(PARAM))) {
            Map<String, String> define = new LinkedHashMap<>();
            q.define().forEach((k, t) -> define.put(k, t.contains(PARAM) ? DelPrinter.print(bindParams(Expressions.parse(t), values)) : t));
            bound = new ModelQuery(bound.mq(), bound.model(), define, bound.fields(), bound.measures(), bound.where(), bound.having(), bound.time(), bound.sort(), bound.limit(),
                    bound.totals(), bound.drill(), bound.shape(), bound.params(), bound.options(), bound.ext());
        }
        String name = q.ext().perspective();
        if (name != null) {
            ModelDef.Perspective p = v.def().semantics().perspectives().get(name);
            if (p == null) {
                throw new MqException(new MqException.Problem("DRS-9152", "perspective", "the model has no perspective '" + name + "'",
                        Map.of("replace", "perspective", "with", List.copyOf(v.def().semantics().perspectives().keySet()))));
            }
            for (int i = 0; i < bound.fields().size(); i++) {
                String f = bound.fields().get(i);
                String[] parts = f.split("\\.");
                if (!p.shows(parts.length > 2 ? parts[0] + "." + parts[1] : f)) {
                    out.add(MqException.Problem.of("DRS-9153", "select.fields[" + i + "]", "'" + f + "' is not in the perspective '" + name + "'"));
                }
            }
            for (int i = 0; i < bound.measures().size(); i++) {
                String m = bound.measures().get(i);
                if (!p.measures().isEmpty() && !p.measures().contains(m) && !q.define().containsKey(m)) {
                    out.add(MqException.Problem.of("DRS-9153", "select.measures[" + i + "]", "'" + m + "' is not in the perspective '" + name + "'"));
                }
            }
            if (!out.isEmpty()) {
                throw new MqException(out);
            }
        }
        for (String g : q.ext().calc().keySet()) {
            ModelDef.CalcGroup group = v.def().calculation().calcGroups().get(g);
            if (group == null) {
                out.add(new MqException.Problem("DRS-9165", "calc." + g, "the model has no calculation group '" + g + "'",
                        Map.of("replace", "calc", "with", List.copyOf(v.def().calculation().calcGroups().keySet()))));
            } else if (!group.items().containsKey(q.ext().calc().get(g))) {
                out.add(new MqException.Problem("DRS-9165", "calc." + g, "the calculation group '" + g + "' has no item '" + q.ext().calc().get(g) + "'",
                        Map.of("replace", "calc." + g, "with", List.copyOf(group.items().keySet()))));
            }
        }
        if (!out.isEmpty()) {
            throw new MqException(out);
        }
        return bound;
    }

    /** The names a list of the query selects, each {@code param.x} replaced by the field (or measure) the field parameter {@code x} chose. */
    private static List<String> chosen(Map<String, ModelDef.Parameter> params, ModelQuery q, List<String> names, String path, Set<String> measureNames,
            List<MqException.Problem> out) {
        List<String> list = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String f = names.get(i);
            if (!f.startsWith(PARAM)) {
                list.add(f);
                continue;
            }
            ModelDef.Parameter p = params.get(f.substring(PARAM.length()));
            if (p == null || !"field".equals(p.kind())) {
                out.add(MqException.Problem.of("DRS-9164", path + "[" + i + "]", "'" + f + "' names no field parameter of the model"));
                continue;
            }
            // a parameter of measures among the fields (or of fields among the measures) is named as itself, not as the choice it stands for (QA2-20)
            boolean amongMeasures = path.endsWith("measures");
            List<String> wrong = p.fields().stream().filter(c -> measureNames.contains(c) != amongMeasures).toList();
            if (!wrong.isEmpty()) {
                out.add(MqException.Problem.of("DRS-9164", path + "[" + i + "]", "'" + f + "' chooses among " + String.join(", ", p.fields()) + ": "
                        + (amongMeasures ? (wrong.size() == p.fields().size() ? "fields" : String.join(", ", wrong) + (wrong.size() == 1 ? " is a field" : " are fields"))
                                + ", so it goes in select.fields"
                                : (wrong.size() == p.fields().size() ? "measures" : String.join(", ", wrong) + (wrong.size() == 1 ? " is a measure" : " are measures"))
                                        + ", so it goes in select.measures")));
                continue;
            }
            list.add(String.valueOf(choice(p, q)));
        }
        return list;
    }

    /** What a field parameter is for this query: the query's value, else the parameter's default, else its first choice. */
    private static Object choice(ModelDef.Parameter p, ModelQuery q) {
        return q.params().getOrDefault(p.name(), p.defaultValue() != null ? p.defaultValue() : p.fields().get(0));
    }

    private static void checkValues(Map<String, ModelDef.Parameter> params, Map<String, Object> values, List<MqException.Problem> out) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            ModelDef.Parameter p = params.get(e.getKey());
            String path = "params." + e.getKey();
            if (p == null) {
                out.add(new MqException.Problem("DRS-9164", path, "the model has no parameter '" + e.getKey() + "'", Map.of("remove", path)));
                continue;
            }
            String problem = problem(p, e.getValue());
            if (problem != null) {
                out.add(MqException.Problem.of("DRS-9164", path, problem));
            }
        }
    }

    /** What is wrong with a parameter's value, or null. */
    static String problem(ModelDef.Parameter p, Object v) {
        if (v == null) {
            return p.name() + " has no value";
        }
        switch (p.kind()) {
            case "number", "integer" -> {
                if (!(v instanceof Number n)) {
                    return p.name() + " is a number, not '" + v + "'";
                }
                if ("integer".equals(p.kind()) && n.doubleValue() != Math.rint(n.doubleValue())) {
                    return p.name() + " is a whole number";
                }
                if (p.min() != null && n.doubleValue() < p.min() || p.max() != null && n.doubleValue() > p.max()) {
                    return p.name() + " is from " + p.min() + " to " + p.max() + ", not " + v;
                }
            }
            case "date" -> {
                try {
                    LocalDate.parse(String.valueOf(v));
                } catch (RuntimeException x) {
                    return p.name() + " is a date (yyyy-mm-dd), not '" + v + "'";
                }
            }
            case "boolean" -> {
                if (!(v instanceof Boolean)) {
                    return p.name() + " is true or false";
                }
            }
            case "field" -> {
                if (!p.fields().contains(String.valueOf(v))) {
                    return p.name() + " is one of its choices " + p.fields() + ", not '" + v + "'";
                }
            }
            default -> { }
        }
        if (!p.values().isEmpty() && !"field".equals(p.kind()) && p.values().stream().noneMatch(x -> String.valueOf(x).equals(String.valueOf(v)))) {
            return p.name() + " is one of " + p.values() + ", not '" + v + "'";
        }
        return null;
    }

    /** The values the measures see: the query's, else each parameter's default (field parameters left out: they are not values). */
    static Map<String, Object> values(ModelView v, ModelQuery q) {
        Map<String, Object> out = new LinkedHashMap<>();
        v.def().calculation().parameters().forEach((name, p) -> {
            if (!"field".equals(p.kind())) {
                out.put(name, q.params().getOrDefault(name, p.defaultValue()));
            }
        });
        return out;
    }

    /** The dataset with the parameters bound into its measures and the calculation groups applied to the selected measures. */
    static Dataset bind(ModelView v, ModelQuery q, Dataset ds) {
        Map<String, Object> values = values(v, q);
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>(ds.measures());
        boolean changed = false;
        if (!values.isEmpty()) {
            for (Map.Entry<String, Dataset.Measure> e : ds.measures().entrySet()) {
                Dataset.Measure m = e.getValue();
                if (m.expr() != null && m.expr().contains(PARAM)) {
                    measures.put(e.getKey(), withExpr(m, DelPrinter.print(bindParams(Expressions.parse(m.expr()), values))));
                    changed = true;
                }
            }
        }
        if (!q.ext().calc().isEmpty()) {
            List<ModelDef.CalcGroup> groups = q.ext().calc().keySet().stream().map(g -> v.def().calculation().calcGroups().get(g))
                    .sorted(Comparator.comparingInt(ModelDef.CalcGroup::precedence).reversed()).toList();
            for (String name : q.measures()) {
                Dataset.Measure m = measures.get(name);
                if (m == null || m.expr() == null) {
                    throw new MqException(MqException.Problem.of("DRS-9165", "calc", "a calculation group applies to measures with an expression; '" + name + "' is a Python measure"));
                }
                String base = BASE + name;
                measures.put(base, new Dataset.Measure(base, m.expr(), m.format(), m.description(), null, m.inputs(), m.by()));
                Node current = new Node.MeasureRef(base);
                String format = m.format();
                for (ModelDef.CalcGroup g : groups) {
                    String template = g.items().get(q.ext().calc().get(g.name()));
                    format = g.formats().getOrDefault(q.ext().calc().get(g.name()), format);     // the outermost item with a format of its own decides
                    Node t = com.ash.drishti.server.bi.expr.DelLowering.lower(com.ash.drishti.server.bi.expr.DelParser.parse(template));
                    current = selected(t, current);
                }
                Node bound = values.isEmpty() ? current : bindParams(current, values);
                measures.put(name, new Dataset.Measure(m.name(), DelPrinter.print(bound), format, m.description(), m.python(), m.inputs(), m.by()));
                changed = true;
            }
        }
        if (!changed) {
            return ds;
        }
        return ds.withMeasures(measures);
    }

    private static Dataset.Measure withExpr(Dataset.Measure m, String expr) {
        return new Dataset.Measure(m.name(), expr, m.format(), m.description(), m.python(), m.inputs(), m.by());
    }

    /** The template with {@code SELECTED} replaced by the measure (a tree, never text substitution). */
    static Node selected(Node t, Node measure) {
        return switch (t) {
            case Node.Ref r when r.parts().equals(List.of("SELECTED")) -> measure;
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(a -> selected(a, measure)).toList());
            case Node.Bin b -> new Node.Bin(b.op(), selected(b.left(), measure), selected(b.right(), measure));
            case Node.Un u -> new Node.Un(u.op(), selected(u.operand(), measure));
            case Node.In i -> new Node.In(selected(i.value(), measure), i.items().stream().map(x -> selected(x, measure)).toList(), i.negated());
            case Node.Cond c -> new Node.Cond(selected(c.test(), measure), selected(c.yes(), measure), selected(c.no(), measure));
            default -> t;
        };
    }

    /** The tree with each {@code param.name} replaced by its value as a literal. */
    static Node bindParams(Node t, Map<String, Object> values) {
        return switch (t) {
            case Node.Ref r when r.parts().size() == 2 && "param".equals(r.parts().get(0)) && values.containsKey(r.parts().get(1)) -> literal(values.get(r.parts().get(1)));
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(a -> bindParams(a, values)).toList());
            case Node.Bin b -> new Node.Bin(b.op(), bindParams(b.left(), values), bindParams(b.right(), values));
            case Node.Un u -> new Node.Un(u.op(), bindParams(u.operand(), values));
            case Node.In i -> new Node.In(bindParams(i.value(), values), i.items().stream().map(x -> bindParams(x, values)).toList(), i.negated());
            case Node.Cond c -> new Node.Cond(bindParams(c.test(), values), bindParams(c.yes(), values), bindParams(c.no(), values));
            default -> t;
        };
    }

    private static Node literal(Object v) {
        return switch (v) {
            case null -> new Node.Null();
            case Integer i -> new Node.Num(i, true);
            case Long l -> new Node.Num(l, true);
            case Number n -> n.doubleValue() < 0 ? new Node.Un("-", new Node.Num(-n.doubleValue(), false)) : new Node.Num(n.doubleValue(), false);
            case Boolean b -> new Node.Bool(b);
            default -> {
                String s = String.valueOf(v);
                yield s.matches("\\d{4}-\\d{2}-\\d{2}") ? new Node.Call("date", List.of(new Node.Str(s))) : new Node.Str(s);
            }
        };
    }
}
