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
import com.ash.drishti.server.bi.expr.DelTablesTranslator;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.Flattener;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ModelDefParser;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import com.ash.drishti.tables.migrate.TablesMigrator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A test's BI fixture moved onto the table layer the way an installation's files are (docs/architecture/ARCHITECTURE.md §28 section 4): {@code tables migrate}
 * over the fixture's text, in memory, then the layer's tables and model read as the server reads them. A BI dataset of the old forms becomes
 * tables and a model; BI's compiler then plans over the model, flattened ({@link #dataset}). A table or model of the layer is read as it is.
 */
public final class Layered {

    private Layered() {}

    /** What the migration wrote: file name (relative to the tables folder) to text. */
    public static Map<String, String> migrate(Map<String, String> biFiles) {
        try {
            TablesMigrator.Plan p = new TablesMigrator(new DelTablesTranslator()).plan(biFiles, null);
            p.items().stream().filter(i -> i.refusal() != null).findFirst().ifPresent(i -> {
                throw new IllegalStateException(i.source() + ": " + i.refusal());
            });
            if (Boolean.getBoolean("drishti.debug.layered")) {
                biFiles.forEach((k, v) -> System.out.println("LAYERED-IN " + k + "\n" + v));
                p.writes().forEach((k, v) -> System.out.println("LAYERED-OUT " + k + "\n" + v));
            }
            return p.writes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One BI file of the old forms (a dataset of version 1 or 2, or a model of version 2) migrated: the layer's texts it became. */
    public static Map<String, String> migrate(String yaml) {
        String file = yaml.lines().anyMatch(l -> l.startsWith("model:")) ? "models/fixture.model.yaml" : "datasets/fixture.dataset.yaml";
        return migrate(Map.of(file, yaml));
    }

    /** The table one {@code rachana: 2} dataset text becomes (a text that is already a table is returned as it is). */
    public static String table(String yaml) {
        if (DatasetDefParser.isTable(yaml)) {
            return yaml;
        }
        List<String> tables = migrate(yaml).entrySet().stream().filter(e -> e.getKey().endsWith(".table.yaml")).map(Map.Entry::getValue).toList();
        if (tables.size() > 1) {                                               // a grouping is its rows and the table of the groups (Q13): the one that was the dataset
            String name = yaml.lines().filter(l -> l.startsWith("dataset:")).map(l -> l.substring("dataset:".length()).strip()).findFirst().orElse("");
            List<String> was = tables.stream().filter(t -> t.lines().anyMatch(l -> l.strip().equals("was: " + name))).toList();
            if (was.size() == 1) {
                return was.get(0);
            }
        }
        if (tables.size() != 1) {
            throw new IllegalStateException("the dataset became " + tables.size() + " tables");
        }
        return tables.get(0);
    }

    /** The model one {@code rachana: 2} model text becomes (a model of the layer is returned as it is). */
    public static String model(String yaml) {
        if (yaml.lines().anyMatch(l -> l.strip().equals("rachana: 3"))) {
            return yaml;
        }
        return migrate(yaml).entrySet().stream().filter(e -> e.getKey().endsWith(".model.yaml")).map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new IllegalStateException("no model was written"));
    }

    /**
     * Several {@code rachana: 2} dataset texts migrated in one run, as an installation's folder is (a narrowing reads its base's columns), each
     * read as the table it becomes; texts that are tables already are read as they are. A grouping gives its rows' table too.
     */
    public static List<DatasetDefParser.Result> tableDefs(String... yamls) {
        Map<String, String> files = new LinkedHashMap<>();
        List<DatasetDefParser.Result> out = new ArrayList<>();
        for (int i = 0; i < yamls.length; i++) {
            if (DatasetDefParser.isTable(yamls[i])) {
                out.add(DatasetDefParser.parse(yamls[i]));
            } else {
                files.put("datasets/d" + i + ".dataset.yaml", yamls[i]);
            }
        }
        if (!files.isEmpty()) {
            migrate(files).entrySet().stream().filter(e -> e.getKey().endsWith(".table.yaml")).forEach(e -> out.add(DatasetDefParser.parse(e.getValue())));
        }
        return out;
    }

    /** A {@code rachana: 2} dataset text (or a table) read as the table it becomes. */
    public static DatasetDefParser.Result tableDef(String yaml) {
        try {
            return DatasetDefParser.parse(table(yaml));
        } catch (IllegalStateException refused) {                            // what the migration refuses is a problem of the text, as the layer would say it
            String why = refused.getMessage() == null ? "" : refused.getMessage();
            java.util.regex.Matcher code = java.util.regex.Pattern.compile("DRS-\\d{4}").matcher(why);
            return new DatasetDefParser.Result(null, List.of(new DatasetParser.Problem("error", code.find() ? code.group() : "DRS-9127", "", why)));
        }
    }

    /** A {@code rachana: 2} model text (or a model of the layer) read as the model it becomes. */
    public static ModelDefParser.Result modelDef(String yaml) {
        return ModelDefParser.parse(model(yaml));
    }

    /**
     * BI's dataset over the layer: an old-form dataset migrated into tables and a model, the model flattened over its tables as the model catalogue
     * does (a table of the layer is its own one-table dataset). The problems of every step are in the result.
     */
    public static DatasetParser.Result dataset(String yaml) {
        if (DatasetDefParser.isTable(yaml)) {
            return DatasetParser.parse(yaml);
        }
        Map<String, String> out;
        try {
            out = migrate(yaml);
        } catch (IllegalStateException refused) {                            // what the migration refuses is a problem of the text, as the layer would say it
            String why = refused.getMessage() == null ? "" : refused.getMessage();
            java.util.regex.Matcher code = java.util.regex.Pattern.compile("DRS-\\d{4}").matcher(why);
            return new DatasetParser.Result(null, List.of(new DatasetParser.Problem("error", code.find() ? code.group() : "DRS-9127", "", why)));
        }
        Map<String, String> tables = new LinkedHashMap<>();
        String model = null;
        for (Map.Entry<String, String> e : out.entrySet()) {
            if (e.getKey().endsWith(".model.yaml")) {
                model = e.getValue();
            } else if (e.getKey().endsWith(".table.yaml")) {
                tables.put(e.getKey(), e.getValue());
            }
        }
        if (model == null) {
            if (tables.size() == 1) {
                return DatasetParser.parse(tables.values().iterator().next());
            }
            throw new IllegalStateException("the migration wrote no model: " + out.keySet());
        }
        return flatten(model, new ArrayList<>(tables.values()));
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
