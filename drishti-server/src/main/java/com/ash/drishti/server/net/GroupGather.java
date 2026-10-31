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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.derived.GroupShares;
import com.ash.drishti.server.security.Principal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Grouped pages across the cluster (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 8): a page of a derived kind (a counterparty's trades, a book's P&amp;L) is
 * built from the members of every node that holds the kind it groups, with the scatter-gather of a search. The nodes asked are the
 * search's ({@link SearchScatter#targets}: those the caller's roles reach, never a replica of a node already asked); each answers its
 * share of the group from its own data, masked for the caller as it masks ({@code GET /api/v1/net/group/{kind}/{id}}); the shares are
 * merged with this node's own ({@link GroupShares}: a member two nodes hold counts once) and the page is masked again here by the view.
 * A node that holds the kind but failed, was late or is down is named in the page ({@code partial: true}, {@code missing}); with nothing
 * to gather (the cluster off, a forwarded request, no other node holding the kind) the page is this node's, unchanged.
 */
public final class GroupGather {

    private final SourceRouter router;
    private final NetRouting routing;
    private final SearchScatter scatter;
    private final JsonCodec codec;

    public GroupGather(SourceRouter router, NetRouting routing, SearchScatter scatter, JsonCodec codec) {
        this.router = router;
        this.routing = routing;
        this.scatter = scatter;
        this.codec = codec;
    }

    /** The page gathered across the nodes, or empty when there is nothing to gather (then {@code local} stands). */
    public Optional<EntityDocument> gather(EntityRef ref, AsOf asOf, EntityDocument local) {
        if (!routing.forwarding() || NetCaller.fromPeer()) {
            return Optional.empty();
        }
        Principal p = NetCaller.current().map(NetCaller.Caller::principal).orElse(null);
        SourceRouter.Derived derived = p == null ? null : router.derivedOf(ref.kind()).orElse(null);
        if (derived == null) {
            return Optional.empty();
        }
        String from = derived.plugin().groupedFrom(ref.kind()).orElseThrow();
        List<String> peers = scatter.targets(from, p);
        List<String> down = routing.downHolders(from);
        if (peers.isEmpty() && down.isEmpty()) {
            return Optional.empty();
        }
        String self = routing.net().nodeId();
        List<GroupShares.Share> shares = new ArrayList<>();
        List<String> answered = new ArrayList<>();
        derived.plugin().share(ref, asOf).flatMap(doc -> derived.plugin().readShare(ref.kind(), self, doc)).ifPresent(shares::add);
        answered.add(self);
        List<String> missing = new ArrayList<>();
        String path = "/api/v1/net/group/" + URLEncoder.encode(ref.kind(), StandardCharsets.UTF_8) + "/~?id=" + URLEncoder.encode(ref.id(), StandardCharsets.UTF_8);
        for (SearchScatter.Share s : peers.isEmpty() ? List.<SearchScatter.Share>of() : scatter.ask(peers, path, asOf, p, scatter.budget(), "group", from)) {
            if (s.answer() == null) {
                missing.add(s.nodeId() + ": " + s.failure());
                continue;
            }
            answered.add(s.nodeId());
            com.fasterxml.jackson.databind.JsonNode share = s.answer().path("share");
            if (share.isObject()) {
                derived.plugin().readShare(ref.kind(), s.nodeId(), codec.read(share.toString())).ifPresent(shares::add);
            }
        }
        down.forEach(d -> missing.add(d + ": not asked: it cannot be reached now"));
        NetCaller.served().ifPresent(sv -> sv.gathered(answered, missing.stream().map(m -> m.substring(0, m.indexOf(':'))).toList()));
        if (shares.isEmpty()) {
            return Optional.empty();                      // no member anywhere: not found, as on one node (or the holders' failure)
        }
        DataNode page = derived.plugin().gather(ref, shares).orElseThrow();
        if (!missing.isEmpty()) {
            Map<String, Object> marked = new LinkedHashMap<>(((DataNode.Obj) page).fields());
            marked.put("partial", true);
            marked.put("missing", missing.stream().map(m -> "node " + m).toList());
            page = DataNode.of(marked);
        }
        Provenance prov = local != null ? local.provenance()
                : new Provenance(derived.source(), 0, Instant.now(), false, asOf == null ? null : asOf.businessDate());
        return Optional.of(new EntityDocument(ref, page, prov));
    }
}
