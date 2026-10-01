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

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;

/** The packs this server runs with, for the API and the About page. */
public final class PackRegistry {

    private final List<Pack> packs;

    public PackRegistry(Environment env) {
        String loaded = env.getProperty("drishti.packs.loaded", "");
        List<String> names = Arrays.stream(loaded.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.packs = new PackLoader().load(PackLoader.dirs(env.getProperty("drishti.packs.dir", "./packs"),
                env.getProperty("drishti.packs.installed-dir", "./data/packs/installed")), names);
    }

    public List<Pack> packs() {
        return packs;
    }
}
