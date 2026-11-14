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
package com.ash.drishti.server.edition;

import com.ash.drishti.packs.edition.Edition;
import java.util.List;

/**
 * What a request is seen through (docs/guides/ADMIN_GUIDE.md): the distribution's filter ({@link Edition#NONE} for Drishti All), the same
 * for every request. The lenses the site offers ({@code drishti.lenses}) are workspaces: the console arranges Home and the menus for the
 * person's lens, and a lens never hides anything, so the {@value #HEADER} header the console sends changes nothing here. Data access stays
 * with roles, entitlements and masks.
 */
public final class EditionContext {

    /** The header the console sends for a person working in a lens (read by nothing on the server: a lens only arranges). */
    public static final String HEADER = "X-Drishti-Lens";

    /** No filter and no lens. */
    public static final EditionContext NONE = new EditionContext(Edition.NONE, List.of());

    private final Edition instance;
    private final List<String> lenses;

    public EditionContext(Edition instance, List<String> lenses) {
        this.instance = instance;
        this.lenses = List.copyOf(lenses);
    }

    /** The distribution's filter ({@link Edition#NONE} for Drishti All). */
    public Edition instance() {
        return instance;
    }

    /** The lenses the site offers, sorted. */
    public List<String> lensNames() {
        return lenses.stream().sorted().toList();
    }

    /** The filter the current request is seen through: the distribution's, whatever lens the person works in. */
    public Edition current() {
        return instance;
    }
}
