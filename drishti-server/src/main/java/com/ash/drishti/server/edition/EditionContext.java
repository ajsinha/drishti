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
import java.util.Map;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Which edition a request is seen through (docs/architecture/EDITIONS.md): the instance's own ({@code drishti.edition}), or, on a full
 * instance, the <em>lens</em> the console names in the {@value #HEADER} header for a person who opened one ("Open Drishti Risk").
 * The lens is the person's (kept in their settings, so every node of a cluster sees it); it is accepted only when the site offers it
 * ({@code drishti.lenses}). A lens hides commands and BI items from that person's view; it never secures anything: data access stays
 * with roles, entitlements and masks.
 */
public final class EditionContext {

    /** The header the console sends for a person who opened a lens. */
    public static final String HEADER = "X-Drishti-Lens";

    /** No edition and no lens. */
    public static final EditionContext NONE = new EditionContext(Edition.NONE, Map.of());

    private final Edition instance;
    private final Map<String, Edition> lenses;

    public EditionContext(Edition instance, Map<String, Edition> lenses) {
        this.instance = instance;
        this.lenses = Map.copyOf(lenses);
    }

    /** The instance's own edition ({@link Edition#NONE} for the full Drishti). */
    public Edition instance() {
        return instance;
    }

    /** The lenses the site offers (none when the instance runs an edition). */
    public List<String> lensNames() {
        return lenses.keySet().stream().sorted().toList();
    }

    public Map<String, Edition> lenses() {
        return lenses;
    }

    /** The edition the current request is seen through: the instance's, else the request's lens, else none. */
    public Edition current() {
        if (instance.active() || lenses.isEmpty()) {
            return instance;
        }
        RequestAttributes a = RequestContextHolder.getRequestAttributes();
        if (a instanceof ServletRequestAttributes s) {
            String lens = s.getRequest().getHeader(HEADER);
            if (lens != null && !lens.isBlank()) {
                return lenses.getOrDefault(lens.strip(), Edition.NONE);
            }
        }
        return Edition.NONE;
    }
}
