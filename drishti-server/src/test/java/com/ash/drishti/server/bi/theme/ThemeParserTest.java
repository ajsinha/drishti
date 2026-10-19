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
package com.ash.drishti.server.bi.theme;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.report.ReportParser.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Theme items (R6, 2.4.0): the shipped themes are clean, and a bad palette, colour, font or visual default comes back as DRS-9036 at its path. */
class ThemeParserTest {

    @Test
    void theShippedThemesAreClean() throws IOException {
        try (var files = Files.list(Path.of("../config/bi/themes"))) {
            List<Path> all = files.filter(f -> f.toString().endsWith(".theme.yaml")).toList();
            assertThat(all).isNotEmpty();
            for (Path f : all) {
                ThemeParser.Result r = ThemeParser.parse(Files.readString(f));
                assertThat(r.problems()).as(f.toString()).isEmpty();
                assertThat(r.theme().palette()).hasSizeBetween(3, 12);
                assertThat(Files.readString(f)).startsWith("# Project Drishti");
            }
        }
    }

    @Test
    void everyMistakeHasItsPath() {
        String yaml = """
                theme: mono
                palette: ["#000000", red]
                background: white
                font: { family: Comic Sans, size: 40 }
                visuals:
                  chart: { labels: maybe, valueAxis: { log: 3 } }
                  sparkles: { on: true }
                """;
        List<Problem> p = ThemeParser.parse(yaml).problems();
        assertThat(p.stream().filter(Problem::error).map(Problem::path).toList()).contains("theme", "palette", "background", "font.family", "font.size", "visuals.chart.valueAxis.log",
                "visuals.sparkles");
        assertThat(p).allMatch(x -> !x.error() || x.code().equals("DRS-9036"));
    }

    @Test
    void lowContrastTextIsAWarning() {
        String yaml = "theme: pale\npalette: [\"#111111\", \"#222222\", \"#333333\"]\nbackground: \"#ffffff\"\nforeground: \"#eeeeee\"\n";
        ThemeParser.Result r = ThemeParser.parse(yaml);
        assertThat(r.ok()).isTrue();
        assertThat(r.problems()).anyMatch(x -> x.code().equals("A11Y"));
    }
}
