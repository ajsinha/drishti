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
package com.ash.drishti.server.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.LiveCompiler;
import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.rachana.about.AboutProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ask's text about a kind: the kind's section of its pack's README (a pack has no guide of its own since i7k). */
class PackGuidesTest {

    @TempDir Path dir;

    private static final String ABOUT = """
            about: 1
            kinds:
              scenario-pv:
                title: Scenario PV
                about: "A trade's value in each scenario."
              risk-factor:
                title: Risk factor
                about: "A market input."
            """;

    private static final String README = """
            # The market-risk pack

            ## What it is

            VaR inputs.

            ## Data structures

            ### `scenario-pv`

            One row per trade, date and scenario.

            | Field | Type |
            |---|---|
            | `PV` | number |

            ### `pnl-vector`

            The vector.

            ## Data contracts

            | `SPV` | `scenario-pv` |
            """;

    private PackGuides guides(boolean withReadme) throws Exception {
        Path pack = Files.createDirectories(dir.resolve("market-risk"));
        Files.writeString(pack.resolve("pack.yaml"), "pack: market-risk\n");
        Path about = Files.createDirectories(pack.resolve("config")).resolve("about.yaml");
        Files.writeString(about, ABOUT);
        if (withReadme) {
            Files.writeString(pack.resolve("README.md"), README);
        }
        AboutProperties props = new AboutProperties(List.of(new AboutProperties.PackSource("market-risk", "Market risk", about.toString(), null,
                List.of("scenario-pv", "risk-factor"), List.of("market-risk"))), null, null);
        return new PackGuides(new AboutCatalog(props, LiveCompiler.current()), props);
    }

    @Test
    void theKindsSectionOfThePacksReadmeUpToTheNextKind() throws Exception {
        String s = guides(true).sectionFor("market-risk", "scenario-pv", "SPV").orElseThrow();
        assertThat(s).startsWith("### `scenario-pv`").contains("One row per trade, date and scenario.", "| `PV` | number |")
                .doesNotContain("pnl-vector").doesNotContain("Data contracts").doesNotContain("# The market-risk pack");
    }

    @Test
    void aKindWithoutItsOwnHeadingGetsTheWholeReadmeAndAPackWithoutOneNothing() throws Exception {
        assertThat(guides(true).sectionFor("market-risk", "risk-factor", "RFAC").orElseThrow()).startsWith("# The market-risk pack");
        assertThat(guides(false).sectionFor("market-risk", "scenario-pv", "SPV")).isEmpty();
        assertThat(guides(true).sectionFor("other-pack", "scenario-pv", "SPV")).isEmpty();
        assertThat(guides(true).sectionFor("market-risk", "no-such-kind", "X")).isEmpty();
    }

    @Test
    void theBacktickedHeadingWinsOverAnEarlierHeadingThatNamesTheKindAsAWord() {
        String md = "# The trade pack\n\nAll.\n\n## Data structures\n\n### `trade`\n\nOne trade.\n\n### `book`\n\nBooks.\n";
        assertThat(PackGuides.section(md, "trade", "TRD")).isEqualTo("### `trade`\n\nOne trade.\n\n");
    }
}
