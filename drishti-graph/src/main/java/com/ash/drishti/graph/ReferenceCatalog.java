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
package com.ash.drishti.graph;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Knows which identifiers and fields name other entities. Built from {@link GraphProperties} and rebuilt when packs
 * change ({@link com.ash.drishti.common.LiveSettings}); thread-safe.
 */
public final class ReferenceCatalog implements com.ash.drishti.common.LiveSettings {

    private record Compiled(Pattern pattern, String kind) {}

    /** Patterns and fields as one unit, swapped whole when packs change: a reader sees the old pair or the new, never one of each. */
    private record State(List<Compiled> patterns, Map<String, GraphProperties.FieldRef> fields) {
        static State of(GraphProperties props) {
            List<Compiled> out = new ArrayList<>();
            props.idPatterns().forEach(p -> out.add(new Compiled(Pattern.compile(p.pattern()), p.kind())));
            return new State(List.copyOf(out), props.fields());
        }
    }

    private volatile State state;

    public ReferenceCatalog(GraphProperties props) {
        this.state = State.of(props);
    }

    @Override
    public Runnable prepare(org.springframework.core.env.Environment candidate) {
        State next = State.of(GraphProperties.of(candidate));
        return () -> state = next;
    }

    /** The kind an identifier belongs to, judged by the configured patterns. */
    public Optional<String> kindOf(String id) {
        for (Compiled c : state.patterns()) {
            if (c.pattern.matcher(id).find()) {
                return Optional.of(c.kind);
            }
        }
        return Optional.empty();
    }

    /** The kind of entity a field of this name refers to, per the configured fields ({@code nettingSet} is a netting set). */
    public Optional<String> kindOfField(String field) {
        GraphProperties.FieldRef ref = field == null ? null : state.fields().get(field);
        return ref == null ? Optional.empty() : Optional.of(ref.kind());
    }

    /** The configured fields that name entities of {@code kind} ({@code counterparty} for counterparties), sorted. */
    public List<String> fieldsNaming(String kind) {
        return state.fields().entrySet().stream().filter(e -> kind.equals(e.getValue().kind())).map(Map.Entry::getKey).sorted().toList();
    }

    /**
     * References in {@code doc}: configured fields holding an id (a string, an array of strings, or an
     * object with {@code id}), in document order, without duplicates and without {@code self}. A masked value
     * ({@link DataNode#MASK}) is no reference.
     */
    public List<LinkRef> discover(DataNode doc, EntityRef self) {
        List<LinkRef> out = new ArrayList<>();
        Map<String, GraphProperties.FieldRef> fields = state.fields();
        Set<EntityRef> seen = new LinkedHashSet<>();
        seen.add(self);
        if (!(doc instanceof DataNode.Obj o)) {
            return out;
        }
        o.fields().forEach((field, value) -> {
            GraphProperties.FieldRef ref = fields.get(field);
            if (ref == null) {
                return;
            }
            String label = ref.label() == null ? humanize(field) : ref.label();
            if (value instanceof DataNode.Arr a) {
                for (int i = 0; i < a.size(); i++) {
                    add(out, seen, label, ref.kind(), a.get(i), "$." + field + "[" + i + "]");
                }
            } else {
                add(out, seen, label, ref.kind(), value, "$." + field);
            }
        });
        return out;
    }

    private static void add(List<LinkRef> out, Set<EntityRef> seen, String label, String kind, DataNode v, String path) {
        if (v.isMasked() || v.get("id").isMasked()) {
            return;                                    // a masked reference names nothing the caller may see
        }
        String id = v instanceof DataNode.Obj ? v.get("id").asText() : v.asText();
        if (id.isEmpty()) {
            return;
        }
        String display = v instanceof DataNode.Obj && !v.get("name").isNull() ? v.get("name").asText() : id;
        EntityRef target = EntityRef.of(kind, id);
        if (seen.add(target)) {
            out.add(new LinkRef(label, target, display, path));
        }
    }

    static String humanize(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isUpperCase(c) && i > 0 ? " " + Character.toLowerCase(c) : String.valueOf(c));
        }
        return Character.toUpperCase(sb.charAt(0)) + sb.substring(1);
    }
}
