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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pack may not define a mnemonic that is a reserved command word (BI, RUPAKA, FORM, FUNC, WHAT, DATAMAP), whatever the case. */
class ReservedMnemonicTest {

    @TempDir Path dir;

    private Pack pack(String mnemonic) throws Exception {
        Path p = Files.createDirectories(dir.resolve("desk"));
        Files.writeString(p.resolve("pack.yaml"), "pack: desk\nkinds: [trade]\nmnemonics:\n  " + mnemonic + ": { kind: trade, label: Trades }\n");
        return new PackLoader().load(dir, List.of("desk")).get(0);
    }

    @Test
    void reservedWordsAreRefusedInAnyCase() throws Exception {
        for (String word : List.of("BI", "bi", "RUPAKA", "rupaka", "Rupaka", "FORM", "form", "FUNC", "func", "Func", "WHAT", "what", "What", "DATAMAP", "datamap", "DataMap")) {
            Pack p = pack(word);
            assertThatThrownBy(() -> new PackLoader().properties(List.of(p))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reserved command word").hasMessageContaining(word);
        }
    }

    @Test
    void anOrdinaryMnemonicIsAccepted() throws Exception {
        assertThat(new PackLoader().properties(List.of(pack("TRD")))).containsEntry("drishti.commands.mnemonics.TRD.kind", "trade");
    }
}
