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
package com.ash.drishti.inference;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.model.StripItem;
import com.ash.drishti.rachana.model.Title;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs the rules over a document, keeps the best candidate per document path, and packs the winners into
 * the strip, the main column and the right column under the density limits. Deterministic, stateless and
 * thread-safe; callers cache results by shape fingerprint.
 */
public final class InferenceEngine {

    private final Semantics semantics;
    private final ColumnInference columns;
    private final List<InferenceRule> rules;

    public InferenceEngine(Semantics semantics, List<InferenceRule> rules) {
        this.semantics = semantics;
        this.columns = new ColumnInference(semantics);
        this.rules = List.copyOf(rules);
    }

    public ColumnInference columns() {
        return columns;
    }

    public Semantics semantics() {
        return semantics;
    }

    public InferredLayout infer(DataNode doc, String kind) {
        RuleContext ctx = new RuleContext(doc, kind, semantics, columns);
        List<Candidate> all = new ArrayList<>();
        for (InferenceRule r : rules) {
            r.propose(ctx, all);
        }
        LayoutPacker.Packed packed = LayoutPacker.pack(all, semantics.limit("main", 6), semantics.limit("right", 4) - 1);
        Title title = title(doc, kind);
        return new InferredLayout(title, strip(doc, kind, title), packed.panels(), packed.why());
    }

    /** {@code [Humanized kind · product] ID with counterparty}. */
    Title title(DataNode doc, String kind) {
        String idField = semantics.idFields(kind).stream().filter(f -> doc.get(f).type().isScalar() && !doc.get(f).isNull())
                .findFirst().orElseGet(() -> firstIdLike(doc));
        String pill = Semantics.humanize(kind.replace('-', ' '));
        String product = doc.get("product").asText();
        if (!product.isEmpty()) {
            pill = pill + " · " + product;
        }
        String with = null;
        DataNode cp = doc.get("counterparty");
        if (cp instanceof DataNode.Obj && !cp.get("id").isNull()) {
            with = "link($.counterparty.id, 'counterparty', $.counterparty.name)";
        } else if (cp.type().isScalar() && !cp.isNull()) {
            with = "link($.counterparty, 'counterparty')";
        }
        return new Title(pill, idField == null ? "'" + kind + "'" : "$." + idField, with);
    }

    /** Fallback: the first top-level text field named {@code ...Id} (for example {@code optionId}). */
    private static String firstIdLike(DataNode doc) {
        if (doc instanceof DataNode.Obj o) {
            for (Map.Entry<String, DataNode> e : o.fields().entrySet()) {
                if (e.getKey().endsWith("Id") && e.getValue().type() == com.ash.drishti.api.NodeType.STRING) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    /** Up to eight top-level scalars, ranked by role weight, shown in document order. */
    List<StripItem> strip(DataNode doc, String kind, Title title) {
        if (!(doc instanceof DataNode.Obj o)) {
            return List.of();
        }
        Set<String> skip = new HashSet<>(Set.of("product", "productType", "counterparty"));
        if (title.id().startsWith("$.")) {
            skip.add(title.id().substring(2));
        }
        record Scored(String field, Role role, int order) {}
        List<Scored> scored = new ArrayList<>();
        int[] i = {0};
        o.fields().forEach((k, v) -> {
            i[0]++;
            if (v.type().isScalar() && !v.isNull() && !skip.contains(k)) {
                scored.add(new Scored(k, semantics.role(k, v), i[0]));
            }
        });
        scored.sort(Comparator.comparingInt((Scored s) -> -s.role().weight()).thenComparingInt(Scored::order));
        List<Scored> top = new ArrayList<>(scored.subList(0, Math.min(semantics.limit("strip", 8), scored.size())));
        top.sort(Comparator.comparingInt(Scored::order));
        boolean emphasised = false;
        List<StripItem> out = new ArrayList<>();
        for (Scored s : top) {
            boolean emph = !emphasised && "signed-money".equals(s.role().name()) && s.field().toLowerCase(Locale.ROOT).contains("mtm");
            emphasised |= emph;
            out.add(new StripItem(Semantics.humanize(s.field()), "$." + s.field(), s.role().fmt(), s.role().tone(), emph, Rules.INFERRED));
        }
        return out;
    }
}
