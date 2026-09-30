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
package com.ash.drishti.rachana.parse;

import com.ash.drishti.rachana.SutraException;
import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.model.SourceLocation;
import com.ash.drishti.rachana.model.Sutra;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.util.List;

/** Parses and validates a Sutra: a Markdown Sutra ({@link SutraMarkdown}) or plain YAML. Stateless and thread-safe. */
public final class SutraParser {

    private final PositionalYamlReader reader = new PositionalYamlReader();

    /**
     * @param yaml the file content: Markdown with one {@code ```sutra} block, or plain YAML
     * @param file the file name used in problem locations
     * @param domain the default domain (the parent folder name)
     * @throws SutraException listing every problem found
     */
    public Sutra parse(String yaml, String file, String domain) {
        if (SutraMarkdown.isFile(file) || SutraMarkdown.isMarkdown(yaml)) {
            String inner = SutraMarkdown.yaml(yaml);
            if (inner == null) {
                throw new SutraException(List.of(new SutraProblem("DRS-2004",
                        "a Markdown Sutra needs exactly one closed ```sutra block", new SourceLocation(file, 1, 1))));
            }
            yaml = inner;
        }
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
