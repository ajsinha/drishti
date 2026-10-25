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
package com.ash.drishti.api.compute;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Who a computed answer is for (REACTIVE_CONNECTORS.md section 9): the user, their roles and their <em>view signature</em>, a hash of
 * everything that decides what they may see (the kinds their roles open, field masks, row entitlements). Two viewers with the same
 * signature may share a cached answer; two with different signatures never do. Never a credential.
 *
 * <p>The server sets the viewer of the request being served ({@link #call}), so a connector reached through the normal routing (a view of
 * a computed kind, a panel's source) knows whom it computes for. Virtual threads a read starts inherit it. A read with no viewer is a
 * read by the server itself and is refused by computed kinds unless the connector says otherwise.
 *
 * @param user the user id
 * @param roles the user's roles
 * @param viewSignature the hash of what the user may see
 */
public record Viewer(String user, List<String> roles, String viewSignature) {

    private static final InheritableThreadLocal<Viewer> CURRENT = new InheritableThreadLocal<>();

    public Viewer {
        Objects.requireNonNull(user, "user");
        roles = roles == null ? List.of() : List.copyOf(roles);
        viewSignature = viewSignature == null ? "" : viewSignature;
    }

    /** The viewer the current read is for, if the server set one. */
    public static Optional<Viewer> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Runs {@code body} on behalf of {@code viewer} (null: none), restoring the previous viewer afterwards. */
    public static <T> T call(Viewer viewer, Callable<T> body) throws Exception {
        Viewer before = CURRENT.get();
        set(viewer);
        try {
            return body.call();
        } finally {
            set(before);
        }
    }

    /** Sets the viewer of this thread (null removes it); for request filters that cannot wrap the request in {@link #call}. */
    public static void set(Viewer viewer) {
        if (viewer == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(viewer);
        }
    }
}
