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
package com.ash.drishti.engine.bind;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.source.SourceFailures;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a {@code map} panel reads across a whole kind, before the panels are bound: which entities of its {@code link} kind
 * exist (so a country with no page is not clickable) and the {@code shade} field of every entity of its {@code shade} kind.
 * The entities are read once per kind and kept briefly ({@code drishti.panels.map-cache-seconds}), then the caller's masks
 * are applied to each: a masked field shades nothing and is never added up or ranked, and a kind the caller may not open is
 * never listed (the panel says so). Read within {@code drishti.panels.map-budget-millis}; a slower read leaves the map
 * unshaded, with the reason in its note.
 */
public final class MapSupport {

    private static final Pattern TOKEN = Pattern.compile("\\{(iso3|iso2|code)}");

    /**
     * What one map panel found.
     *
     * @param link the codes with an entity, the kind and why none, or null when the panel has no {@code link}
     * @param shade the figures, or null
     */
    public record Lookup(PanelData.GeoLink link, PanelData.GeoShade shade) {}

    private final SourceRouter router;
    private final ElCompiler el;
    private final Formats formats;
    private final PanelLimits limits;
    private final Cache<String, Map<EntityRef, EntityDocument>> documents;
    private final Cache<String, List<EntityRef>> listings;

    public MapSupport(SourceRouter router, ElCompiler el, Formats formats, PanelLimits limits) {
        this.router = router;
        this.el = el;
        this.formats = formats;
        this.limits = limits;
        Duration ttl = Duration.ofSeconds(limits.mapCacheSeconds());
        this.documents = Caffeine.newBuilder().maximumSize(8).expireAfterWrite(ttl).build();
        this.listings = Caffeine.newBuilder().maximumSize(32).expireAfterWrite(ttl).build();
    }

    /** What the map panels among {@code panels} read across kinds, by panel id. */
    public Map<String, Lookup> resolve(List<Panel> panels, java.util.function.Predicate<String> wanted, AsOf asOf,
            UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        Map<String, Lookup> out = new LinkedHashMap<>();
        for (Panel p : panels) {
            if (p.kind() == PanelKind.MAP && wanted.test(p.id())) {
                out.put(p.id(), new Lookup(link(p, asOf, mayOpen), shade(p, asOf, redact, mayOpen)));
            }
        }
        return out;
    }

    private PanelData.GeoLink link(Panel p, AsOf asOf, Predicate<String> mayOpen) {
        if (!(p.options().get("link") instanceof Map<?, ?> m)) {
            return null;
        }
        String kind = String.valueOf(m.get("kind"));
        String template = String.valueOf(m.get("id"));
        if (!mayOpen.test(kind)) {
            return new PanelData.GeoLink(kind, template, List.of(), "no access to " + kind);
        }
        Matcher token = TOKEN.matcher(template);
        if (!token.find()) {
            return new PanelData.GeoLink(kind, template, List.of(), "the id template names no {iso3}, {iso2} or {code}");
        }
        String group = token.group(1);
        String quoted = Pattern.quote(template.substring(0, token.start())) + "(" + (group.equals("iso3") ? "[A-Za-z]{3}" : group.equals("iso2") ? "[A-Za-z]{2}" : "[A-Za-z0-9-]{2,12}")
                + ")" + Pattern.quote(template.substring(token.end()));
        Pattern id = Pattern.compile("^" + quoted + "$");
        try {
            Set<String> codes = new LinkedHashSet<>();
            for (EntityRef r : listing(kind, asOf)) {
                Matcher m2 = id.matcher(r.id());
                if (m2.matches()) {
                    codes.add(m2.group(1).toUpperCase(java.util.Locale.ROOT));
                }
            }
            return new PanelData.GeoLink(kind, template, List.copyOf(codes), codes.isEmpty() ? "no " + kind + " entity matches " + template : null);
        } catch (RuntimeException e) {
            return new PanelData.GeoLink(kind, template, List.of(), "the " + kind + " entities could not be listed");
        }
    }

    private List<EntityRef> listing(String kind, AsOf asOf) {
        return listings.get(kind + "|" + asOf.businessDate(), k -> {
            Duration budget = Duration.ofMillis(limits.mapBudgetMillis());
            List<EntityHit> hits = router.list(kind, "", limits.mapEntities(), budget, asOf, new SourceFailures()).hits();
            List<EntityRef> refs = new ArrayList<>();
            hits.forEach(h -> refs.add(h.ref()));
            return refs;
        });
    }

    private PanelData.GeoShade shade(Panel p, AsOf asOf, UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        if (!(p.options().get("shade") instanceof Map<?, ?> m)) {
            return null;
        }
        String kind = String.valueOf(m.get("kind"));
        String field = String.valueOf(m.get("field"));
        String by = m.get("by") == null ? "$.iso3" : String.valueOf(m.get("by"));
        String fmt = m.get("fmt") == null ? p.option("fmt").orElse(null) : String.valueOf(m.get("fmt"));
        String label = m.get("label") == null ? kind + " " + field.replaceFirst("^\\$\\.", "") : String.valueOf(m.get("label"));
        String unit = p.option("unit").orElse(null);
        if (!mayOpen.test(kind)) {
            return new PanelData.GeoShade(label, unit, List.of(), "no access to " + kind);
        }
        Map<EntityRef, EntityDocument> docs;
        try {
            docs = documents(kind, asOf);
        } catch (RuntimeException e) {
            return new PanelData.GeoShade(label, unit, List.of(), "the " + kind + " entities could not be read in time");
        }
        List<PanelData.ShadeItem> items = new ArrayList<>();
        boolean masked = false;
        var fieldExpr = el.compile(field);
        var byExpr = el.compile(by);
        for (EntityDocument d : docs.values()) {
            EvalContext ctx = EvalContext.of(redact.apply(d.data()), formats);
            try {
                Object code = byExpr.eval(ctx);
                Object v = fieldExpr.eval(ctx);
                if (Values.masked(v)) {
                    masked = true;
                    continue;
                }
                double x = Values.number(v);
                if (code != null && !Values.masked(code) && !Double.isNaN(x)) {
                    items.add(new PanelData.ShadeItem(Values.text(code), x, formats.format(fmt, Values.normalise(x))));
                }
            } catch (RuntimeException e) {
                // a document that lacks the field has no figure: the country stays unshaded
            }
        }
        if (masked) {
            return new PanelData.GeoShade(label, unit, List.of(), "the field is masked for your role");
        }
        return new PanelData.GeoShade(label, unit, items, items.isEmpty() ? "no " + kind + " entity has " + field : null);
    }

    private Map<EntityRef, EntityDocument> documents(String kind, AsOf asOf) {
        return documents.get(kind + "|" + asOf.businessDate(), k -> router.fetchAll(listing(kind, asOf), Duration.ofMillis(limits.mapBudgetMillis()), asOf));
    }
}
