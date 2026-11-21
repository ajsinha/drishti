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
package com.ash.drishti.plugin.icebergext;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.SourceContext;
import com.ash.lake.read.TableShape;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * "Make a pack" (plugins/drishti-plugin-iceberg-external/README.md section 9): each served kind's document as a JSON Schema, from the table's
 * schema: its fields (document names, types, docs as descriptions), the id ({@code x-drishti-role: key}), the business
 * date ({@code x-drishti-role: date}) and the {@code links} ({@code x-drishti-link}). The schemas feed the pack maker,
 * which drafts the kind, its fields and a Sutra listing them, to edit like any pack:
 *
 * <pre>
 *   java -cp &lt;plugin class path&gt; com.ash.drishti.plugin.icebergext.StarterSchemas --out schemas/ --set catalog=rest --set uri=… --set tables.trade.table=…
 *   tools/drishti.py pack make --schema schemas/ --name risk-mart
 * </pre>
 */
public final class StarterSchemas {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private StarterSchemas() {}

    /** One kind's schema from the table's columns as the product's read API reports them (a column's name and type; nested fields are not described). */
    static ObjectNode of(TableShape cfg, TableSnapshot t) {
        ObjectNode schema = F.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", cfg.kind());
        schema.put("x-drishti-kind", cfg.kind());
        schema.put("description", "Rows of the Iceberg table " + cfg.table() + " as " + cfg.kind() + " documents");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (Column f : t.columns().all()) {
            if (cfg.exclude().contains(f.name()) || f.name().equals(cfg.documentColumn())) {
                continue;
            }
            String name = cfg.nameOf(f.name());
            ObjectNode p = type(f.type());
            if (f.name().equals(t.idColumn()) || cfg.id().size() == 1 && cfg.id().get(0).equals(f.name())) {
                p.put("x-drishti-role", "key");
            }
            if (f.name().equals(cfg.date())) {
                p.put("x-drishti-role", "date");
            }
            if (cfg.links().containsKey(name)) {
                p.put("x-drishti-link", cfg.links().get(name));
            }
            props.set(name, p);
        }
        return schema;
    }

    private static ObjectNode type(ColumnType t) {
        ObjectNode n = F.objectNode();
        switch (t) {
            case BOOLEAN -> n.put("type", "boolean");
            case INT, LONG -> n.put("type", "integer");
            case DOUBLE -> n.put("type", "number");
            case DATE -> n.put("type", "string").put("format", "date");
            case TIMESTAMP -> n.put("type", "string").put("format", "date-time");
            case BINARY -> n.put("type", "string").put("contentEncoding", "base64");
            case STRING_LIST -> n.put("type", "array").putObject("items").put("type", "string");
            case DOUBLE_LIST, FLOAT_LIST -> n.put("type", "array").putObject("items").put("type", "number");
            default -> n.put("type", "string");
        }
        return n;
    }

    /** {@code --out DIR --set key=value …}: writes {@code <kind>.schema.json} for each served kind. */
    public static void main(String[] args) throws Exception {
        Map<String, String> settings = new HashMap<>();
        Path out = null;                                       // named outright: never a folder of the working folder by default
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[i + 1]);
                case "--set" -> settings.put(args[i + 1].substring(0, args[i + 1].indexOf('=')), args[i + 1].substring(args[i + 1].indexOf('=') + 1));
                default -> throw new IllegalArgumentException("unknown option " + args[i] + ": --out DIR --set key=value");
            }
        }
        if (out == null) {
            throw new IllegalArgumentException("--out DIR is required: the folder the schemas are written to (an absolute path)");
        }
        settings.put("refresh-seconds", "3600");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        IcebergExternalPlugin p = new IcebergExternalPlugin();
        p.start(new SourceContext() {
            @Override
            public Map<String, String> settings() {
                return settings;
            }

            @Override
            public DataNode parseJson(InputStream in) {
                throw new UnsupportedOperationException("not needed to describe the tables");
            }

            @Override
            public ScheduledExecutorService scheduler() {
                return scheduler;
            }
        });
        try {
            Files.createDirectories(out);
            ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            for (Map.Entry<String, ObjectNode> e : p.starterSchemas().entrySet()) {
                Path f = out.resolve(e.getKey() + ".schema.json");
                Files.writeString(f, json.writeValueAsString(e.getValue()) + "\n");
                System.out.println("written " + f);
            }
            p.notServed().forEach((k, why) -> System.err.println("not served: " + why));
        } finally {
            p.close();
            scheduler.shutdownNow();
        }
    }
}
