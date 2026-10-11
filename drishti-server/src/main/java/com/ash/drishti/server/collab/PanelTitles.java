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
package com.ash.drishti.server.collab;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.control.ViewState;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The titles of a view's panels, for emails and bridge posts that name a panel: a person reads "Cashflows", not {@code cashflows}. The
 * titles come from the view as the reader would get it (their masks, their gate), cached for a few minutes per view; when the view
 * cannot be built the panel's id is the answer, so a message is never held back for want of a title.
 */
public final class PanelTitles {

    private static final Logger LOG = LoggerFactory.getLogger(PanelTitles.class);

    /** Titles of the panels of an entity's view for a reader: panel id to title. */
    @FunctionalInterface
    public interface Source {
        Map<String, String> titles(String kind, String entityId, Principal reader);

        /** The titles with the control values a sender had on screen (a panel's title may carry the filter: "Cashflows - counterparty X"). */
        default Map<String, String> titles(String kind, String entityId, Principal reader, ViewState state) {
            return titles(kind, entityId, reader);
        }
    }

    private final Source source;
    private final Entitlements entitlements;
    private final Cache<String, Map<String, String>> cache = Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(5)).build();

    public PanelTitles(Source source, Entitlements entitlements) {
        this.source = source;
        this.entitlements = entitlements;
    }

    /** The titles read from the view pipeline. */
    public static Source fromPipeline(ViewPipeline pipeline, Entitlements entitlements) {
        return new Source() {
            @Override
            public Map<String, String> titles(String kind, String entityId, Principal reader) {
                return titles(kind, entityId, reader, ViewState.NONE);
            }

            @Override
            public Map<String, String> titles(String kind, String entityId, Principal reader, ViewState state) {
                Map<String, String> out = new LinkedHashMap<>();
                ViewModel raw = state == null || state.isEmpty()
                        ? pipeline.view(EntityRef.of(kind, entityId), AsOf.LATEST, entitlements.redactor(reader), k -> entitlements.mayOpen(reader, k))
                        : pipeline.view(EntityRef.of(kind, entityId), AsOf.LATEST, entitlements.redactor(reader), k -> entitlements.mayOpen(reader, k),
                                state.controls());
                for (PanelView p : entitlements.restrict(reader, raw).panels()) {
                    out.put(p.id(), p.title());
                }
                return out;
            }
        };
    }

    /** The title of {@code panelId} in the entity's view for {@code reader}; the id itself when unknown; null for no panel. */
    public String title(String kind, String entityId, String panelId, Principal reader) {
        return title(kind, entityId, panelId, reader, ViewState.NONE);
    }

    /**
     * As above, for the view as the sender had it on screen: a panel whose title follows a control (the filtered title) is named as the
     * recipient will see it, not by its unfiltered title.
     */
    public String title(String kind, String entityId, String panelId, Principal reader, ViewState state) {
        if (panelId == null || panelId.isBlank()) {
            return null;
        }
        ViewState st = state == null ? ViewState.NONE : state;
        String key = kind + '\u0000' + entityId + '\u0000' + entitlements.masks(reader) + '\u0000' + st.text();
        Map<String, String> titles = cache.getIfPresent(key);
        if (titles == null) {
            try {
                titles = source.titles(kind, entityId, reader, st);
            } catch (RuntimeException e) {
                LOG.debug("no panel titles for {} {}: {}", kind, entityId, e.toString());
                titles = Map.of();
            }
            cache.put(key, titles);
        }
        String t = titles.get(panelId);
        return t == null || t.isBlank() ? panelId : t;
    }
}
