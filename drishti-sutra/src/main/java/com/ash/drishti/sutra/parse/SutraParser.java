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
package com.ash.drishti.sutra.parse;

import com.ash.drishti.sutra.SutraException;
import com.ash.drishti.sutra.SutraProblem;
import com.ash.drishti.sutra.model.SourceLocation;
import com.ash.drishti.sutra.model.Sutra;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.util.List;

/** Parses and validates Sutra YAML. Stateless and thread-safe. */
public final class SutraParser {

    private final PositionalYamlReader reader = new PositionalYamlReader();

    /**
     * @param yaml the file content
     * @param file the file name used in problem locations
     * @param domain the default domain (the parent folder name)
     * @throws SutraException listing every problem found
     */
    public Sutra parse(String yaml, String file, String domain) {
        PNode root;
        try {
            root = reader.read(yaml);
        } catch (JsonProcessingException e) {
            var l = e.getLocation();
            throw new SutraException(List.of(new SutraProblem("DRS-2001", "YAML syntax: " + e.getOriginalMessage(),
                    new SourceLocation(file, l == null ? 0 : l.getLineNr(), l == null ? 0 : l.getColumnNr()))));
        } catch (IOException e) {
            throw new SutraException(List.of(new SutraProblem("DRS-2001", "cannot read: " + e.getMessage(),
                    new SourceLocation(file, 0, 0))));
        }
        SutraBuilder b = new SutraBuilder(file);
        Sutra s = b.build(root, domain);
        if (!b.problems().isEmpty()) {
            throw new SutraException(b.problems());
        }
        return s;
    }
}
