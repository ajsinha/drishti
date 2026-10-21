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

import com.ash.drishti.server.security.Principal;

/**
 * The powers of the UI Board (UI_BOARD.md section 7.1), mapped onto the existing roles (decision 14.1 item 4): viewing is every signed-in
 * role; saving boards is a role with {@code layout}; authoring chips is a Sutra author; reviewing chips is a Sutra approver; certifying
 * chips is a {@code bi-steward}; an administrator holds all. Workspace boards follow the BI workspace membership.
 */
public interface UiAccess {

    boolean mayView(Principal p);

    boolean maySave(Principal p);

    boolean mayAuthor(Principal p);

    /**
     * {@code ui.board.render-dynamic} (UI_BOARD.md 7.1): render an unsaved board document. A role that may save boards (analyst and above); never an
     * embed token in 2.7.0 (14.1 item 8), which renders saved boards only.
     */
    default boolean mayRenderDynamic(Principal p) {
        return p != null && p.embedApp() == null && maySave(p);
    }

    boolean mayReview(Principal p);

    boolean mayCertify(Principal p);

    boolean isAdmin(Principal p);

    /** The caller's role in a BI workspace ({@code viewer}, {@code contributor}, {@code member}, {@code admin}), or null when none. */
    String workspaceRole(String workspace, Principal p);

    /** Records an audit row (who, what, which item, detail); never values. */
    default void audit(Principal p, String action, String subject, String detail) {}

    /** The rank of a workspace role, -1 for none. */
    static int rank(String role) {
        return role == null ? -1 : switch (role) {
            case "viewer" -> 0;
            case "contributor" -> 1;
            case "member" -> 2;
            case "admin" -> 3;
            default -> -1;
        };
    }
}
