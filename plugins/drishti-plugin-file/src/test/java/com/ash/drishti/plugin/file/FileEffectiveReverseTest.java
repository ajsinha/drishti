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
package com.ash.drishti.plugin.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DATA-08 (QA 2026-10-01): reverse lookups of a kind kept in {@code effective} mode (a line when an entity changes, as
 * banking-core keeps counterparties and credit limits) find the entities whose version on the date mentions the target,
 * as a read of each would show it, instead of none.
 */
class FileEffectiveReverseTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 28);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 29);
    private static final LocalDate D3 = LocalDate.of(2026, 9, 30);

    @TempDir
    Path root;

    private void write(LocalDate day, String kind, String... lines) throws Exception {
        Path f = root.resolve(day + "/" + kind + ".jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, List.of(lines), StandardCharsets.UTF_8);
    }

    private static String cpty(String id, String nettingSet) {
        String doc = "{\"id\":\"" + id + "\",\"name\":\"" + id + " plc\",\"nettingSets\":[\"" + nettingSet + "\"]}";
        return "{\"kind\":\"counterparty\",\"id\":\"" + id + "\",\"doc\":\"" + doc.replace("\"", "\\\"") + "\"}";
    }

    @Test
    void anEffectiveKindIsLookedUpInEachEntitysVersionOnTheDate() throws Exception {
        write(D1, "counterparty", cpty("CP-1", "NS-SUMMIT-NY"), cpty("CP-2", "NS-OTHER"), cpty("CP-3", "NS-SUMMIT-NY"));
        write(D3, "counterparty", cpty("CP-1", "NS-MOVED"), cpty("CP-2", "NS-SUMMIT-NY"));   // CP-1 moved away, CP-2 moved in
        write(D1, "credit-limit", "{\"id\":\"CL-9\",\"level\":2,\"counterparty\":\"CP-1\",\"nettingSet\":\"NS-SUMMIT-NY\",\"limit\":267000000}");
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toString(), "mode.counterparty", "effective", "mode.credit-limit", "effective",
                "rescan-seconds", "3600")));
        EntityRef ns = EntityRef.of("netting-set", "NS-SUMMIT-NY");

        assertThat(p.reverse(ns, "counterparty", AsOf.of(D2))).containsExactlyInAnyOrder(EntityRef.of("counterparty", "CP-1"),
                EntityRef.of("counterparty", "CP-3"));
        assertThat(p.reverse(ns, "counterparty", AsOf.of(D3))).containsExactlyInAnyOrder(EntityRef.of("counterparty", "CP-2"),
                EntityRef.of("counterparty", "CP-3"));                     // each entity's latest line on or before the date
        assertThat(p.reverse(ns, "counterparty", AsOf.of(LocalDate.of(2026, 9, 25)))).isEmpty();   // nothing known yet
        assertThat(p.reverse(ns, "credit-limit", AsOf.of(D3))).containsExactly(EntityRef.of("credit-limit", "CL-9"));
        assertThat(p.reverse(ns, null, AsOf.of(D3))).containsExactlyInAnyOrder(EntityRef.of("counterparty", "CP-2"),
                EntityRef.of("counterparty", "CP-3"), EntityRef.of("credit-limit", "CL-9"));
        for (EntityRef r : p.reverse(ns, "counterparty", AsOf.of(D3))) {          // what a read of each shows agrees
            assertThat(p.fetch(r, AsOf.of(D3)).orElseThrow().data().get("nettingSets").get(0).asText()).isEqualTo("NS-SUMMIT-NY");
        }
    }
}
