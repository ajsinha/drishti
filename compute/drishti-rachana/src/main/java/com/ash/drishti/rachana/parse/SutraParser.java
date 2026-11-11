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
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates a Sutra: one YAML document ({@code <name>.v<N>.sutra.yaml}) that starts with {@code rachana: 1}.
 * Stateless and thread-safe.
 */
public final class SutraParser {

    /** The Rachana language version this parser reads; every Sutra declares it ({@code rachana: 1}). */
    public static final int LANGUAGE = 1;

    /** The file name used for text that comes from Studio rather than from a file. */
    public static final String STUDIO = "studio.sutra.yaml";

    /**
     * YAML a Sutra must not depend on: a key written twice in one mapping, a second document, a tag other than the core
     * ones (see {@link PositionalYamlReader}).
     */
    public static final String AMBIGUOUS_YAML = "DRS-2033";

    private final PositionalYamlReader reader = new PositionalYamlReader();

    /**
     * @param yaml the file content: the Sutra YAML
     * @param file the file name used in problem locations
     * @param domain the default domain (the parent folder name)
     * @throws SutraException listing every problem found
     */
    public Sutra parse(String yaml, String file, String domain) {
        PNode root;
        List<PositionalYamlReader.Issue> issues = new ArrayList<>();
        try {
            root = reader.read(yaml, issues);
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
        if (!issues.isEmpty() || !b.problems().isEmpty()) {
            List<SutraProblem> all = new ArrayList<>();
            issues.forEach(i -> all.add(new SutraProblem(AMBIGUOUS_YAML, i.message(), new SourceLocation(file, i.line(), i.column()))));
            all.addAll(b.problems());
            throw new SutraException(all);
        }
        return s;
    }
}
