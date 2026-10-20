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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.report.ReportParser.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field parameters in a report's wells (docs/architecture/BI_DESIGN.md section 12.2, wave D server item 5; docs/guides/REPORTS.md section 21). A well may
 * name {@code param.<name>}, a field parameter of the report's model ({@code parameters: { breakdown: { kind: field, fields: [trade.DESK, trade.PORTFOLIO] } }});
 * the reader's parameter slicer chooses which of its fields (or measures) the visual draws, and the visual is asked with that choice in its place.
 *
 * <p>{@link #check} is the report schema's part ({@code DRS-9037}): the report is on a model, the model has a field parameter of that name, and every one of its
 * choices fits the well (a measure well takes a parameter of measures, a field well one of fields; a well of either takes either). {@link #resolve} puts the
 * choice in place of {@code param.<name>} in the wells and the sort, for one reader's values (the default when they chose none). Pure and thread-safe.
 */
public final class FieldParams {

    /** How a well names a field parameter. */
    public static final String PREFIX = "param.";
    static final String CODE = "DRS-9037";

    private FieldParams() {}

    /** Whether a name is a field parameter's ({@code param.<name>}). */
    public static boolean isParam(String item) {
        return item != null && item.startsWith(PREFIX) && item.length() > PREFIX.length();
    }

    /** True when some visual of the report names a field parameter in a well. */
    public static boolean used(Report report) {
        return report.pages().stream().flatMap(p -> p.visuals().stream()).anyMatch(FieldParams::used);
    }

    /** True when the visual names a field parameter in a well. */
    public static boolean used(Report.Visual v) {
        return v.wells().values().stream().anyMatch(l -> l.stream().anyMatch(FieldParams::isParam));
    }

    /**
     * The problems of every {@code param.<name>} in the report's wells ({@code DRS-9037}): a report not on a model, a name that is no field parameter of the model,
     * a choice the well cannot take (a field in a measure well, a measure in a field well), a choice the model does not have, a slicer's field.
     *
     * @param params the model's parameters as the author reads it (empty for a report on a dataset)
     */
    public static List<Problem> check(Report report, Dataset ds, Map<String, ModelDef.Parameter> params) {
        List<Problem> out = new ArrayList<>();
        if (ds == null) {
            return out;
        }
        Resolver resolver = new Resolver(ds);
        for (int pi = 0; pi < report.pages().size(); pi++) {
            Report.Page page = report.pages().get(pi);
            for (int vi = 0; vi < page.visuals().size(); vi++) {
                Report.Visual v = page.visuals().get(vi);
                VisualCatalogue.Type type = VisualCatalogue.type(v.type());
                for (var e : v.wells().entrySet()) {
                    String path = "pages[" + pi + "].visuals[" + vi + "].wells." + e.getKey();
                    for (String item : e.getValue()) {
                        if (isParam(item)) {
                            VisualCatalogue.Well well = type == null ? null : type.wells().get(e.getKey());
                            checkOne(report, v, well, item, path, ds, resolver, params, out);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void checkOne(Report report, Report.Visual v, VisualCatalogue.Well well, String item, String path, Dataset ds, Resolver resolver,
            Map<String, ModelDef.Parameter> params, List<Problem> out) {
        String name = item.substring(PREFIX.length());
        if (report.model() == null) {
            out.add(ReportParser.error(CODE, path, "'" + item + "' is a field parameter, and field parameters belong to a model: this report reads the dataset '" + report.dataset()
                    + "' (bind the report to a model with model:, and add the parameter there)"));
            return;
        }
        ModelDef.Parameter p = params.get(name);
        List<String> fieldParams = params.values().stream().filter(x -> "field".equals(x.kind())).map(x -> PREFIX + x.name()).toList();
        if (p == null) {
            out.add(ReportParser.error(CODE, path, "the model has no parameter '" + name + "'" + (fieldParams.isEmpty() ? " (it has no field parameters: add one under parameters:, kind: field)"
                    : " (its field parameters: " + String.join(", ", fieldParams) + ")")));
            return;
        }
        if (!"field".equals(p.kind())) {
            out.add(ReportParser.error(CODE, path, "'" + name + "' is a what-if parameter (a " + p.kind() + " value used in measures), not a field parameter: a well takes a parameter of kind: field"
                    + (fieldParams.isEmpty() ? "" : " (" + String.join(", ", fieldParams) + ")")));
            return;
        }
        if ("slicer".equals(v.type())) {
            out.add(ReportParser.error(CODE, path, "a slicer slices one field; the reader chooses a field parameter with its parameter slicer above the page"));
            return;
        }
        if (well == null) {
            return;                                                         // an unknown well is the parser's DRS-9032
        }
        for (String choice : p.fields()) {
            boolean measure = ds.measures().containsKey(choice);
            boolean field = !measure && isField(resolver, choice);
            if (!measure && !field) {
                out.add(ReportParser.error(CODE, path, "the field parameter '" + name + "' can be '" + choice + "', which is neither a field nor a measure of the model"));
            } else if (well.kind() == VisualCatalogue.Kind.MEASURE && !measure) {
                out.add(ReportParser.error(CODE, path, "the " + wellName(path) + " well takes measures, and the field parameter '" + name + "' can be " + choice + ", a field: put it in a field well"));
            } else if (well.kind() == VisualCatalogue.Kind.FIELD && measure) {
                out.add(ReportParser.error(CODE, path, "the " + wellName(path) + " well takes fields, and the field parameter '" + name + "' can be " + choice + ", a measure: put it in a measure well"));
            }
        }
    }

    private static String wellName(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    private static boolean isField(Resolver r, String name) {
        try {
            return r.resolve(List.of(name.split("\\.")), null).column() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The visual as it is drawn for these values: each {@code param.<name>} of its wells (and of its sort) replaced by the chosen field or measure. A visual
     * that names none is returned as it is.
     *
     * @throws DrishtiException {@code DRS-9037} when a value is not one of the parameter's choices, or the name is no field parameter
     */
    public static Report.Visual resolve(Report.Visual v, Map<String, ModelDef.Parameter> params, Map<String, Object> values) {
        if (!used(v) && (v.sort() == null || !isParam(v.sort().by()))) {
            return v;
        }
        Map<String, List<String>> wells = new LinkedHashMap<>();
        v.wells().forEach((w, items) -> wells.put(w, items.stream().map(i -> isParam(i) ? choice(i, params, values) : i).toList()));
        Report.Sort sort = v.sort() != null && isParam(v.sort().by()) ? new Report.Sort(choice(v.sort().by(), params, values), v.sort().desc()) : v.sort();
        return new Report.Visual(v.id(), v.type(), v.title(), v.at(), wells, v.filters(), sort, v.limit(), v.hierarchy(), v.drillThrough(), v.format(), v.text(), v.src(),
                v.alt(), v.interacts(), v.action(), v.dataset(), v.tooltip(), v.from(), v.alert());
    }

    /** What {@code param.<name>} is for these values: the value when it is one of the choices, else the default, else the first choice. */
    public static String choice(String item, Map<String, ModelDef.Parameter> params, Map<String, Object> values) {
        String name = item.substring(PREFIX.length());
        ModelDef.Parameter p = params.get(name);
        if (p == null || !"field".equals(p.kind()) || p.fields().isEmpty()) {
            throw new DrishtiException(ErrorCode.REPORT_FIELD_PARAMETER, "'" + item + "' names no field parameter of the report's model");
        }
        Object chosen = values == null ? null : values.get(name);
        if (chosen != null) {
            if (!p.fields().contains(String.valueOf(chosen))) {
                throw new DrishtiException(ErrorCode.REPORT_FIELD_PARAMETER, name + " is one of " + p.fields() + ", not '" + chosen + "'");
            }
            return String.valueOf(chosen);
        }
        return p.defaultValue() != null ? String.valueOf(p.defaultValue()) : p.fields().get(0);
    }
}
