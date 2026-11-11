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
package com.ash.drishti.rachana.design.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.parse.BlockUses;
import com.ash.drishti.rachana.parse.SutraBlocks;
import com.ash.drishti.rachana.parse.SutraParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The designer on a Sutra that uses shared blocks: the uses are listed with the panels they bring (what the canvas groups), a written
 * panel is edited, added, moved and removed at its own place (a use is one written item standing for several panels), a block's panel
 * is not edited in this Sutra, and a use's parameters are set or the use removed.
 */
class BlockOpsTest {

    @TempDir Path dir;

    static final String LEGS = """
            rachana: 1
            block: legs
            version: 1
            description: The legs side by side.
            params: { rate-fmt: price4 }
            panels:
              - { id: legs, kind: kv, title: Legs, columns: [ { label: Rate, bind: $.RATE, fmt: "%{rate-fmt}" } ] }
              - { id: flows, kind: kv, title: Cash flows, when: "$.FLOWS != null", columns: [ { label: N, bind: $.N } ] }
            """;

    static final String SUTRA = """
            rachana: 1
            sutra: deal
            version: 1
            match: { kind: trade }
            title: { id: $.TRADE_ID }
            panels:
              - { id: head, kind: kv, title: Head, columns: [ { label: Id, bind: $.TRADE_ID } ] }
              # the legs, shared by every swap
              - { use: legs@1, with: { rate-fmt: pct4 } }
              - { id: notes, kind: markdown, text: hi }
            """;

    @BeforeEach
    void load() throws Exception {
        Files.writeString(dir.resolve("legs.v1.block.yaml"), LEGS);
        assertThat(SutraBlocks.load(List.of(dir))).isEmpty();
    }

    @AfterEach
    void empty() {
        SutraBlocks.load(List.of());
    }

    private static List<String> ids(String text) {
        return new SutraParser().parse(text, SutraParser.STUDIO, "studio").panels().stream().map(Panel::id).toList();
    }

    private static OpResult apply(String text, String opsJson) {
        return new OpApplier().apply(text, Ops.parse(opsJson));
    }

    @Test
    void theUsesAreListedWithThePanelsTheyBringAndTheirParameters() {
        List<BlockUses.Use> uses = BlockUses.of(SUTRA);
        assertThat(uses).hasSize(1);
        BlockUses.Use u = uses.get(0);
        assertThat(u.item()).isEqualTo(1);
        assertThat(u.ref()).isEqualTo("legs@1");
        assertThat(u.version()).isEqualTo(1);
        assertThat(u.description()).isEqualTo("The legs side by side.");
        assertThat(u.params()).containsEntry("rate-fmt", "price4");
        assertThat(u.with()).containsEntry("rate-fmt", "pct4");
        assertThat(u.panels()).extracting(BlockUses.Brought::id).containsExactly("legs", "flows");
        assertThat(u.panels().get(1).when()).isEqualTo("$.FLOWS != null");
        assertThat(u.panels().get(0).panel()).isInstanceOf(java.util.Map.class);         // written out, parameters filled: what "unpack" writes
        assertThat(((java.util.Map<?, ?>) u.panels().get(0).panel()).get("id")).isEqualTo("legs");
        assertThat(u.problems()).isEmpty();

        BlockUses.Use bad = BlockUses.of(SUTRA.replace("legs@1", "legz@1").replace("rate-fmt: pct4", "rate: pct4")).get(0);
        assertThat(bad.version()).isNull();
        assertThat(bad.panels()).isEmpty();
        assertThat(bad.problems()).extracting(p -> p.code()).containsExactly(SutraBlocks.BLOCK);
    }

    @Test
    void aWrittenPanelIsEditedAtItsOwnPlaceAndABlocksPanelIsNot() {
        OpResult r = apply(SUTRA, "[{\"op\":\"setOption\",\"panel\":\"notes\",\"option\":\"title\",\"value\":\"Notes\"}]");
        assertThat(r.problems()).isEmpty();
        assertThat(r.yaml()).contains("- { id: notes, kind: markdown, text: hi, title: Notes }").contains("- { use: legs@1, with: { rate-fmt: pct4 } }");

        OpResult refused = apply(SUTRA, "[{\"op\":\"setOption\",\"panel\":\"flows\",\"option\":\"title\",\"value\":\"X\"}]");
        assertThat(refused.problems()).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo(OpException.UNEDITABLE);
            assertThat(p.message()).contains("shared block legs@1");
        });
        assertThat(refused.yaml()).isEqualTo(SUTRA);
        assertThat(apply(SUTRA, "[{\"op\":\"remove\",\"panel\":\"legs\"}]").problems()).hasSize(1);
    }

    @Test
    void addMoveAndRemoveCountAUseAsOneWrittenItem() {
        OpResult added = apply(SUTRA, "[{\"op\":\"addPanel\",\"id\":\"pv\",\"kind\":\"markdown\",\"at\":{\"after\":\"flows\"},\"options\":{\"text\":\"pv\"}}]");
        assertThat(added.problems()).isEmpty();
        assertThat(ids(added.yaml())).containsExactly("head", "legs", "flows", "pv", "notes");

        OpResult moved = apply(SUTRA, "[{\"op\":\"move\",\"panel\":\"notes\",\"before\":\"legs\"}]");
        assertThat(moved.problems()).isEmpty();
        assertThat(ids(moved.yaml())).containsExactly("head", "notes", "legs", "flows");

        OpResult side = apply(SUTRA, "[{\"op\":\"move\",\"panel\":\"head\",\"area\":\"right\",\"span\":6}]");
        assertThat(side.problems()).isEmpty();
        assertThat(side.yaml()).contains("area: right").contains("span: 6");

        OpResult removed = apply(SUTRA, "[{\"op\":\"remove\",\"panel\":\"notes\"}]");
        assertThat(removed.problems()).isEmpty();
        assertThat(ids(removed.yaml())).containsExactly("head", "legs", "flows");
    }

    @Test
    void aUsesParametersAreSetOrTheUseRemoved() {
        OpResult set = apply(SUTRA, "[{\"op\":\"setBlock\",\"use\":0,\"with\":{\"rate-fmt\":\"amount2\"}}]");
        assertThat(set.problems()).isEmpty();
        assertThat(set.yaml()).contains("# the legs, shared by every swap\n  - { use: legs@1, with: { \"rate-fmt\": amount2 } }");
        assertThat(new SutraParser().parse(set.yaml(), SutraParser.STUDIO, "studio").panels().get(1).columns().get(0).fmt()).isEqualTo("amount2");

        OpResult defaults = apply(SUTRA, "[{\"op\":\"setBlock\",\"use\":0,\"with\":{}}]");
        assertThat(defaults.yaml()).contains("  - { use: legs@1 }");

        OpResult unknown = apply(SUTRA, "[{\"op\":\"setBlock\",\"use\":0,\"with\":{\"colour\":\"red\"}}]");
        assertThat(unknown.problems()).singleElement().satisfies(p -> assertThat(p.code()).isEqualTo(SutraBlocks.BLOCK));

        OpResult gone = apply(SUTRA, "[{\"op\":\"setBlock\",\"use\":0,\"remove\":true}]");
        assertThat(gone.problems()).isEmpty();
        assertThat(ids(gone.yaml())).containsExactly("head", "notes");
        assertThat(apply(SUTRA, "[{\"op\":\"setBlock\",\"use\":1,\"with\":{}}]").problems()).singleElement()
                .satisfies(p -> assertThat(p.code()).isEqualTo(OpException.NO_PANEL));
    }
}
