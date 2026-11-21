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
package com.ash.drishti.engine.rachana;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.common.Fingerprint;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.pivot.PivotProperties;
import com.ash.drishti.graph.BadgeRenderer;
import com.ash.drishti.graph.LinkRef;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.rachana.bind.BindContext;
import com.ash.rachana.bind.BindHooks;
import com.ash.rachana.bind.Binder;
import com.ash.rachana.bind.Entity;
import com.ash.rachana.bind.LinkResolver;
import com.ash.rachana.bind.PanelLimits;
import com.ash.rachana.bind.PivotEngine;
import com.ash.rachana.bind.PivotLimits;
import com.ash.rachana.el.ElCompiler;
import com.ash.rachana.format.Formats;
import com.ash.rachana.model.PanelKind;
import com.ash.rachana.view.PanelData;
import com.ash.rachana.view.ViewModel.Cell;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drishti's binder: Rachana's one {@link Binder} with Drishti's profile hooks (SPLIT_PLAN 8.3e). Drishti keeps only what is its own:
 * links (an id's kind from the {@link ReferenceCatalog}, a kind's mnemonic from {@link Mnemonics}), the {@code links} panel (the document's
 * {@link LinkRef}s with their badges and resolved, pending or missing), the {@code provenance} panel (layout, fingerprint, source and
 * generation), field labels ({@code Semantics.humanize}), the panel and pivot limits from {@code drishti.panels} and {@code drishti.pivot},
 * and the pivot engine (bicompute's {@code PivotCore}) for a pivot panel's figures. What a host panel reads, the pipeline puts in
 * {@link BindContext#host()} as a {@link Host}.
 */
public final class DrishtiBinder {

    private DrishtiBinder() {
    }

    /**
     * What Drishti's own panels read from a binding: the document as stored with its provenance, the shape fingerprint, the links the
     * catalogue found in the document.
     */
    public record Host(EntityDocument doc, Fingerprint fingerprint, List<LinkRef> links) {
        public Host {
            links = links == null ? List.of() : List.copyOf(links);
        }
    }

    /** The binder with Drishti's hooks. */
    public static Binder binder(ElCompiler el, Formats formats, ReferenceCatalog catalog, BadgeRenderer badges, Mnemonics mnemonics,
            PanelLimits limits, PivotProperties pivot) {
        return new Binder(el, formats, hooks(catalog, badges, mnemonics, limits, pivot), PIVOTS);
    }

    /** Drishti's hooks: links, the two profile panels, labels, limits. */
    public static BindHooks hooks(ReferenceCatalog catalog, BadgeRenderer badges, Mnemonics mnemonics, PanelLimits limits, PivotProperties pivot) {
        PivotProperties pv = pivot == null ? PivotProperties.defaults() : pivot;
        return BindHooks.core()
                .withLinks(new LinkResolver() {
                    @Override
                    public Optional<String> kindOf(String id) {
                        return catalog == null ? Optional.empty() : catalog.kindOf(id);
                    }

                    @Override
                    public String codeFor(String kind) {
                        return mnemonics == null ? null : mnemonics.codeFor(kind);
                    }
                })
                .withPanel(PanelKind.LINKS, (p, c, b) -> links(c, b, badges))
                .withPanel(PanelKind.PROVENANCE, (p, c, b) -> provenance(c))
                .withLabels(com.ash.drishti.inference.Semantics::humanize)
                .withLimits(limits == null ? PanelLimits.defaults() : limits)
                .withPivot(new PivotLimits(pv.enabled(), pv.maxRecords(), "drishti.pivot.enabled"));
    }

    private static Host host(BindContext c) {
        return c.host() instanceof Host h ? h : new Host(null, null, List.of());
    }

    private static PanelData links(BindContext c, Binder b, BadgeRenderer badges) {
        List<PanelData.LinkItem> items = new ArrayList<>();
        for (LinkRef r : host(c).links()) {
            com.ash.rachana.bind.EntityRef target = Hosted.ref(r.target());
            Entity d = c.linked().get(target);
            String status = d != null ? "resolved" : c.pending().contains(target) ? "pending" : "missing";
            String badge = d == null || badges == null ? "" : badges.badge(r.target().kind(), DataNode.of(d.data()));
            items.add(new PanelData.LinkItem(r.label(), r.target().id(), b.linkView(r.target().kind(), r.target().id()), badge, status));
        }
        return new PanelData.Links(items);
    }

    private static PanelData provenance(BindContext c) {
        Host h = host(c);
        var pv = h.doc() == null ? null : h.doc().provenance();
        return new PanelData.Fields(List.of(
                Cell.of("Layout", c.layout().label()),
                Cell.of("Fingerprint", h.fingerprint() == null ? null : h.fingerprint().shortForm()),
                Cell.of("Source", pv == null ? null : pv.source() + ", gen " + pv.generation())));
    }

    /**
     * A panel as Drishti's API answers it: the error text of a panel that could not be bound carries Rachana's {@code RCH-} code, which
     * the edge answers as Drishti's {@code DRS-} code of the same number ({@code RCH-2102} as {@code DRS-2102}, SPLIT_PLAN 8.3e).
     */
    public static com.ash.rachana.view.ViewModel.PanelView edge(com.ash.rachana.view.ViewModel.PanelView v) {
        if (v == null || v.error() == null || !v.error().startsWith("RCH-")) {
            return v;
        }
        return new com.ash.rachana.view.ViewModel.PanelView(v.id(), v.kind(), v.title(), v.code(), v.key(), v.area(), v.inferred(),
                v.explanation(), v.data(), "DRS-" + v.error().substring(4), v.empty(), v.span(), v.height(), v.denied(), v.wire(), v.refresh(),
                v.form(), v.provenance(), v.compute(), v.actions());
    }

    /** A pivot panel's figures by the pivot engine (every cell, subtotal and total in memory), its row keys as the engine writes them. */
    public static final PivotEngine PIVOTS = new PivotEngine() {
        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Map<String, Object>> cells(Map<String, Object> view, List<Field> fields, List<Object[]> rows) {
            List<com.ash.drishti.kernel.api.pivot.PivotProvider.Field> fs = fields.stream()
                    .map(f -> new com.ash.drishti.kernel.api.pivot.PivotProvider.Field(f.name(), f.type(), f.label(), f.description())).toList();
            com.ash.drishti.bicompute.core.PivotCore core = new com.ash.drishti.bicompute.core.PivotCore(
                    List.of(new com.ash.drishti.bicompute.rows.RowsProvider("panel", "panel", fs, rows)),
                    com.ash.drishti.bicompute.cache.StateCache.none(),
                    new com.ash.drishti.bicompute.core.PivotCore.Settings(5_000_000, 5_000_000, Integer.MAX_VALUE, 1, 10, 1, 60, Long.MAX_VALUE));
            Map<String, Object> answer = core.query(com.ash.drishti.kernel.api.pivot.Viewer.of(""),
                    com.ash.drishti.kernel.api.pivot.PivotView.parse(view));
            return (Map<String, Map<String, Object>>) answer.get("cells");
        }

        @Override
        public String key(Map<String, String> coordinates) {
            return com.ash.drishti.kernel.api.pivot.CellKey.of(coordinates).canonical();
        }
    };
}
