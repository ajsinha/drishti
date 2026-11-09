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
package com.ash.drishti.server.pivot;

import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.Dim;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Cell comments on a saved pivot view (ARCHITECTURE.md section 26.7, item 10; PIVOT_GUIDE.md, cell comments): a note on one cell (who, when,
 * the text, resolved or open), kept with the view's versions ({@code pivot/view-versions/<owner>/<name>/comments.json}, beside the numbered
 * versions) and stamped with the version it was written on, so a comment follows the view through its saves and says when it was made on an
 * older definition. A cell is named by its coordinates (dimension to member, as the answer's {@code coords}), never by its place on the
 * screen, so a comment stays on its cell when the view is rearranged; a comment may name one measure of the cell.
 *
 * <p>Anyone who may open the view reads and writes its comments; the author or the view's owner resolves, reopens and deletes one. Text is
 * 1 to {@value #MAX_TEXT} characters; a view keeps at most {@value #MAX_COMMENTS}. The controller audits every change (never the text).
 */
public final class PivotCellComments {

    static final int MAX_TEXT = 2_000;
    static final int MAX_COMMENTS = 1_000;
    private static final String FILE = "comments.json";

    private final PivotViews views;
    private final Clock clock;

    public PivotCellComments(PivotViews views, Clock clock) {
        this.views = views;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Where a view's comments are kept: beside its numbered versions. */
    static String key(String owner, String name) {
        return "pivot/view-versions/" + ContentDocs.name(owner) + "/" + PivotViews.slug(name) + "/" + FILE;
    }

    private static DrishtiException bad(String words) {
        return new DrishtiException(ErrorCode.PIVOT_COMMENT, words);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> current(Principal p, String id) {
        return views.get(p, id).orElseThrow(() -> new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no saved pivot view " + id
                + " that you may open: comments are on saved views (save the view first)"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stored(Map<String, Object> view) {
        Map<String, Object> doc = views.docs().value(key(String.valueOf(view.get("owner")), String.valueOf(view.get("name"))), Map.class)
                .map(m -> (Map<String, Object>) m).orElse(Map.of());
        return doc.get("comments") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /**
     * The view's comments, oldest first ({@code cell}: one cell's, by its coordinates' text or the answer's cell key; {@code open}: only those not resolved), each with
     * {@code older: true} when it was written on an earlier version of the view.
     */
    public Map<String, Object> list(Principal p, String id, String cell, boolean open) {
        Map<String, Object> v = current(p, id);
        int version = ((Number) v.getOrDefault("version", 1)).intValue();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : stored(v)) {
            if (cell != null && !cell.isBlank() && !cell.strip().equals(c.get("cell")) && !cell.strip().equals(c.get("cellId"))) {
                continue;
            }
            if (open && c.get("resolved") != null) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>(c);
            e.put("older", ((Number) c.getOrDefault("version", version)).intValue() < version);
            out.add(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("version", version);
        m.put("comments", out);
        m.put("open", out.stream().filter(c -> c.get("resolved") == null).count());
        return m;
    }

    /**
     * A new comment {@code {coords, measure, text}} on a cell of the view: the coordinates' dimensions must be the view's (its rows, columns
     * and the dimensions its nodes open by); {@code measure} one of its values' labels or absent.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> add(Principal p, String id, Map<String, Object> body) {
        Map<String, Object> v = current(p, id);
        PivotView view = PivotView.parse((Map<String, Object>) v.get("view"));
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text")).strip();
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            throw bad("a comment is 1 to " + MAX_TEXT + " characters, not " + text.length());
        }
        Map<String, String> coords = new TreeMap<>();
        if (body.get("coords") instanceof Map<?, ?> m) {
            m.forEach((k, x) -> coords.put(String.valueOf(k), String.valueOf(x)));
        }
        Set<String> dims = dims(view);
        for (String d : coords.keySet()) {
            if (!dims.contains(d)) {
                throw bad(d + " is not a dimension of " + id + " (its dimensions: " + String.join(", ", dims) + "): comment on a cell of the view");
            }
        }
        if (coords.containsValue(CellKey.OTHERS)) {
            throw bad("an Others cell has no fixed members (it moves with the top N): comment on a member or a total");
        }
        String measure = body.get("measure") == null || String.valueOf(body.get("measure")).isBlank() ? null : String.valueOf(body.get("measure"));
        if (measure != null && view.values().stream().noneMatch(x -> x.label().equals(measure))) {
            throw bad("'" + measure + "' is not a value of " + id + "; its values: " + String.join(", ", view.values().stream()
                    .map(PivotView.Value::label).toList()));
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "c-" + UUID.randomUUID().toString().substring(0, 12));
        c.put("cell", CellKey.of(coords).canonical());           // readable: productType=IRS (the grand total: *)
        c.put("cellId", CellKey.of(coords).id());                // the answer's cell key, for the screen's marker
        c.put("coords", coords);
        c.put("label", coords.isEmpty() ? "grand total" : String.join(" · ", coords.values()));
        if (measure != null) {
            c.put("measure", measure);
        }
        c.put("text", text);
        c.put("by", p.user());
        c.put("at", clock.instant().toString());
        c.put("version", ((Number) v.getOrDefault("version", 1)).intValue());
        c.put("resolved", null);
        update(v, all -> {
            if (all.size() >= MAX_COMMENTS) {
                throw bad(id + " has " + all.size() + " comments, the most a view keeps: delete resolved ones first");
            }
            all.add(c);
        });
        return c;
    }

    /** Resolves ({@code resolved} true) or reopens a comment: its author or the view's owner. */
    public Map<String, Object> resolve(Principal p, String id, String commentId, boolean resolved) {
        Map<String, Object> v = current(p, id);
        Map<String, Object>[] out = new Map[1];
        update(v, all -> {
            Map<String, Object> c = find(all, commentId, id);
            mayChange(p, v, c);
            c.put("resolved", resolved ? Map.of("by", p.user(), "at", clock.instant().toString()) : null);
            out[0] = c;
        });
        return out[0];
    }

    /** Deletes a comment: its author or the view's owner. */
    public Map<String, Object> delete(Principal p, String id, String commentId) {
        Map<String, Object> v = current(p, id);
        Map<String, Object>[] out = new Map[1];
        update(v, all -> {
            Map<String, Object> c = find(all, commentId, id);
            mayChange(p, v, c);
            all.remove(c);
            out[0] = c;
        });
        return out[0];
    }

    private static Map<String, Object> find(List<Map<String, Object>> all, String commentId, String id) {
        return all.stream().filter(c -> String.valueOf(c.get("id")).equals(commentId)).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, id + " has no comment " + commentId));
    }

    private static void mayChange(Principal p, Map<String, Object> view, Map<String, Object> c) {
        if (!p.user().equals(c.get("by")) && !p.user().equals(view.get("owner"))) {
            throw bad("only the comment's author (" + c.get("by") + ") or the view's owner (" + view.get("owner") + ") changes it");
        }
    }

    /** The comments changed in one atomic update of their document. */
    @SuppressWarnings("unchecked")
    private void update(Map<String, Object> view, java.util.function.Consumer<List<Map<String, Object>>> change) {
        views.docs().update(key(String.valueOf(view.get("owner")), String.valueOf(view.get("name"))), Map.class, old -> {
            List<Map<String, Object>> all = new ArrayList<>();
            if (old != null && old.get("comments") instanceof List<?> l) {
                for (Object o : l) {
                    all.add(new LinkedHashMap<>((Map<String, Object>) o));
                }
            }
            change.accept(all);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("view", view.get("id"));
            m.put("comments", all);
            return m;
        });
    }

    /** The dimensions a cell of the view may name: its rows, its columns and the dimensions its nodes are opened by. */
    static Set<String> dims(PivotView view) {
        Set<String> s = new LinkedHashSet<>();
        view.rows().forEach(d -> s.add(d.id()));
        view.columns().forEach(d -> s.add(d.id()));
        for (PivotView.Expansion e : view.expanded()) {
            if (e.by() != null) {
                s.add(Dim.parse(e.by()).id());
            }
        }
        return s;
    }
}
