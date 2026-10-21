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
package com.ash.drishti.server.ui;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The proof that a converted pack shows the same screens (docs/architecture/UI_BOARD.md 11.3, phase P5; decision 6 of 14.1: same data plus
 * structure, no pixels). For each entity page of the pack, a sample of its records and each test person: the old page (the Sutra, as the view
 * route builds it) and the board (its chips painted over the record its supplier reads as that person) are built, normalised and compared node
 * by node: the panel tree, its order and titles, and every value, mask, total and link. A screen is proven when every sample agrees for every
 * person; otherwise the report names the first difference with its path ({@code panels[3].data.rows[0].cells[2].text}).
 *
 * <p>Normalising drops what names the builder rather than the screen: the view's {@code provenance} and {@code timings}, and the data of a
 * {@code provenance} panel ("how this view was built" names the Sutra or the board).
 */
public final class BoardProof {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PackScreens screens;
    private final ViewPipeline pipeline;
    private final SutraMatcher matcher;
    private final StructuredSearch search;
    private final Entitlements entitlements;
    private final BoardSuppliers suppliers;

    public BoardProof(PackScreens screens, ViewPipeline pipeline, SutraMatcher matcher, StructuredSearch search, Entitlements entitlements,
            BoardSuppliers suppliers) {
        this.screens = screens;
        this.pipeline = pipeline;
        this.matcher = matcher;
        this.search = search;
        this.entitlements = entitlements;
        this.suppliers = suppliers;
    }

    /**
     * Proves a pack's entity pages.
     *
     * @param pack    the pack
     * @param users   the test people (at least one per access profile: full, masked, row-restricted)
     * @param samples records per page (the first found per kind, matched to their page)
     * @param ids     records to include, per kind (a derived kind with no search of its own needs them)
     * @param boards  the boards to prove, by Sutra name (null: the pack's own conversions); a test swaps a chip here
     * @return the report: per screen, proven or the first difference
     */
    public ObjectNode prove(String pack, List<Principal> users, int samples, Map<String, List<String>> ids, Map<String, PackScreens.Served> boards) {
        Map<String, PackScreens.Served> served = boards != null ? boards : screens.boards(pack);
        Map<String, Set<String>> kinds = new LinkedHashMap<>();
        served.values().forEach(s -> {
            if (s.conversion().kind() != null) {
                kinds.computeIfAbsent(s.conversion().kind(), k -> new LinkedHashSet<>());
            }
        });
        if (ids != null) {
            ids.forEach((k, v) -> kinds.computeIfAbsent(k, x -> new LinkedHashSet<>()).addAll(v));
        }
        Map<String, List<EntityRef>> bySutra = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> k : kinds.entrySet()) {
            Set<String> found = new LinkedHashSet<>(k.getValue());
            found.addAll(sample(k.getKey(), 2000));
            for (String id : found) {
                EntityRef ref = EntityRef.of(k.getKey(), id);
                try {
                    EntityDocument doc = pipeline.document(ref, AsOf.LATEST);
                    Optional<Sutra> s = matcher.match(ref.kind(), doc.data());
                    if (s.isPresent() && served.containsKey(s.get().name())) {
                        List<EntityRef> l = bySutra.computeIfAbsent(s.get().name(), x -> new ArrayList<>());
                        if (l.size() < samples) {
                            l.add(ref);
                        }
                    }
                } catch (RuntimeException e) {
                    // a record that cannot be read is no sample
                }
            }
        }
        ObjectNode r = JSON.createObjectNode();
        r.put("pack", pack);
        ArrayNode us = r.putArray("users");
        users.forEach(u -> us.add(u.user()));
        ArrayNode items = r.putArray("screens");
        int proven = 0;
        int differ = 0;
        int unsampled = 0;
        int pairs = 0;
        for (Map.Entry<String, PackScreens.Served> e : served.entrySet()) {
            PackScreens.Served s = e.getValue();
            ObjectNode o = items.addObject();
            o.put("screen", s.conversion().screen()).put("board", s.conversion().board());
            List<EntityRef> refs = bySutra.getOrDefault(e.getKey(), List.of());
            if (s.painted() == null) {
                o.put("result", "not converted").put("reason", s.conversion().reason());
                differ++;
                continue;
            }
            if (refs.isEmpty()) {
                o.put("result", "no sample");
                unsampled++;
                continue;
            }
            ObjectNode diff = null;
            int compared = 0;
            for (EntityRef ref : refs) {
                for (Principal u : users) {
                    diff = compare(ref, u, s.painted());
                    compared++;
                    if (diff != null) {
                        break;
                    }
                }
                if (diff != null) {
                    break;
                }
            }
            o.put("records", refs.size()).put("compared", compared);
            pairs += compared;
            if (diff == null) {
                o.put("result", "proven");
                proven++;
            } else {
                o.put("result", "differs").set("difference", diff);
                differ++;
            }
        }
        r.put("proven", proven).put("differs", differ).put("noSample", unsampled).put("comparisons", pairs);
        return r;
    }

    private List<String> sample(String kind, int max) {
        try {
            StructuredSearch.Result res = search.run(SearchQuery.parse(kind + " limit " + Math.min(max, SearchQuery.MAX_LIMIT)), AsOf.LATEST,
                    UnaryOperator.identity());
            return res.rows().stream().map(row -> row.ref().id()).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Null when the old page and the board agree for this person, else the first difference. */
    ObjectNode compare(EntityRef ref, Principal who, Sutra painted) {
        JsonNode old;
        try {
            old = normal(entitlements.restrict(who, pipeline.view(ref, AsOf.LATEST, entitlements.redactor(who), k -> entitlements.mayOpen(who, k))));
        } catch (RuntimeException e) {
            old = JSON.createObjectNode().put("error", String.valueOf(e.getMessage()));
        }
        JsonNode board;
        try {
            EntityDocument doc = suppliers instanceof ServerBoardSuppliers sb ? sb.document(who, ref.kind(), ref.id()) : pipeline.document(ref, AsOf.LATEST);
            board = normal(entitlements.restrict(who, pipeline.builtPreview(Optional.of(painted), doc, entitlements.redactor(who),
                    k -> entitlements.mayOpen(who, k)).view()));
        } catch (RuntimeException e) {
            board = JSON.createObjectNode().put("error", String.valueOf(e.getMessage()));
        }
        if (!entitlements.mayOpen(who, ref.kind())) {
            // a person who may not open the kind: the old page is a refusal and the board's supplier must refuse too (no record painted)
            return board.has("error") ? null : JSON.createObjectNode().put("user", who.user()).put("record", ref.kind() + "/" + ref.id())
                    .put("path", "(access)").put("page", "refused").put("board", "painted");
        }
        String path = firstDifference(old, board, "");
        if (path == null) {
            return null;
        }
        ObjectNode d = JSON.createObjectNode();
        d.put("user", who.user()).put("record", ref.kind() + "/" + ref.id()).put("path", path.isEmpty() ? "(view)" : path);
        d.put("page", text(at(old, path))).put("board", text(at(board, path)));
        return d;
    }

    /** A view as compared: without what names its builder. */
    static JsonNode normal(ViewModel v) {
        ObjectNode n = JSON.valueToTree(v);
        n.remove("provenance");
        n.remove("timings");
        for (JsonNode p : n.path("panels")) {
            if ("provenance".equals(p.path("kind").asText()) && p instanceof ObjectNode o) {
                o.remove("data");
            }
        }
        return n;
    }

    /** The path of the first node where {@code a} and {@code b} differ, or null when they are equal. */
    static String firstDifference(JsonNode a, JsonNode b, String at) {
        if (a == null || b == null || a.getNodeType() != b.getNodeType()) {
            return a == null && b == null ? null : at;
        }
        if (a.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                String d = firstDifference(a.get(k), b.get(k), at.isEmpty() ? k : at + "." + k);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        if (a.isArray()) {
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                String d = firstDifference(a.get(i), b.get(i), at + "[" + i + "]");
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        return a.equals(b) ? null : at;
    }

    private static JsonNode at(JsonNode n, String path) {
        JsonNode cur = n;
        if (path.isEmpty()) {
            return cur;
        }
        for (Iterator<String> it = List.of(path.split("\\.")).iterator(); it.hasNext() && cur != null; ) {
            String seg = it.next();
            String name = seg.contains("[") ? seg.substring(0, seg.indexOf('[')) : seg;
            if (!name.isEmpty()) {
                cur = cur.get(name);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(seg);
            while (cur != null && m.find()) {
                cur = cur.get(Integer.parseInt(m.group(1)));
            }
        }
        return cur;
    }

    private static String text(JsonNode n) {
        if (n == null) {
            return "(absent)";
        }
        String s = n.isValueNode() ? n.asText() : n.toString();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
