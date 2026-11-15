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
package com.ash.drishti.net.agreement;

import java.util.List;

/**
 * What Admin → Cluster → Agreement and Health show of the agreement (CLUSTER_I5_DESIGN.md sections 2.3 and 11.1).
 *
 * @param mode the mode in force
 * @param decides true when a change can be decided now (a majority of voters, or the referee, is reachable)
 * @param leader the node that leads (ratis), the referee's name (database), or empty
 * @param term the leader's term (ratis), else 0
 * @param voters the voters, in the configured order (empty for local and database)
 * @param reachable the voters this node reaches now
 * @param why why this mode was chosen, or why it cannot decide, in words
 */
public record AgreementHealth(Agreement.Mode mode, boolean decides, String leader, long term, List<String> voters, List<String> reachable,
        String why) {

    public AgreementHealth {
        leader = leader == null ? "" : leader;
        voters = voters == null ? List.of() : List.copyOf(voters);
        reachable = reachable == null ? List.of() : List.copyOf(reachable);
        why = why == null ? "" : why;
    }

    /** The refusal text of a change that needs agreement now: {@code the cluster cannot decide right now (2 of 5 voters reachable)}. */
    public String refusal() {
        String count = voters.isEmpty() ? "" : " (" + reachable.size() + " of " + voters.size() + " voters reachable)";
        return "the cluster cannot decide right now" + count + (why.isEmpty() ? "" : ": " + why);
    }
}
