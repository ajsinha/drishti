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
package com.ash.drishti.server.modules;

/**
 * An old API prefix still answered for one release (docs/architecture/DOMAIN_MODULES.md, the move): a request under {@code from} is
 * served as the same request under {@code to}, method, body and query unchanged, and answered with a {@code Deprecation} header and a
 * {@code Link} to the new path. A module contributes its aliases as beans; the core's {@link ApiAliasFilter} applies them before every
 * other filter, so the token's scopes, the engine gate and the controllers see only the new path.
 *
 * @param from the old prefix ({@code /api/v1/var})
 * @param to   the new prefix ({@code /api/v1/risk/var})
 */
public record ApiAlias(String from, String to) {

    public ApiAlias {
        if (from == null || !from.startsWith("/") || to == null || !to.startsWith("/")) {
            throw new IllegalArgumentException("an API alias maps one path prefix to another: " + from + " -> " + to);
        }
    }

    /** The path under the new prefix, or null when the path is not under the old one (a prefix at a segment boundary). */
    public String rewrite(String path) {
        if (path.equals(from) || path.startsWith(from + "/") || path.startsWith(from + ".")) {
            return to + path.substring(from.length());
        }
        return null;
    }
}
