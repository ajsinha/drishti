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
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Type;
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
 * "Make a pack" (DRISHTI_ICEBERG.md section 15.2): each served kind's document as a JSON Schema, from the table's
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

    static ObjectNode of(KindConfig cfg, Shape s) {
        ObjectNode schema = F.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", cfg.kind());
        schema.put("x-drishti-kind", cfg.kind());
        schema.put("description", "Rows of the Iceberg table " + cfg.table() + " as " + cfg.kind() + " documents");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (NestedField f : s.documentColumns()) {
            String name = cfg.nameOf(f.name());
            ObjectNode p = type(f.type());
            if (f.doc() != null) {
                p.put("description", f.doc());
            }
            if (s.idFields().size() == 1 && s.idFields().get(0).id() == f.id()) {
                p.put("x-drishti-role", "key");
            }
            if (s.dateField() != null && s.dateField().id() == f.id()) {
                p.put("x-drishti-role", "date");
            }
            if (cfg.links().containsKey(name)) {
                p.put("x-drishti-link", cfg.links().get(name));
            }
            props.set(name, p);
        }
        return schema;
    }

    private static ObjectNode type(Type t) {
        ObjectNode n = F.objectNode();
        switch (t) {
            case Type.Primitive p -> {
                switch (p.id()) {
                    case BOOLEAN -> n.put("type", "boolean");
                    case INT, LONG -> n.put("type", "integer");
                    case FLOAT, DOUBLE, DECIMAL -> n.put("type", "number");
                    case DATE -> n.put("type", "string").put("format", "date");
                    case TIME -> n.put("type", "string").put("format", "time");
                    case TIMESTAMP, TIMESTAMPTZ -> n.put("type", "string").put("format", "date-time");
                    case UUID -> n.put("type", "string").put("format", "uuid");
                    case FIXED, BINARY -> n.put("type", "string").put("contentEncoding", "base64");
                    default -> n.put("type", "string");
                }
            }
            case Type.Struct s -> {
                n.put("type", "object");
                ObjectNode props = n.putObject("properties");
                s.fields().forEach(f -> props.set(f.name(), type(f.type())));
            }
            case Type.ListType l -> {
                n.put("type", "array");
                n.set("items", type(l.element()));
            }
            case Type.MapType m -> {
                n.put("type", "object");
                n.set("additionalProperties", type(m.value()));
            }
        }
        return n;
    }

    /** {@code --out DIR --set key=value …}: writes {@code <kind>.schema.json} for each served kind. */
    public static void main(String[] args) throws Exception {
        Map<String, String> settings = new HashMap<>();
        Path out = Path.of("schemas");
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[i + 1]);
                case "--set" -> settings.put(args[i + 1].substring(0, args[i + 1].indexOf('=')), args[i + 1].substring(args[i + 1].indexOf('=') + 1));
                default -> throw new IllegalArgumentException("unknown option " + args[i] + ": --out DIR --set key=value");
            }
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
