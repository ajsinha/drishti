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
package com.ash.drishti.engine.shape;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property: every sample validates against the schema inferred from the set it belongs to. */
class ShapePropertyTest {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final String[] KEYS = {"a", "b", "c", "d", "name", "id", "children", "date", "x y", "k.1"};
    private static final String[] WORDS = {"Live", "Failed", "USD", "EUR", "2026-09-01", "2026-09-01T10:00:00Z", "a@b.com", "hello world", "•••",
            "123e4567-e89b-12d3-a456-426614174000", "", "X6"};

    @Provide
    Arbitrary<List<JsonNode>> sampleSets() {
        return Arbitraries.longs().map(seed -> {
            Random r = new Random(seed);
            List<JsonNode> docs = new ArrayList<>();
            int n = 1 + r.nextInt(8);
            boolean uniform = r.nextInt(3) > 0;
            for (int i = 0; i < n; i++) {
                docs.add(uniform ? record(r, 0, 1.0) : value(r, 0));
            }
            return docs;
        });
    }

    @Property(tries = 400)
    void everySampleValidatesAgainstItsInferredSchema(@ForAll("sampleSets") List<JsonNode> docs) {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            samples.add(new Sample("s" + i, docs.get(i)));
        }
        Shape shape = ShapeTestSupport.service().infer(samples);
        MiniSchemaValidator v = new MiniSchemaValidator(shape.schema());
        for (JsonNode d : docs) {
            if (!v.validate(d).isEmpty()) {
                throw new AssertionError(minimal(docs, d.deepCopy()));
            }
        }
    }

    /** A failing set cut down: fewest documents, then fewest fields, that still fail; with the schema. */
    private static String minimal(List<JsonNode> docs, JsonNode failing) {
        List<JsonNode> keep = new ArrayList<>();
        docs.forEach(d -> keep.add(d.deepCopy()));
        for (int i = keep.size() - 1; i >= 0 && keep.size() > 1; i--) {
            JsonNode gone = keep.remove(i);
            if (!fails(keep)) {
                keep.add(i, gone);
            }
        }
        boolean again = true;
        while (again) {
            again = false;
            for (JsonNode doc : keep) {
                for (JsonNode n : nodes(doc)) {
                    List<Object> keys = new ArrayList<>();
                    if (n instanceof ObjectNode o) {
                        o.fieldNames().forEachRemaining(keys::add);
                    } else if (n instanceof ArrayNode a) {
                        for (int i = a.size() - 1; i >= 0; i--) {
                            keys.add(i);
                        }
                    }
                    for (Object k : keys) {
                        JsonNode saved = k instanceof String ks ? ((ObjectNode) n).remove(ks) : ((ArrayNode) n).remove((Integer) k);
                        if (fails(keep)) {
                            again = true;
                        } else if (k instanceof String ks) {
                            ((ObjectNode) n).set(ks, saved);
                        } else {
                            ((ArrayNode) n).insert((Integer) k, saved);
                        }
                    }
                }
            }
        }
        Shape s = ShapeTestSupport.service().infer(samples(keep));
        List<String> errs = new ArrayList<>();
        keep.forEach(d -> errs.addAll(new MiniSchemaValidator(s.schema()).validate(d)));
        return "minimal failing set " + keep + " errors " + errs + " schema " + s.schema();
    }

    private static List<JsonNode> nodes(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        out.add(n);
        n.forEach(c -> out.addAll(nodes(c)));
        return out;
    }

    private static boolean fails(List<JsonNode> docs) {
        Shape s = ShapeTestSupport.service().infer(samples(docs));
        MiniSchemaValidator v = new MiniSchemaValidator(s.schema());
        return docs.stream().anyMatch(d -> !v.validate(d).isEmpty());
    }

    private static List<Sample> samples(List<JsonNode> docs) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            out.add(new Sample("s" + i, docs.get(i)));
        }
        return out;
    }

    /** A record-shaped document: a stable-ish set of fields, some optional, nested records and lists. */
    private static JsonNode record(Random r, int depth, double keep) {
        ObjectNode o = F.objectNode();
        for (String k : KEYS) {
            if (r.nextDouble() < keep * 0.5) {
                o.set(k, depth > 3 ? scalar(r) : r.nextInt(4) == 0 ? record(r, depth + 1, 0.8) : value(r, depth + 1));
            }
        }
        return o;
    }

    private static JsonNode value(Random r, int depth) {
        int t = r.nextInt(depth > 3 ? 6 : 9);
        return switch (t) {
            case 6 -> record(r, depth + 1, 1.0);
            case 7 -> list(r, depth);
            case 8 -> mapLike(r, depth);
            default -> scalar(r);
        };
    }

    private static JsonNode list(Random r, int depth) {
        ArrayNode a = F.arrayNode();
        int n = r.nextInt(5);
        boolean records = r.nextBoolean();
        for (int i = 0; i < n; i++) {
            a.add(records ? record(r, depth + 1, 1.0) : value(r, depth + 1));
        }
        return a;
    }

    private static JsonNode mapLike(Random r, int depth) {
        ObjectNode o = F.objectNode();
        int n = r.nextInt(8);
        for (int i = 0; i < n; i++) {
            o.set("ID-" + r.nextInt(1000), r.nextBoolean() ? F.numberNode(r.nextInt(100)) : record(r, depth + 1, 0.5));
        }
        return o;
    }

    private static JsonNode scalar(Random r) {
        return switch (r.nextInt(6)) {
            case 0 -> F.nullNode();
            case 1 -> F.booleanNode(r.nextBoolean());
            case 2 -> F.numberNode(r.nextInt(1000) - 500);
            case 3 -> F.numberNode(r.nextDouble() * 100 - 50);
            default -> F.textNode(WORDS[r.nextInt(WORDS.length)]);
        };
    }
}
