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
package com.ash.drishti.server.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The proof of every pack other than trading (UI_BOARD.md 12.4, the P5 follow-up), over the packs' committed samples with security on: every
 * entity page converted onto the record chips, painted and bound as each of three people (full: every kind, raw; masked: every kind with
 * MTM, NOTIONAL, PRICE and GDP_PER_CAPITA masked; restricted: a few kinds per pack, so links to the others are refused) and compared with the
 * old page. Up to 3 records per page. The per-pack switch stays on sutras.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,core,counterparty-risk,market-data,market-risk,news-and-markets,world-economy",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=MTM,NOTIONAL,PRICE,GDP_PER_CAPITA", "drishti.ui.dir=../config/ui",
        "drishti.identity.database-url=jdbc:sqlite:target/ui-migrate-packs-${random.uuid}/identity.db",
        "drishti.data.dir=target/ui-migrate-packs-data/${random.uuid}",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.restricted.kinds[0]=book", "drishti.security.roles.restricted.kinds[1]=desk",
        "drishti.security.roles.restricted.kinds[2]=netting-set", "drishti.security.roles.restricted.kinds[3]=cva",
        "drishti.security.roles.restricted.kinds[4]=ir-curve", "drishti.security.roles.restricted.kinds[5]=bond",
        "drishti.security.roles.restricted.kinds[6]=var", "drishti.security.roles.restricted.kinds[7]=trade-valuation",
        "drishti.security.roles.restricted.kinds[8]=listed-company", "drishti.security.roles.restricted.kinds[9]=economy",
        "drishti.security.roles.restricted.kinds[10]=region-economy", "drishti.security.roles.restricted.raw=true"})
class UiMigrationPacksTest {

    static final List<String> PACKS = List.of("core", "counterparty-risk", "market-data", "market-risk", "news-and-markets", "world-economy");
    static final List<Principal> PEOPLE = List.of(new Principal("fay", List.of("full")), new Principal("max", List.of("masked")),
            new Principal("ria", List.of("restricted")));

    @Autowired BoardProof proof;

    @Test
    void everyPackIsProvenForThreePeople() {
        Map<String, JsonNode> results = new LinkedHashMap<>();
        for (String pack : PACKS) {
            ObjectNode r = proof.prove(pack, PEOPLE, 3, null, null);
            results.put(pack, r);
            System.out.println("P5 proof, " + pack + ": proven " + r.path("proven") + ", differs " + r.path("differs") + ", no sample "
                    + r.path("noSample") + ", comparisons " + r.path("comparisons"));
            for (JsonNode s : r.path("screens")) {
                if (!"proven".equals(s.path("result").asText())) {
                    System.out.println("  " + s);
                }
            }
        }
        results.forEach((pack, r) -> {
            assertThat(r.path("differs").asInt()).as(pack + ": " + r).isZero();
            assertThat(r.path("proven").asInt()).as(pack).isPositive();
        });
    }
}
