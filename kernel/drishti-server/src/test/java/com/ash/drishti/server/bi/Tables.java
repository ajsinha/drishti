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
package com.ash.drishti.server.bi;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.Flattener;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ModelDefParser;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A test's BI fixture as the table layer reads it: tables ({@code rachana: 3}, {@code table:}) and models ({@code model:}), read as the server reads them.
 * BI's compiler plans over a model flattened over its tables ({@link #flatten}); a table on its own is its own one-table dataset ({@link #dataset}).
 */
public final class Tables {

    private Tables() {}

    /**
     * The tables and the model among these texts (a text that has a {@code model:} line is the model), flattened as the model catalogue does; with no model,
     * the one table, as BI's dataset.
     */
    public static DatasetParser.Result dataset(String... texts) {
        List<String> tables = new ArrayList<>();
        String model = null;
        for (String t : texts) {
            if (t.lines().anyMatch(l -> l.startsWith("model:"))) {
                model = t;
            } else {
                tables.add(t);
            }
        }
        if (model == null) {
            if (tables.size() != 1) {
                throw new IllegalStateException("no model among " + texts.length + " texts");
            }
            return DatasetParser.parse(tables.get(0));
        }
        return flatten(model, tables);
    }

    /** The sample models and tables kept in the test resources ({@code bi/samples/tables/bi}). */
    private static final java.nio.file.Path SAMPLES = java.nio.file.Path.of(com.ash.lake.tck.TestRoots.module(), "src", "test", "resources", "bi", "samples");

    /** The folder of the sample reports. */
    public static java.nio.file.Path sampleReports() {
        return SAMPLES.resolve("reports");
    }

    /** The folder of the sample models and tables (a model file is {@code <name>.model.yaml}, its tables {@code <name with underscores>_<table>.table.yaml}). */
    public static java.nio.file.Path sampleTables() {
        return SAMPLES.resolve("tables").resolve("bi");
    }

    /** The text of a sample model. */
    public static String sampleModel(String name) {
        try {
            return java.nio.file.Files.readString(sampleTables().resolve(name + ".model.yaml"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The texts of the tables a sample model names. */
    public static String[] sampleTableTexts(String name) {
        List<String> out = new ArrayList<>();
        try {
            for (java.util.regex.Matcher m = java.util.regex.Pattern.compile("table: bi\\.([A-Za-z0-9_]+)").matcher(sampleModel(name)); m.find();) {
                out.add(java.nio.file.Files.readString(sampleTables().resolve(m.group(1) + ".table.yaml")));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toArray(String[]::new);
    }

    /** A sample model flattened over its tables. */
    public static DatasetParser.Result sample(String name) {
        return flatten(sampleModel(name), List.of(sampleTableTexts(name)));
    }

    /** One table text read as the layer reads it. */
    public static DatasetDefParser.Result tableDef(String table) {
        return DatasetDefParser.parse(table);
    }

    /** Several table texts, each read as the layer reads it. */
    public static List<DatasetDefParser.Result> tableDefs(String... tables) {
        List<DatasetDefParser.Result> out = new ArrayList<>();
        for (String t : tables) {
            out.add(DatasetDefParser.parse(t));
        }
        return out;
    }

    /** One model text read as the layer reads it. */
    public static ModelDefParser.Result modelDef(String model) {
        return ModelDefParser.parse(model);
    }

    /** A model of the layer flattened over the given tables of the layer (each by its name). */
    public static DatasetParser.Result flatten(String model, List<String> tableTexts) {
        List<DatasetParser.Problem> problems = new ArrayList<>();
        Map<String, DatasetDef> byName = new LinkedHashMap<>();
        for (String t : tableTexts) {
            DatasetDefParser.Result r = DatasetDefParser.parse(t);
            problems.addAll(r.problems());
            if (r.dataset() != null) {
                byName.put(r.dataset().name(), r.dataset());
            }
        }
        ModelDefParser.Result m = ModelDefParser.parse(model);
        problems.addAll(m.problems());
        if (m.model() == null) {
            return new DatasetParser.Result(null, problems);
        }
        Map<String, ResolvedDataset> resolved = new LinkedHashMap<>();
        for (ModelDef.Use u : m.model().datasets().values()) {
            DatasetDef d = byName.get(u.dataset());
            if (d == null) {
                throw new IllegalStateException("the model reads " + u.dataset() + ", which is not among " + byName.keySet());
            }
            resolved.put(u.alias(), resolve(d, byName));
        }
        Flattener.Flat flat = Flattener.flatten(m.model(), resolved);
        problems.addAll(flat.problems());
        problems.addAll(DatasetParser.check(flat.dataset()));
        return new DatasetParser.Result(flat.dataset(), problems);
    }

    private static ResolvedDataset resolve(DatasetDef d, Map<String, DatasetDef> byName) {
        if (d.extendsRef() == null) {
            return ResolvedDataset.of(d);
        }
        DatasetDef base = byName.get(d.extendsRef());
        if (base == null) {
            throw new IllegalStateException(d.name() + " extends " + d.extendsRef() + ", which is not among " + byName.keySet());
        }
        return ResolvedDataset.extend(d, resolve(base, byName));
    }
}
