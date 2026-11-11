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
package com.ash.drishti.server.api;

import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.SutraRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The problems of the Sutra files that live inside a pack's folder, one line each ({@code file:line:column DRS-code message}, the file
 * relative to the pack's folder). A pack whose Sutra does not parse is still switched on; this is how Admin → Packs shows it.
 */
final class PackSutraProblems {

    private PackSutraProblems() {
    }

    static List<String> of(SutraRegistry sutras, Path packDir) {
        Path dir = packDir.toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<SutraProblem>> e : sutras.problems().entrySet()) {
            Path file = Path.of(e.getKey()).toAbsolutePath().normalize();
            if (!file.startsWith(dir)) {
                continue;
            }
            String rel = dir.relativize(file).toString().replace('\\', '/');
            for (SutraProblem p : e.getValue()) {
                out.add(rel + ":" + p.location().line() + ":" + p.location().column() + " " + p.code() + " " + sutras.relative(p.message()));
            }
        }
        return out;
    }
}
