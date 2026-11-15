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
package com.ash.drishti.packs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live companions (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 2.2): any data connector may have one, a connector on a messaging
 * plugin that says {@code companion-of: <data connector>}. A companion serves its data connector's kinds (or the subset its template
 * names) and no others; a companion whose data connector is not in force is not used (a source module switched off takes its live rows
 * with it). A live connector with no {@code companion-of:} feeds a kind that has no lake. Pure: the connectors come in, the answers go
 * out. The Python twin is {@code drishti-console/core/resolution.py} ({@code Connectors.companion_problems}, {@code Bindings.live}); both
 * are held to the shared fixture {@code src/test/resources/connector-layers/cases.yaml} ({@code companions:}).
 */
public final class Companions {

    /** The code of every companion problem. */
    public static final String CODE = "DRS-1061";
    /** The plugins a companion may use: the transports (decision Q9: no others in i5). */
    public static final Set<String> TRANSPORTS = Set.of("kafka", "activemq", "rabbitmq");

    /**
     * One connector as the companion rules see it.
     *
     * @param name        its name
     * @param plugin      its plugin
     * @param companionOf the data connector it accompanies, empty for none
     * @param kinds       the kinds it names (a template's {@code kinds:}); a companion that names none serves its data connector's
     */
    public record Connector(String name, String plugin, String companionOf, List<String> kinds) {

        public Connector {
            companionOf = companionOf == null ? "" : companionOf.strip();
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
        }

        public boolean companion() {
            return !companionOf.isEmpty();
        }
    }

    private Companions() {
    }

    /** A definition as the companion rules see it, with the kinds its template names. */
    public static Connector of(ConnectorFiles.Definition d, List<String> templateKinds) {
        return new Connector(d.name(), d.plugin(), d.companionOf(), templateKinds);
    }

    /**
     * What is wrong with each companion, by its name; a companion listed here is not used. The data connector is not in force (its file
     * gone, its module off), is itself a companion, or the companion is not on a messaging plugin, or names a kind its data connector does
     * not serve.
     *
     * @param connectors every connector in force, by name
     */
    public static Map<String, String> problems(Map<String, Connector> connectors) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Connector c : connectors.values()) {
            if (!c.companion()) {
                continue;
            }
            Connector data = connectors.get(c.companionOf());
            String why = null;
            if (data == null) {
                why = "is the live companion of " + c.companionOf() + ", which is not here (its file removed or its module off): it is not used";
            } else if (data.companion()) {
                why = "is the live companion of " + c.companionOf() + ", which is itself a live companion: name the data connector";
            } else if (!TRANSPORTS.contains(c.plugin())) {
                why = "is a live companion on plugin " + c.plugin() + ", which pushes nothing: use " + String.join(", ", new java.util.TreeSet<>(TRANSPORTS));
            } else if (!data.kinds().isEmpty() && !data.kinds().containsAll(c.kinds())) {
                List<String> extra = c.kinds().stream().filter(k -> !data.kinds().contains(k)).toList();
                why = "names " + String.join(", ", extra) + ", which its data connector " + data.name() + " does not serve (" + String.join(", ", data.kinds())
                        + "): a companion changes only its data connector's rows";
            }
            if (why != null) {
                out.put(c.name(), "connector " + c.name() + " " + why + " (" + CODE + ")");
            }
        }
        return out;
    }

    /** The kinds a companion serves: those it names, else its data connector's; none when it is not used. */
    public static List<String> kinds(Connector c, Map<String, Connector> connectors) {
        return kinds(c, connectors, problems(connectors));
    }

    private static List<String> kinds(Connector c, Map<String, Connector> connectors, Map<String, String> bad) {
        if (!c.companion()) {
            return c.kinds();
        }
        if (bad.containsKey(c.name()) || !connectors.containsKey(c.companionOf())) {
            return List.of();
        }
        return c.kinds().isEmpty() ? connectors.get(c.companionOf()).kinds() : c.kinds();
    }

    /**
     * The live connectors of {@code kind} when the site's binding names none (KERNEL_AND_MODULES.md section 18.6, Live): the companions of
     * the connectors it is read from that serve the kind, in read order; then the live connectors with no data connector that name the kind
     * (a kind with no lake). A companion with a problem is never one of them.
     *
     * @param read       the kind's read connectors, in read order
     * @param connectors every connector in force, by name
     */
    public static List<String> live(String kind, List<String> read, Map<String, Connector> connectors) {
        Map<String, String> bad = problems(connectors);
        List<String> out = new ArrayList<>();
        for (String r : read) {
            for (Connector c : connectors.values()) {
                if (c.companionOf().equals(r) && kinds(c, connectors, bad).contains(kind) && !out.contains(c.name())) {
                    out.add(c.name());
                }
            }
        }
        for (Connector c : connectors.values()) {
            if (!c.companion() && TRANSPORTS.contains(c.plugin()) && c.kinds().contains(kind) && !read.contains(c.name()) && !out.contains(c.name())) {
                out.add(c.name());
            }
        }
        return out;
    }
}
