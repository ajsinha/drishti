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
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import com.ash.drishti.server.bi.expr.DelType;
import com.ash.drishti.server.bi.expr.Fields;
import com.ash.drishti.server.bi.layer.Flattener;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The fields and measures of a model as one reader sees them, for the DEL checker ({@code POST /api/v1/bi/expr/validate}): the flattened model's columns
 * (with the implicit ones every table has), date parts typed as integers, columns hidden from the reader's roles absent (so a hidden column is "not found",
 * as everywhere), masked ones marked, the measures by name or stable id. In row and rule scope {@code current} is the alias whose row is in context, so a bare
 * column name means one of its columns.
 */
public final class ModelFields implements Fields {

    private final Dataset flat;
    private final Resolver resolver;
    private final String current;
    private final SqlCompiler.Masks masks;
    private final Set<String> hidden;
    private final Map<String, String> ids;
    private final Map<String, DelType> params;

    ModelFields(Dataset flat, String current, SqlCompiler.Masks masks, Set<String> hidden, Map<String, String> ids) {
        this(flat, current, masks, hidden, ids, Map.of());
    }

    ModelFields(Dataset flat, String current, SqlCompiler.Masks masks, Set<String> hidden, Map<String, String> ids, Map<String, DelType> params) {
        this.flat = flat;
        this.resolver = new Resolver(flat);
        this.current = current;
        this.masks = masks;
        this.hidden = hidden;
        this.ids = ids;
        this.params = params;
    }

    /** The DEL types of a model's parameters ({@code param.name}): a what-if number, text, date or boolean; a field parameter is not a value. */
    static Map<String, DelType> paramTypes(com.ash.drishti.server.bi.layer.ModelDef def) {
        Map<String, DelType> out = new java.util.LinkedHashMap<>();
        def.calculation().parameters().forEach((name, p) -> {
            DelType t = switch (p.kind()) {
                case "number" -> DelType.DECIMAL;
                case "integer" -> DelType.INTEGER;
                case "text" -> DelType.STRING;
                case "date" -> DelType.DATE;
                case "boolean" -> DelType.BOOLEAN;
                default -> null;
            };
            if (t != null) {
                out.put(name, t);
            }
        });
        return out;
    }

    @Override
    public Optional<DelType> param(String name) {
        return Optional.ofNullable(params.get(name));
    }

    @Override
    public List<String> params() {
        return List.copyOf(params.keySet());
    }

    /** The fields of a model bound to the reader; {@code alias} is the dataset whose row is in context (row and rule scope), or null. */
    public static ModelFields of(MqService.Bound b, String alias, Set<String> roles) {
        ModelView v = b.view();
        Set<String> hidden = new LinkedHashSet<>(b.security().hidden().isEmpty() ? ObjectSecurity.hidden(v, roles) : b.security().hidden());
        String current = alias != null && v.flat().tables().containsKey(alias) ? alias : null;
        if (current == null && alias == null && v.flat().tables().size() == 1) {
            current = v.flat().tables().keySet().iterator().next();
        }
        return new ModelFields(v.flat(), current, b.context().masks(), hidden, Flattener.measureIds(v.measureDefs()), paramTypes(v.def()));
    }

    private static void hiddenOf(String alias, ResolvedDataset r, Set<String> roles, Set<String> out) {
        if (r != null && r.def().security().hiddenRoles().stream().anyMatch(roles::contains)) {
            r.def().security().hiddenColumns().forEach(c -> out.add(alias + "." + c));
        }
    }

    /** The alias in context (row and rule scope), or null. */
    public String current() {
        return current;
    }

    @Override
    public Optional<Field> field(List<String> parts) {
        Resolver.ColRef ref;
        try {
            ref = resolver.resolve(parts, current);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (ref.column() == null || hidden.contains(ref.table() + "." + ref.column())) {
            return Optional.empty();
        }
        Dataset.Column c = resolver.column(ref.table(), ref.column());
        DelType t = ref.part() != null ? DelType.INTEGER : DelType.ofColumn(c == null ? null : c.type());
        return Optional.of(new Field(ref.text(), t, masks != null && masks.masked(ref.table(), ref.column())));
    }

    @Override
    public boolean alias(String name) {
        return flat.tables().containsKey(name);
    }

    @Override
    public Optional<String> measure(String nameOrId) {
        String name = flat.measures().containsKey(nameOrId) ? nameOrId : ids.get(nameOrId);
        return name == null || hidden.contains(ObjectSecurity.MEASURE + name) ? Optional.empty() : Optional.of(name);
    }

    @Override
    public List<String> names() {
        List<String> out = new ArrayList<>();
        flat.tables().forEach((t, table) -> table.columns().keySet().forEach(c -> {
            if (!hidden.contains(t + "." + c)) {
                out.add(t + "." + c);
                if (t.equals(current)) {
                    out.add(c);
                }
            }
        }));
        flat.measures().keySet().stream().filter(m -> !hidden.contains(ObjectSecurity.MEASURE + m)).forEach(out::add);
        return out;
    }
}
