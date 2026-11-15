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
package com.ash.drishti.net.member;

import java.util.List;
import java.util.Optional;

/**
 * An agreed view (CLUSTER_I5_DESIGN.md section 2.2): every member of a partition sees the same members in the same order under the same
 * id. Deterministic rules that once waited for gossip to settle (the VaR shard ranking, the first-live owner) are re-run on a new view.
 *
 * @param id the view's id: rises with every view change in a partition (a merge starts from the highest of the merged views)
 * @param coordinator the id of the member that installed the view (the first member)
 * @param members the members, in the agreed order
 */
public record View(long id, String coordinator, List<NodeIdentity> members) {

    /** No view: the node has not joined. */
    public static final View NONE = new View(0, "", List.of());

    public View {
        coordinator = coordinator == null ? "" : coordinator;
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** The members' ids, in order. */
    public List<String> ids() {
        return members.stream().map(NodeIdentity::id).toList();
    }

    public boolean contains(String nodeId) {
        return members.stream().anyMatch(m -> m.id().equals(nodeId));
    }

    public Optional<NodeIdentity> member(String nodeId) {
        return members.stream().filter(m -> m.id().equals(nodeId)).findFirst();
    }

    public int size() {
        return members.size();
    }
}
