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

/**
 * The lenses a server offers (docs/architecture/LENSES.md): {@code drishti.lenses}, where the word {@value #DEFAULT_WORD} stands for every
 * lens of the modules that are on (a lens file {@code config/lenses/<module>/<lens>.yaml} whose module is on, offered by the registry when
 * it declares the lens, and not marked {@code offered-by-default: false} in that file). A lens named that cannot be offered never stops
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

        /** The lenses that have a lens file ({@code config/lenses/<module>/<lens>.yaml}), in name order. */
        List<String> files();

        /** Whether the lens has its file. */
        default boolean hasFile(String name) {
            return files().contains(name);
        }

        /** Whether the module of the lens file's folder ({@code config/lenses/<module>/}) is on; a file at the top of the folder: yes. */
        boolean fileModuleOn(String name);

        /** Whether its file says {@code offered-by-default: false} (a lens a distribution or a profile names, not the default). */
        boolean offDefault(String name);
    }

    /** Whether a lens can be offered: the registry offers it, or (declared nowhere) its file's module is on. */
    private static boolean available(String n, Catalogue c) {
        if (c.declared(n)) {
            return c.offered().contains(n);
        }
        return c.hasFile(n) && c.fileModuleOn(n);
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
                // every lens file of a module that is on (and offered, when the registry declares it), not marked off by default
                c.files().stream().filter(n -> c.fileModuleOn(n) && available(n, c) && !c.offDefault(n)).forEach(out::add);
                continue;
            }
            if (available(name, c)) {
                out.add(name);
            } else if (c.declared(name) || c.hasFile(name)) {
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
