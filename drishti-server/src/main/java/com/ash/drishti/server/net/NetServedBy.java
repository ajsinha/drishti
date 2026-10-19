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
package com.ash.drishti.server.net;

import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.List;

/**
 * The node that answered a view, for administrators only (DRISHTI_NET.md section 13): a line <em>Node</em> in the view's "How this view was
 * built" panel naming this node and how the view was served: built here from local data, built here from a document a peer read
 * (data from ...), forwarded here by another node (forwarded from ...), or a grouped page gathered from several nodes (scatter-gather from
 * ..., and the nodes missing). Everyone else's view is unchanged, so a user cannot tell which server answered; with Drishti*net off nothing
 * is added.
 */
public final class NetServedBy {

    /** The label of the line in the provenance panel. */
    public static final String LABEL = "Node";

    private final NetService net;
    private final Entitlements entitlements;

    public NetServedBy(NetService net, Entitlements entitlements) {
        this.net = net;
        this.entitlements = entitlements;
    }

    /** The line for the current request: this node and how it served it, or null when nothing is to be said (the cluster off). */
    public String line() {
        if (!net.running()) {
            return null;
        }
        String self = net.nodeId();
        NetCaller.Caller c = NetCaller.current().orElse(null);
        if (c != null && c.forwarded()) {
            return self + " · forwarded from " + c.fromPeer();
        }
        NetCaller.Served s = c == null ? null : c.served();
        if (s != null && !s.gatheredFrom().isEmpty()) {
            List<String> missing = s.missing();
            return self + " · scatter-gather from " + String.join(", ", s.gatheredFrom()) + (missing.isEmpty() ? "" : " (missing " + String.join(", ", missing) + ")");
        }
        if (s != null && !s.dataFrom().isEmpty()) {
            return self + " · built here, data from " + String.join(", ", s.dataFrom());
        }
        return self + " · local";
    }

    /** The line for an administrator ({@link #line}), or null for anyone else and with the cluster off. */
    public String lineFor(Principal p) {
        return p == null || !entitlements.isAdmin(p) ? null : line();
    }

    /** The view with the line added to its provenance panels, for an administrator; the view unchanged for anyone else. */
    public ViewModel annotate(Principal p, ViewModel v) {
        return annotate(v, lineFor(p));
    }

    /** The view with {@code line} added to its provenance panels (unchanged when {@code line} is null). */
    public static ViewModel annotate(ViewModel v, String line) {
        if (line == null) {
            return v;
        }
        List<ViewModel.PanelView> panels = v.panels().stream().map(pv -> annotate(pv, line)).toList();
        return new ViewModel(v.ref(), v.mnemonic(), v.title(), v.strip(), panels, v.keys(), v.provenance(), v.timings(), v.controls(), v.selects());
    }

    /** A live frame with {@code line} added to the provenance panels it carries whole (unchanged when {@code line} is null). */
    public static com.ash.drishti.engine.live.Frame annotate(com.ash.drishti.engine.live.Frame f, String line) {
        if (line == null || f.patches().stream().noneMatch(x -> x.panel() != null && "provenance".equals(x.panel().kind()))) {
            return f;
        }
        List<com.ash.drishti.engine.live.Patch> patches = f.patches().stream().map(x -> x.panel() == null ? x
                : new com.ash.drishti.engine.live.Patch(x.op(), x.index(), x.cell(), annotate(x.panel(), line), x.provenance(), x.at())).toList();
        return new com.ash.drishti.engine.live.Frame(f.seq(), f.generation(), patches, f.latencyMs(), f.p99Ms());
    }

    /** One panel with the line added when it is a provenance panel. */
    static ViewModel.PanelView annotate(ViewModel.PanelView pv, String line) {
        if (!"provenance".equals(pv.kind()) || !(pv.data() instanceof PanelData.Fields f)) {
            return pv;
        }
        List<ViewModel.Cell> cells = new ArrayList<>(f.fields());
        cells.add(ViewModel.Cell.of(LABEL, line));
        return new ViewModel.PanelView(pv.id(), pv.kind(), pv.title(), pv.code(), pv.key(), pv.area(), pv.inferred(), pv.explanation(),
                new PanelData.Fields(cells), pv.error(), pv.empty(), pv.span(), pv.height(), pv.denied());
    }
}
