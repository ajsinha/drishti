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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The lenses a server offers (docs/architecture/LENSES.md): {@code drishti.lenses}, where the word {@value #DEFAULT_WORD} stands for every
 * lens of the modules that are on (a lens the registry declares, offered because its modules are on, with a lens file under
 * {@code config/lenses}, and not marked {@code offered-by-default: false} in that file). A lens named that cannot be offered never stops
 * the start: it is left out, and each one is a problem in words (why, and how to fix it) for the start-up log (ERROR) and Admin → Health.
 *
 * @param names    the lenses offered, in order, each once
 * @param problems one line per lens named that is not offered
 */
public record LensOffer(List<String> names, List<String> problems) {

    /** The value of {@code drishti.lenses} that means "the lenses of the modules that are on" (application.yaml's default). */
    public static final String DEFAULT_WORD = "default";

    /** What the server knows about lenses. */
    public interface Catalogue {
        /** Every lens the registry declares whose modules are on, in declaration order. */
        List<String> offered();

        /** Whether the registry declares the lens (modules on or off). */
        boolean declared(String name);

        /** Whether the lens has its file ({@code config/lenses/<module>/<lens>.yaml}). */
        boolean hasFile(String name);

        /** Whether its file says {@code offered-by-default: false} (a lens a distribution or a profile names, not the default). */
        boolean offDefault(String name);
    }

    /** The lenses {@code configured} ({@code drishti.lenses}) resolve to against {@code c}. */
    public static LensOffer of(List<String> configured, Catalogue c) {
        Set<String> out = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        for (String raw : configured) {
            String name = raw.strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (DEFAULT_WORD.equals(name)) {
                c.offered().stream().filter(c::hasFile).filter(n -> !c.offDefault(n)).forEach(out::add);
                continue;
            }
            Predicate<String> on = n -> c.offered().contains(n);
            if (on.test(name) || (!c.declared(name) && c.hasFile(name))) {
                out.add(name);                     // offered, or a site's own lens: its file arranges it
            } else if (c.declared(name)) {
                problems.add("lens '" + name + "' is not offered: the module it needs is off or not installed at this site. Fix: switch the module"
                        + " on (Admin → Modules, or drishti.modules.<id>.enabled=true), or take '" + name + "' out of drishti.lenses (DRISHTI_LENSES)");
            } else {
                problems.add("lens '" + name + "' is not offered: no module declares a lens of that name and there is no lens file"
                        + " config/lenses/<module>/" + name + ".yaml. Fix: correct the name in drishti.lenses (DRISHTI_LENSES), add the lens file,"
                        + " or leave drishti.lenses at 'default' (the lenses of the modules that are on)");
            }
        }
        return new LensOffer(List.copyOf(out), List.copyOf(problems));
    }
}
