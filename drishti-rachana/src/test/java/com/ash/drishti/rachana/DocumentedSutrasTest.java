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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every complete Sutra shown in the documentation and tutorials parses and compiles, so readers can paste it. */
class DocumentedSutrasTest {

    /** A complete Sutra in a document: a {@code ```yaml} block whose first line (after comments) is {@code rachana: 1}. */
    static final Pattern BLOCK = Pattern.compile("(?ms)^```yaml\\s*$\\n((?:#[^\\n]*\\n|\\s*\\n)*rachana:.*?)^```\\s*$");

    @Test
    void sutraExamplesInDocsAndTutorialsAreValid() throws Exception {
        List<Path> files = new ArrayList<>();
        for (String dir : List.of("../docs", "../console/web/guides", "../packs")) {
            try (Stream<Path> s = Files.walk(Path.of(dir))) {
                s.filter(p -> p.toString().endsWith(".md") && !p.toString().contains("/sutras/"))
                        .forEach(files::add);
            }
        }
        SutraParser parser = new SutraParser();
        SutraExpressions check = new SutraExpressions(new ElCompiler());
        int checked = 0;
        for (Path f : files) {
            Matcher m = BLOCK.matcher(Files.readString(f));
            while (m.find()) {
                String yaml = m.group(1);
                if (yaml.contains("...") || yaml.contains("…")) {
                    continue;   // an abridged illustration, not a Sutra to paste
                }
                Sutra s = parser.parse(yaml, f.getFileName().toString().replace(".md", ".sutra.yaml"), "docs");
                assertThat(check.check(s)).as(f + " " + s.id()).isEmpty();
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(2);
    }
}
